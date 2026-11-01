/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.service;

import ai.philterd.workspace.model.RelationalDbDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Executes the stored SQL for a {@link RelationalDbDataSource} against the
 * configured JDBC URL and returns the first few rows, for the "Preview"
 * button on the Admin → Data Sources page.
 *
 * <p>Row cap is enforced via {@link Statement#setMaxRows(int)} rather than
 * by rewriting the operator's SQL with a {@code LIMIT} clause — that keeps
 * the preview dialect-agnostic (Oracle's {@code FETCH FIRST N ROWS ONLY},
 * SQL Server's {@code TOP N}, PostgreSQL/MySQL's {@code LIMIT N} would all
 * require parsing and rewriting the saved query). The driver enforces the
 * cap at the protocol level, so the database doesn't stream more rows than
 * we need.
 *
 * <p>Defence-in-depth: even though the SQL was validated when the data
 * source was saved, we re-run {@link SqlReadOnlyValidator} on the way in
 * here. An admin could in principle hand-edit the stored row in MongoDB
 * to slip a mutating statement past the original guard.
 */
@Service
public class RdbPreviewService {

    private static final Logger log = LoggerFactory.getLogger(RdbPreviewService.class);

    /** Hard cap on rows the preview will fetch. Wired into the JDBC statement. */
    public static final int MAX_ROWS = 10;

    /**
     * Connection-establishment timeout. Anything slower than this and the
     * preview popup would feel broken; the operator would rather see a
     * clear timeout error than a spinner that hangs the page.
     */
    private static final int LOGIN_TIMEOUT_SECONDS = 8;

    /** Statement-execution timeout. Same reasoning as the login timeout. */
    private static final int QUERY_TIMEOUT_SECONDS = 8;

    private final SymmetricCipher cipher;
    private final JdbcUrlValidator jdbcUrlValidator;

    public RdbPreviewService(final SymmetricCipher cipher, final JdbcUrlValidator jdbcUrlValidator) {
        this.cipher = cipher;
        this.jdbcUrlValidator = jdbcUrlValidator;
    }

    /**
     * Run the data source's SQL with a {@link #MAX_ROWS} cap and return the
     * column names plus the rows. Each cell is rendered to a {@link String}
     * (or {@code null}) so the JSON payload doesn't have to carry per-cell
     * type information for the front end. Binary columns are reported as
     * {@code <BLOB: n bytes>} placeholders rather than dumped verbatim —
     * the preview popup is for human eyeballing, not for round-tripping
     * arbitrary types.
     */
    public Result preview(final RelationalDbDataSource source) {
        if (source == null) {
            return Result.failed("Data source not found.");
        }
        final String sql = source.getSqlQuery();
        if (source.getEncryptedJdbcUrl() == null || source.getEncryptedJdbcUrl().isEmpty()) {
            return Result.failed("Data source has no JDBC URL.");
        }
        if (sql == null || sql.isBlank()) {
            return Result.failed("Data source has no SQL query.");
        }
        final SqlReadOnlyValidator.Result sqlCheck = SqlReadOnlyValidator.validate(sql);
        if (!sqlCheck.ok()) {
            return Result.failed(sqlCheck.error());
        }

        final String jdbcUrl;
        final String username;
        final String password;
        try {
            jdbcUrl = cipher.decrypt(source.getEncryptedJdbcUrl());
            username = source.getEncryptedUsername() == null || source.getEncryptedUsername().isEmpty()
                    ? null : cipher.decrypt(source.getEncryptedUsername());
            password = source.getEncryptedPassword() == null || source.getEncryptedPassword().isEmpty()
                    ? null : cipher.decrypt(source.getEncryptedPassword());
        } catch (RuntimeException e) {
            log.warn("Failed to decrypt stored connection details for RDB preview: {}", e.getMessage());
            return Result.failed("Could not decrypt stored connection details.");
        }

        // Validated again just before connecting, not only when saved (#73).
        final JdbcUrlValidator.Result urlCheck = jdbcUrlValidator.validate(jdbcUrl);
        if (!urlCheck.ok()) {
            return Result.failed(urlCheck.error());
        }

        final Properties props = new Properties();
        if (username != null) props.setProperty("user", username);
        if (password != null) props.setProperty("password", password);

        // DriverManager has a JVM-wide login timeout knob — set it for this
        // call and reset it afterwards so we don't leak it onto unrelated
        // callers (other parts of the app may set their own).
        final int previousLoginTimeout = DriverManager.getLoginTimeout();
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        try (Connection conn = DriverManager.getConnection(jdbcUrl, props);
             Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                     ResultSet.CONCUR_READ_ONLY)) {
            // Protocol-level cap so the database itself doesn't stream more
            // rows than we'll display. setMaxRows(0) means unlimited, so
            // MAX_ROWS must be > 0.
            stmt.setMaxRows(MAX_ROWS);
            stmt.setFetchSize(MAX_ROWS);
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                final ResultSetMetaData md = rs.getMetaData();
                final int columnCount = md.getColumnCount();
                final List<String> columns = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(md.getColumnLabel(i));
                }
                final List<Map<String, Object>> rows = new ArrayList<>(MAX_ROWS);
                while (rs.next()) {
                    final Map<String, Object> row = new LinkedHashMap<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(columns.get(i - 1), renderCell(rs, md, i));
                    }
                    rows.add(row);
                }
                return Result.ok(columns, rows);
            }
        } catch (SQLException e) {
            return Result.failed(formatSqlError(e));
        } finally {
            DriverManager.setLoginTimeout(previousLoginTimeout);
        }
    }

    /**
     * Convert a JDBC cell into a string the JSON layer can serialise without
     * loss. Binary columns are summarised rather than dumped so the preview
     * popup doesn't blow up on a {@code BYTEA} or {@code BLOB} field.
     */
    private static Object renderCell(final ResultSet rs, final ResultSetMetaData md, final int i)
            throws SQLException {
        final int type = md.getColumnType(i);
        switch (type) {
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB: {
                final byte[] bytes = rs.getBytes(i);
                if (rs.wasNull()) return null;
                return "<BLOB: " + bytes.length + " bytes>";
            }
            default: {
                final Object value = rs.getObject(i);
                if (rs.wasNull() || value == null) return null;
                return value.toString();
            }
        }
    }

    /**
     * Strip the most-useful bit of error out of a {@link SQLException}.
     * Driver messages tend to include stack-trace-like noise the operator
     * doesn't need; the SQL state plus the top-level message is usually
     * the right amount.
     */
    private static String formatSqlError(final SQLException e) {
        final String state = e.getSQLState();
        final String message = e.getMessage();
        if (state == null || state.isBlank()) {
            return message == null ? e.getClass().getSimpleName() : message;
        }
        return "[" + state + "] " + (message == null ? e.getClass().getSimpleName() : message);
    }

    /**
     * Preview outcome. {@link #ok} carries the column list and the rendered
     * rows; on failure it carries a human-readable error and the two row
     * lists are empty.
     */
    public record Result(boolean ok, String error,
                         List<String> columns, List<Map<String, Object>> rows) {
        public static Result ok(final List<String> columns, final List<Map<String, Object>> rows) {
            return new Result(true, null, columns, rows);
        }
        public static Result failed(final String error) {
            return new Result(false, error, Collections.emptyList(), Collections.emptyList());
        }
    }
}
