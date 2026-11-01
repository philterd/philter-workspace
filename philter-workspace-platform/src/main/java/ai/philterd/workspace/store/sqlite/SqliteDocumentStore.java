/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store.sqlite;

import ai.philterd.workspace.service.PiiFieldEncryption;
import ai.philterd.workspace.service.SymmetricCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Stores entities as JSON documents in SQLite, one table per collection, with MongoDB's save
 * semantics: a save inserts or replaces the whole document, and an entity with a
 * {@code @Version} field is written only if its version is unchanged.
 *
 * <p>PII fields are encrypted with {@link PiiFieldEncryption} before every write and decrypted
 * after every read, as the MongoDB store does.</p>
 */
public class SqliteDocumentStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteDocumentStore.class);

    /** Folds case for {@code IgnoreCase} comparisons; registered on each connection. */
    static final String FOLD = "workspace_fold";

    private final JdbcTemplate jdbc;
    private final SymmetricCipher cipher;
    private final SqliteJson json = new SqliteJson();
    private final Map<Class<?>, CollectionInfo> collections = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public SqliteDocumentStore(final DataSource dataSource, final SymmetricCipher cipher) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.cipher = cipher;
    }

    /** Create the table and indexes for {@code type} now rather than on first use. */
    public void register(final Class<?> type) {
        info(type);
    }

    CollectionInfo info(final Class<?> type) {
        return collections.computeIfAbsent(type, this::createCollection);
    }

    private CollectionInfo createCollection(final Class<?> type) {
        final CollectionInfo info = new CollectionInfo(type);
        jdbc.execute("CREATE TABLE IF NOT EXISTS \"" + info.table + "\" ("
                + "id TEXT PRIMARY KEY NOT NULL, version INTEGER, doc TEXT NOT NULL) STRICT");
        for (String retired : info.retiredIndexes) {
            jdbc.execute("DROP INDEX IF EXISTS \"" + retired + "\"");
        }
        for (CollectionInfo.IndexDef index : info.indexes) {
            final List<String> exprs = new ArrayList<>();
            for (String path : index.paths()) {
                exprs.add(Where.field(path));
            }
            jdbc.execute("CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX IF NOT EXISTS \"" + index.name()
                    + "\" ON \"" + info.table + "\" (" + String.join(", ", exprs) + ")"
                    + (index.partialFilter() == null ? "" : " WHERE " + index.partialFilter().sql()));
        }
        log.debug("SQLite collection {} ready with {} index(es)", info.table, info.indexes.size());
        return info;
    }

    // ---- writes

    public <T> T save(final T entity) {
        final CollectionInfo info = info(entity.getClass());
        String id = info.id(entity);
        if (id == null) {
            id = newId();
            info.setId(entity, id);
        }
        final String docId = id;
        if (info.versionField == null) {
            translate(info, () -> jdbc.update("INSERT INTO \"" + info.table + "\" (id, version, doc) VALUES (?, NULL, ?) "
                    + "ON CONFLICT(id) DO UPDATE SET doc = excluded.doc", docId, toDoc(entity)));
            return entity;
        }
        final Long current = info.version(entity);
        if (current == null) {
            info.setVersion(entity, 0L);
            try {
                translate(info, () -> jdbc.update("INSERT INTO \"" + info.table + "\" (id, version, doc) VALUES (?, 0, ?)",
                        docId, toDoc(entity)));
            } catch (RuntimeException e) {
                info.setVersion(entity, null);
                throw e;
            }
            return entity;
        }
        final long next = current + 1;
        info.setVersion(entity, next);
        final int updated;
        try {
            updated = translate(info, () -> jdbc.update("UPDATE \"" + info.table + "\" SET version = ?, doc = ? "
                    + "WHERE id = ? AND version = ?", next, toDoc(entity), docId, current));
        } catch (RuntimeException e) {
            info.setVersion(entity, current);
            throw e;
        }
        if (updated == 0) {
            info.setVersion(entity, current);
            throw new OptimisticLockingFailureException("Version " + current + " of " + info.type.getSimpleName()
                    + " " + docId + " is no longer current");
        }
        return entity;
    }

    /** Delete {@code entity}; a versioned entity only if its version is unchanged. */
    public void delete(final Object entity) {
        final CollectionInfo info = info(entity.getClass());
        final String id = info.id(entity);
        if (id == null) {
            return;
        }
        if (info.versionField == null || info.version(entity) == null) {
            jdbc.update("DELETE FROM \"" + info.table + "\" WHERE id = ?", id);
            return;
        }
        final int n = jdbc.update("DELETE FROM \"" + info.table + "\" WHERE id = ? AND version = ?", id, info.version(entity));
        if (n == 0 && exists(entity.getClass(), Where.eq("id", id))) {
            throw new OptimisticLockingFailureException("Version " + info.version(entity) + " of "
                    + info.type.getSimpleName() + " " + id + " is no longer current");
        }
    }

    public long delete(final Class<?> type, final Where where) {
        final CollectionInfo info = info(type);
        return jdbc.update("DELETE FROM \"" + info.table + "\" WHERE " + where.sql(), bind(where.params()));
    }

    public <T> List<T> deleteReturning(final Class<T> type, final Where where) {
        final CollectionInfo info = info(type);
        return jdbc.query("DELETE FROM \"" + info.table + "\" WHERE " + where.sql() + " RETURNING doc",
                (rs, i) -> fromDoc(rs.getString(1), type), bind(where.params()));
    }

    /**
     * Set and remove fields on the one document matching {@code where} in a single statement,
     * returning it as updated, or {@code null} when nothing matched. The version is left
     * unchanged, as the MongoDB store's atomic updates also leave it.
     */
    public <T> T updateOne(final Class<T> type, final Where where, final Map<String, ?> set,
                           final Collection<String> unset) {
        final CollectionInfo info = info(type);
        final List<Object> params = new ArrayList<>();
        final String sql = "UPDATE \"" + info.table + "\" SET doc = " + patch(set, unset, params)
                + " WHERE " + where.sql() + " RETURNING doc";
        params.addAll(bindList(where.params()));
        final List<T> rows = translate(info, () -> jdbc.query(sql, (rs, i) -> fromDoc(rs.getString(1), type), params.toArray()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Set and remove fields on every document matching {@code where}; returns how many changed. */
    public long updateAll(final Class<?> type, final Where where, final Map<String, ?> set,
                          final Collection<String> unset) {
        final CollectionInfo info = info(type);
        final List<Object> params = new ArrayList<>();
        final String sql = "UPDATE \"" + info.table + "\" SET doc = " + patch(set, unset, params) + " WHERE " + where.sql();
        params.addAll(bindList(where.params()));
        return translate(info, () -> jdbc.update(sql, params.toArray()));
    }

    private String patch(final Map<String, ?> set, final Collection<String> unset, final List<Object> params) {
        String expr = "doc";
        if (!unset.isEmpty()) {
            final List<String> paths = new ArrayList<>();
            for (String path : unset) {
                Where.field(path);
                paths.add("'$." + path + "'");
            }
            expr = "json_remove(" + expr + ", " + String.join(", ", paths) + ")";
        }
        if (!set.isEmpty()) {
            final StringBuilder sb = new StringBuilder("json_set(").append(expr);
            for (Map.Entry<String, ?> e : set.entrySet()) {
                Where.field(e.getKey());
                sb.append(", '$.").append(e.getKey()).append("', json(?)");
                params.add(json.write(e.getValue()));
            }
            expr = sb.append(')').toString();
        }
        return expr;
    }

    // ---- reads

    public <T> Optional<T> findById(final Class<T> type, final String id) {
        final List<T> rows = find(type, Where.eq("id", id), Sort.unsorted(), 1, 0);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Matching documents in {@code sort} order; {@code limit} below 0 means no limit. */
    public <T> List<T> find(final Class<T> type, final Where where, final Sort sort, final long limit, final long offset) {
        final CollectionInfo info = info(type);
        final StringBuilder sql = new StringBuilder("SELECT doc FROM \"").append(info.table).append("\" WHERE ")
                .append(where.sql()).append(orderBy(sort));
        if (limit >= 0 || offset > 0) {
            sql.append(" LIMIT ").append(limit >= 0 ? limit : -1).append(" OFFSET ").append(Math.max(0, offset));
        }
        return jdbc.query(sql.toString(), (rs, i) -> fromDoc(rs.getString(1), type), bind(where.params()));
    }

    public long count(final Class<?> type, final Where where) {
        final CollectionInfo info = info(type);
        final Long n = jdbc.queryForObject("SELECT COUNT(*) FROM \"" + info.table + "\" WHERE " + where.sql(),
                Long.class, bind(where.params()));
        return n == null ? 0 : n;
    }

    public boolean exists(final Class<?> type, final Where where) {
        final CollectionInfo info = info(type);
        return !jdbc.queryForList("SELECT 1 FROM \"" + info.table + "\" WHERE " + where.sql() + " LIMIT 1",
                bind(where.params())).isEmpty();
    }

    private static String orderBy(final Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return "";
        }
        final List<String> parts = new ArrayList<>();
        for (Sort.Order o : sort) {
            String expr = Where.field(o.getProperty());
            if (o.isIgnoreCase()) {
                expr = FOLD + "(" + expr + ")";
            }
            String part = expr + (o.isAscending() ? " ASC" : " DESC");
            if (o.getNullHandling() == Sort.NullHandling.NULLS_FIRST) {
                part += " NULLS FIRST";
            } else if (o.getNullHandling() == Sort.NullHandling.NULLS_LAST) {
                part += " NULLS LAST";
            }
            parts.add(part);
        }
        return " ORDER BY " + String.join(", ", parts);
    }

    // ---- conversion

    private String toDoc(final Object entity) {
        PiiFieldEncryption.encrypt(cipher, entity);
        try {
            return json.write(entity);
        } finally {
            PiiFieldEncryption.decrypt(cipher, entity);
        }
    }

    private <T> T fromDoc(final String doc, final Class<T> type) {
        final T entity = json.read(doc, type);
        PiiFieldEncryption.decrypt(cipher, entity);
        return entity;
    }

    Object toSql(final Object value) {
        return json.toSql(value);
    }

    private Object[] bind(final List<Object> params) {
        return bindList(params).toArray();
    }

    private List<Object> bindList(final List<Object> params) {
        final List<Object> out = new ArrayList<>(params.size());
        for (Object p : params) {
            out.add(json.toSql(p));
        }
        return out;
    }

    /** A 24-character hex id, time-ordered like a MongoDB ObjectId. */
    private String newId() {
        final byte[] b = new byte[12];
        random.nextBytes(b);
        final int seconds = (int) (System.currentTimeMillis() / 1000);
        b[0] = (byte) (seconds >>> 24);
        b[1] = (byte) (seconds >>> 16);
        b[2] = (byte) (seconds >>> 8);
        b[3] = (byte) seconds;
        return HexFormat.of().formatHex(b);
    }

    private static <R> R translate(final CollectionInfo info, final Supplier<R> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLiteException se
                        && (se.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
                        || se.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY)) {
                    // SQLite's message names the index, not the value, but keep it out anyway.
                    throw new DuplicateKeyException("Duplicate key in " + info.table);
                }
            }
            throw e;
        }
    }
}
