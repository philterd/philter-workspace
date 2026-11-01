/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.model;

import java.time.Instant;
import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "relational_db_data_sources")
public class RelationalDbDataSource implements GroupScopedSource {

    @Id
    private String id;

    @Indexed(unique = true, sparse = true)
    private String name;

    /**
     * AES-GCM ciphertext of the JDBC URL (see {@code SymmetricCipher}). The plaintext is a
     * standard JDBC URL, e.g. {@code jdbc:postgresql://host:5432/db}. Stored encrypted
     * because JDBC URLs can carry credentials in query parameters (e.g.
     * {@code …/db?user=alice&password=…}) and connection-string flags that some drivers
     * accept. {@code JdbcUrlValidator} refuses {@code user:pass@} userinfo and a known set
     * of dangerous query parameters at save time, but encrypting at rest is the
     * belt-and-braces guarantee: even a future validator regression can't surface a
     * cleartext credential out of the database.
     */
    private String encryptedJdbcUrl;

    /**
     * SQL query that returns the documents to be imported. The first column of each row is
     * expected to hold the document text.
     */
    private String sqlQuery;

    /**
     * AES-GCM ciphertext of the database username (see {@code SymmetricCipher}). Null/empty
     * means no explicit credential was configured — the runtime should fall back to whatever
     * authentication the JDBC URL itself supplies.
     */
    private String encryptedUsername;

    /** AES-GCM ciphertext of the database password. Null/empty when no password is set. */
    private String encryptedPassword;

    private LocalDateTime createdAt;

    /**
     * Result-column name (case-insensitive, matched the same way as {@code filename})
     * whose value the worker reads from each row to advance the per-source watermark.
     * Null/empty means watermarking is disabled — the source behaves as a one-shot
     * full-scan ingest, capped at the worker's per-run row ceiling.
     *
     * <p>When set, the admin's SQL is expected to reference {@code :lastKey} in a
     * predicate like {@code WHERE id > COALESCE(:lastKey::bigint, 0) ORDER BY id}.
     * The worker substitutes the stored {@link #lastImportedKey} for the placeholder
     * at execution time, and advances the watermark to the last row's value after a
     * successful run.
     */
    private String watermarkColumn;

    /**
     * Most-recently-imported watermark value, stored as a String so the same
     * column can hold an integer PK, a UUID, a timestamp, or any other key the
     * admin's SQL is keyset-paginating on. Type coercion against the watermark
     * column happens in the admin's SQL via an explicit cast — see the docs.
     *
     * <p>Null on a fresh source (or after a manual reset). The worker substitutes
     * {@code NULL} into the SQL for the {@code :lastKey} placeholder on a null
     * watermark, so the canonical {@code COALESCE(:lastKey, 0)} pattern picks
     * the right floor for the first run.
     */
    private String lastImportedKey;

    /** Wall-clock instant the watermark last advanced. Surfaced in the admin UI
     *  so operators can see at a glance when a source last picked up new rows. */
    private Instant lastImportedAt;

    public RelationalDbDataSource() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getEncryptedJdbcUrl() { return encryptedJdbcUrl; }
    public void setEncryptedJdbcUrl(final String encryptedJdbcUrl) { this.encryptedJdbcUrl = encryptedJdbcUrl; }

    public String getSqlQuery() { return sqlQuery; }
    public void setSqlQuery(final String sqlQuery) { this.sqlQuery = sqlQuery; }

    public String getEncryptedUsername() { return encryptedUsername; }
    public void setEncryptedUsername(final String encryptedUsername) { this.encryptedUsername = encryptedUsername; }

    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(final String encryptedPassword) { this.encryptedPassword = encryptedPassword; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getWatermarkColumn() { return watermarkColumn; }
    public void setWatermarkColumn(final String watermarkColumn) { this.watermarkColumn = watermarkColumn; }

    public String getLastImportedKey() { return lastImportedKey; }
    public void setLastImportedKey(final String lastImportedKey) { this.lastImportedKey = lastImportedKey; }

    public Instant getLastImportedAt() { return lastImportedAt; }
    public void setLastImportedAt(final Instant lastImportedAt) { this.lastImportedAt = lastImportedAt; }

    /** The groups this source is available to (#72). With none, and not {@link #allGroups}, administrators only. */
    private java.util.List<String> groupIds = new java.util.ArrayList<>();
    /** Whether this source is available to every group (#72). */
    private boolean allGroups;

    @Override
    public java.util.List<String> getGroupIds() { return groupIds; }
    @Override
    public void setGroupIds(final java.util.List<String> groupIds) {
        this.groupIds = groupIds == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(groupIds);
    }

    @Override
    public boolean isAllGroups() { return allGroups; }
    @Override
    public void setAllGroups(final boolean allGroups) { this.allGroups = allGroups; }
}
