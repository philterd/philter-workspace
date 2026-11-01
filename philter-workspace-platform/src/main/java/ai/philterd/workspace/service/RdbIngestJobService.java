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

import ai.philterd.workspace.model.BackgroundJob;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.RelationalDbDataSource;
import ai.philterd.workspace.repository.BackgroundJobRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.RelationalDbDataSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Runs Relational Database ingest jobs on a background thread. The user picks a
 * configured {@link RelationalDbDataSource} on the Add Documents page; we
 * persist a {@link BackgroundJob} and return immediately. The dispatcher later
 * claims the job atomically and calls {@link #run(String)}, which opens a JDBC
 * connection, executes the saved SQL, and enqueues one document per row.
 *
 * <p>Mirrors {@link S3IngestJobService} / {@link OpenSearchIngestJobService} —
 * same PENDING→RUNNING lifecycle, per-row processed/failed/skipped counters,
 * audit events, and inbox notification on completion.
 *
 * <h2>Row-to-Document mapping</h2>
 *
 * The data source contract is "first column of each row is the document text"
 * — see {@link RelationalDbDataSource#getSqlQuery()}. The filename is taken
 * from a column named {@value #FILENAME_COLUMN} when one is present in the
 * result set (case-insensitive match on the column label), and synthesized as
 * {@code row-N.txt} otherwise. The filename is also used as the dedupe key
 * ({@code sourceDocId}) so re-running the same ingest doesn't double-import
 * rows whose filename hasn't changed.
 *
 * <p>Source attribution stored on each imported {@code Document}:
 * <ul>
 *   <li>{@code sourceSystem = "RDB"}</li>
 *   <li>{@code sourceUrl =} the data source's JDBC URL with any embedded
 *       userinfo stripped (the URL is also surfaced in audit rows)</li>
 *   <li>{@code sourceIndex =} the data source id — namespaces the dedupe
 *       check so two RDB sources with overlapping filenames don't collide</li>
 *   <li>{@code sourceDocId =} the row's filename (synthesized if absent)</li>
 * </ul>
 *
 * <h2>Defense in depth</h2>
 *
 * The saved SQL is re-validated through {@link SqlReadOnlyValidator} on the
 * way in. The write path already gates against mutating statements, but a
 * hand-edited MongoDB row could in principle slip a {@code DELETE} past the
 * save-time guard — re-checking here means the runtime never executes
 * anything {@link SqlReadOnlyValidator} would refuse.
 *
 * <p>{@link Statement#setMaxRows(int)} caps the result set at
 * {@link #MAX_ROWS_PER_RUN}; combined with {@link Statement#setQueryTimeout(int)}
 * and {@link DriverManager#setLoginTimeout(int)}, an accidentally-broad query
 * against a huge table can't pin a worker thread indefinitely.
 */
@Service
public class RdbIngestJobService {

    private static final Logger log = LoggerFactory.getLogger(RdbIngestJobService.class);

    /** Hard ceiling on rows ingested per run. */
    static final int MAX_ROWS_PER_RUN = 100_000;

    /** Per-statement timeout (seconds) — protects against an accidentally-broad query. */
    static final int QUERY_TIMEOUT_SECONDS = 300;

    /** Connection-establishment timeout (seconds). */
    static final int LOGIN_TIMEOUT_SECONDS = 15;

    /** Column label (case-insensitive) used as the document filename when present. */
    static final String FILENAME_COLUMN = "filename";

    static final String SOURCE_SYSTEM = "RDB";

    private final BackgroundJobRepository jobRepository;
    private final RelationalDbDataSourceRepository dataSourceRepository;
    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final IngestQueueService ingestQueueService;
    private final SymmetricCipher cipher;
    private final InboxService inboxService;
    private final AuditLogService auditLogService;
    private final DataImportLogService importLogService;
    /**
     * Indirection so unit tests can supply a stub {@link Connection} without
     * needing a real database. Production callers use
     * {@link DefaultConnectionFactory#open}, which opens a fresh JDBC connection
     * from the data source's stored credentials and URL.
     */
    private final JdbcUrlValidator jdbcUrlValidator;
    private final ConnectionFactory connectionFactory;

    @Autowired
    public RdbIngestJobService(final BackgroundJobRepository jobRepository,
                               final RelationalDbDataSourceRepository dataSourceRepository,
                               final BatchRepository batchRepository,
                               final DocumentRepository documentRepository,
                               final IngestQueueService ingestQueueService,
                               final SymmetricCipher cipher,
                               final InboxService inboxService,
                               final AuditLogService auditLogService,
                               final DataImportLogService importLogService,
                               final JdbcUrlValidator jdbcUrlValidator) {
        this(jobRepository, dataSourceRepository, batchRepository, documentRepository,
                ingestQueueService, cipher, inboxService, auditLogService, importLogService,
                new DefaultConnectionFactory(), jdbcUrlValidator);
    }

    /** Test-only constructor that lets a unit test inject a stub {@link ConnectionFactory}. */
    RdbIngestJobService(final BackgroundJobRepository jobRepository,
                        final RelationalDbDataSourceRepository dataSourceRepository,
                        final BatchRepository batchRepository,
                        final DocumentRepository documentRepository,
                        final IngestQueueService ingestQueueService,
                        final SymmetricCipher cipher,
                        final InboxService inboxService,
                        final AuditLogService auditLogService,
                        final DataImportLogService importLogService,
                        final ConnectionFactory connectionFactory,
                        final JdbcUrlValidator jdbcUrlValidator) {
        this.jobRepository = jobRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.ingestQueueService = ingestQueueService;
        this.cipher = cipher;
        this.inboxService = inboxService;
        this.auditLogService = auditLogService;
        this.importLogService = importLogService;
        this.connectionFactory = connectionFactory;
        this.jdbcUrlValidator = jdbcUrlValidator;
    }

    /**
     * Persist a PENDING job. Returns the saved job so the caller can redirect to
     * the Background Jobs page. Validation failures (missing source, missing
     * batch, missing SQL/URL, dangerous SQL) produce a FAILED row right here so
     * the user gets immediate feedback instead of having to wait for the
     * dispatcher to claim and fail the job.
     */
    public BackgroundJob start(final String sourceId, final String batchId, final int priority,
                               final String actorEmail) {
        final RelationalDbDataSource source = dataSourceRepository.findById(sourceId).orElse(null);
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (source == null) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Relational database data source not found.");
        }
        if (batch == null) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Batch not found.");
        }
        if (source.getEncryptedJdbcUrl() == null || source.getEncryptedJdbcUrl().isEmpty()) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Data source has no JDBC URL configured.");
        }
        if (source.getSqlQuery() == null || source.getSqlQuery().isBlank()) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Data source has no SQL query configured.");
        }
        final SqlReadOnlyValidator.Result sqlCheck =
                SqlReadOnlyValidator.validate(source.getSqlQuery());
        if (!sqlCheck.ok()) {
            return failImmediate(sourceId, batchId, priority, actorEmail, sqlCheck.error());
        }

        final BackgroundJob job = new BackgroundJob();
        job.setId(UUID.randomUUID().toString());
        job.setType(BackgroundJob.TYPE_RDB_INGEST);
        job.setSourceId(source.getId());
        job.setSourceName(source.getName());
        job.setBatchId(batch.getId());
        job.setBatchName(batch.getName());
        job.setPriority(priority);
        job.setStatus(BackgroundJob.STATUS_PENDING);
        job.setCreatedAt(Instant.now());
        job.setCreatedBy(actorEmail == null ? "" : actorEmail);
        jobRepository.save(job);
        return job;
    }

    private BackgroundJob failImmediate(final String sourceId, final String batchId, final int priority,
                                        final String actorEmail, final String error) {
        final BackgroundJob job = new BackgroundJob();
        job.setId(UUID.randomUUID().toString());
        job.setType(BackgroundJob.TYPE_RDB_INGEST);
        job.setSourceId(sourceId);
        job.setBatchId(batchId);
        job.setPriority(priority);
        job.setStatus(BackgroundJob.STATUS_FAILED);
        job.setErrorMessage(error);
        final Instant now = Instant.now();
        job.setCreatedAt(now);
        job.setStartedAt(now);
        job.setFinishedAt(now);
        job.setCreatedBy(actorEmail == null ? "" : actorEmail);
        final BackgroundJob saved = jobRepository.save(job);
        auditJobTerminal(saved, error);
        notifyOwner(saved);
        return saved;
    }

    /**
     * Execute a previously-persisted PENDING job. Invoked by
     * {@link DataImportDispatcher} after it has atomically claimed the job.
     */
    public void run(final String jobId) {
        BackgroundJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Background job {} disappeared before it could run.", jobId);
            return;
        }
        job.setStatus(BackgroundJob.STATUS_RUNNING);
        job.setStartedAt(Instant.now());
        jobRepository.save(job);
        auditJobStarted(job);

        final RelationalDbDataSource source =
                dataSourceRepository.findById(job.getSourceId()).orElse(null);
        final Batch batch = batchRepository.findById(job.getBatchId()).orElse(null);
        if (source == null || batch == null) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Source or batch was deleted before the job ran.");
            return;
        }

        final String sql = source.getSqlQuery();
        if (source.getEncryptedJdbcUrl() == null || source.getEncryptedJdbcUrl().isEmpty()) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Data source has no JDBC URL configured.");
            return;
        }
        if (sql == null || sql.isBlank()) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Data source has no SQL query configured.");
            return;
        }
        // Defense in depth — a hand-edited Mongo row could slip a mutating
        // statement past the save-time check. Refuse to run anything the
        // create/edit path would have refused.
        final SqlReadOnlyValidator.Result sqlCheck = SqlReadOnlyValidator.validate(sql);
        if (!sqlCheck.ok()) {
            finish(job, BackgroundJob.STATUS_FAILED, sqlCheck.error());
            return;
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
            log.warn("Failed to decrypt stored connection details for job {}: {}", job.getId(), ErrorClasses.of(e));
            finish(job, BackgroundJob.STATUS_FAILED, "Could not decrypt stored connection details.");
            return;
        }
        // The URL might still carry user:pass@ if a future regression let one past
        // the validator; strip before it touches the source-attribution columns or
        // any log line. (The validator currently refuses such URLs at save time,
        // but the strip is a no-op for well-formed URLs so it's safe to keep here.)
        final String safeUrl = JdbcUrlValidator.stripUserInfo(jdbcUrl);
        // Validated again just before connecting, not only when saved (#73): the host's DNS answer may have changed.
        final JdbcUrlValidator.Result urlCheck = jdbcUrlValidator.validate(jdbcUrl);
        if (!urlCheck.ok()) {
            finish(job, BackgroundJob.STATUS_FAILED, urlCheck.error());
            return;
        }

        // Substitute the watermark placeholder. Three cases:
        //   1. SQL has no :lastKey at all — the prepared/literal path is identical.
        //   2. SQL has :lastKey AND the source has a stored lastImportedKey — bind it
        //      as a parameter. This is the steady-state incremental-ingest case.
        //   3. SQL has :lastKey but the source has no stored watermark yet —
        //      substitute the SQL literal NULL so the admin's COALESCE(:lastKey, …)
        //      pattern picks up the first-run floor without any extra UI dance.
        final boolean usesWatermark = sql.contains(SqlReadOnlyValidator.WATERMARK_PLACEHOLDER);
        final String storedWatermark = source.getLastImportedKey();
        final String runtimeSql;
        final boolean bindWatermark;
        if (usesWatermark && storedWatermark != null) {
            runtimeSql = sql.replace(SqlReadOnlyValidator.WATERMARK_PLACEHOLDER, "?");
            bindWatermark = true;
        } else if (usesWatermark) {
            runtimeSql = sql.replace(SqlReadOnlyValidator.WATERMARK_PLACEHOLDER, "NULL");
            bindWatermark = false;
        } else {
            runtimeSql = sql;
            bindWatermark = false;
        }

        try (Connection conn = connectionFactory.open(jdbcUrl, username, password);
             PreparedStatement stmt = conn.prepareStatement(runtimeSql,
                     ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            stmt.setMaxRows(MAX_ROWS_PER_RUN);
            stmt.setFetchSize(1_000);
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            if (bindWatermark) {
                // Always bind as String. Type coercion against the watermark column
                // is the admin's responsibility via an explicit cast in their SQL
                // (e.g. `id > :lastKey::bigint` on Postgres) — see the docs.
                stmt.setString(1, storedWatermark);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                processRows(job, batch, source, safeUrl, rs);
            }
            finish(job, BackgroundJob.STATUS_COMPLETED, null);
        } catch (SQLException e) {
            log.warn("Job {} ({} from {}) failed: {}", job.getId(), job.getType(), job.getSourceName(), ErrorClasses.of(e));
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Could not run RDB ingest: " + formatSqlError(e));
        } catch (Exception e) {
            log.warn("Job {} ({} from {}) failed: {}", job.getId(), job.getType(), job.getSourceName(), ErrorClasses.of(e));
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Could not run RDB ingest: " + e.getMessage());
        }
    }

    /**
     * Iterate the result set, enqueueing one document per row. The first column
     * is the document text (contract documented on
     * {@link RelationalDbDataSource#getSqlQuery()}); the filename comes from a
     * column literally named {@value #FILENAME_COLUMN} when present, otherwise
     * we synthesize {@code row-N.txt} so the row still has a deterministic
     * identity for the dedupe key.
     */
    private void processRows(final BackgroundJob job, final Batch batch,
                             final RelationalDbDataSource source, final String safeUrl,
                             final ResultSet rs) throws SQLException {
        final ResultSetMetaData md = rs.getMetaData();
        if (md.getColumnCount() < 1) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "SQL query returned no columns. The first column must hold the document text.");
            return;
        }
        final int filenameColumn = findColumn(md, FILENAME_COLUMN);
        // Watermark column is opt-in per source. Resolve once; if the admin
        // named a column that the SQL doesn't actually return, lookup yields
        // -1 and we skip the advance — the source still ingests rows, the
        // watermark just won't move and the operator will notice via the
        // "Last advanced …" line on the admin page.
        final int watermarkColIdx = source.getWatermarkColumn() != null
                && !source.getWatermarkColumn().isBlank()
                ? findColumn(md, source.getWatermarkColumn())
                : -1;
        final String sourceIndex = source.getId();
        // Track the last watermark value seen across the cursor. Because the
        // admin's SQL is required to ORDER BY this column for keyset paging
        // to work, "last seen" equals "max seen" — but using last-seen avoids
        // any String vs. numeric Comparator gymnastics.
        String lastSeenWatermark = null;
        long rowIndex = 0;
        while (rs.next()) {
            rowIndex++;
            final String text = rs.getString(1);
            final String filename = filenameColumn > 0
                    ? rs.getString(filenameColumn)
                    : null;
            final String resolvedFilename = (filename == null || filename.isBlank())
                    ? "row-" + rowIndex + ".txt"
                    : filename;
            final String sourceDocId = resolvedFilename;
            if (watermarkColIdx > 0) {
                final String thisWatermark = rs.getString(watermarkColIdx);
                if (thisWatermark != null) lastSeenWatermark = thisWatermark;
            }
            if (text == null || text.isEmpty()) {
                final String reason = "Row " + rowIndex
                        + " has a null/empty first column (no document text).";
                bumpFailed(job, reason);
                importLogService.failed(job.getId(), resolvedFilename, sourceDocId, reason);
                continue;
            }
            if (documentRepository.existsBySourceIndexAndSourceDocId(sourceIndex, sourceDocId)) {
                recordSkipped(job, batch, resolvedFilename, sourceIndex, sourceDocId, safeUrl);
                importLogService.skipped(job.getId(), resolvedFilename, sourceDocId);
                continue;
            }
            try {
                final Document doc = ingestQueueService.enqueueText(batch, resolvedFilename, text,
                        job.getPriority());
                doc.setSourceSystem(SOURCE_SYSTEM);
                doc.setSourceUrl(safeUrl);
                doc.setSourceIndex(sourceIndex);
                doc.setSourceDocId(sourceDocId);
                doc.setImportedAt(java.time.LocalDateTime.now());
                documentRepository.save(doc);
                auditDocumentImport(job, doc, "SUCCESS");
                bumpProcessed(job);
                importLogService.success(job.getId(), resolvedFilename, sourceDocId);
            } catch (Exception e) {
                final String reason = "Failed to enqueue \"" + resolvedFilename + "\": "
                        + e.getMessage();
                log.warn("Job {} ({} from {}): an item failed: {}", job.getId(), job.getType(), job.getSourceName(), ErrorClasses.of(e));
                bumpFailed(job, reason);
                importLogService.failed(job.getId(), resolvedFilename, sourceDocId, e.getMessage());
            }
        }
        // Once the cursor's drained, set totalDocuments so the Background Jobs
        // page can show "Processed N of N" instead of "Processed N (total unknown)".
        // Doing it here (rather than via a pre-COUNT(*) query) keeps the path
        // dialect-agnostic and avoids a second round trip.
        job.setTotalDocuments(rowIndex);
        jobRepository.save(job);

        // Advance the watermark on the source row. We do this only when the
        // cursor drained cleanly (we reached this point — any SQLException
        // upstream would have skipped past us via the catch in startRun()),
        // and only when the last-seen value actually moved. A null
        // lastSeenWatermark (no watermark column, or every row's value was
        // null) leaves the stored watermark untouched.
        if (watermarkColIdx > 0 && lastSeenWatermark != null
                && !lastSeenWatermark.equals(source.getLastImportedKey())) {
            final String previous = source.getLastImportedKey();
            source.setLastImportedKey(lastSeenWatermark);
            source.setLastImportedAt(Instant.now());
            dataSourceRepository.save(source);
            final Map<String, Object> details = new LinkedHashMap<>();
            details.put("sourceId", source.getId());
            details.put("from", previous == null ? "" : previous);
            details.put("to", lastSeenWatermark);
            details.put("rowsProcessed", rowIndex);
            auditLogService.log("RDB_WATERMARK_ADVANCE", "RelationalDbDataSource",
                    source.getId(), details);
        }
    }

    /**
     * Find a column whose label matches {@code name} (case-insensitive). Returns the
     * 1-based column index, or -1 when no such column exists. Used to locate the
     * filename column without forcing operators to alias it explicitly.
     */
    private static int findColumn(final ResultSetMetaData md, final String name) throws SQLException {
        final int count = md.getColumnCount();
        for (int i = 1; i <= count; i++) {
            final String label = md.getColumnLabel(i);
            if (label != null && label.equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /**
     * Persist a SKIPPED placeholder {@link Document} for a row that's already been
     * imported (matched by {@code (sourceIndex, sourceDocId)}). The row carries the
     * source attribution so the audit trail points back to the row, but no content
     * — re-running an ingest doesn't double-store the text.
     */
    private void recordSkipped(final BackgroundJob job, final Batch batch, final String filename,
                               final String sourceIndex, final String sourceDocId,
                               final String safeUrl) {
        final Document doc = new Document();
        doc.setId(UUID.randomUUID().toString());
        doc.setBatchId(batch.getId());
        doc.setFilename(filename);
        doc.setOriginalText("");
        doc.setSourceSystem(SOURCE_SYSTEM);
        doc.setSourceUrl(safeUrl);
        doc.setSourceIndex(sourceIndex);
        doc.setSourceDocId(sourceDocId);
        doc.setImportedAt(java.time.LocalDateTime.now());
        doc.setCreatedAt(java.time.LocalDateTime.now());
        doc.setPriority(job.getPriority());
        doc.changeStatus("SKIPPED");
        documentRepository.save(doc);
        auditDocumentImport(job, doc, "SKIPPED");
        bumpSkipped(job);
    }

    private void bumpProcessed(final BackgroundJob job) {
        job.setProcessedDocuments(job.getProcessedDocuments() + 1);
        jobRepository.save(job);
    }

    private void bumpFailed(final BackgroundJob job, final String reason) {
        job.setFailedDocuments(job.getFailedDocuments() + 1);
        if (reason != null && !reason.isBlank()
                && job.getFailureMessages().size() < BackgroundJob.MAX_FAILURE_MESSAGES) {
            job.getFailureMessages().add(reason);
        }
        jobRepository.save(job);
    }

    private void bumpSkipped(final BackgroundJob job) {
        job.setSkippedDocuments(job.getSkippedDocuments() + 1);
        jobRepository.save(job);
    }

    private void finish(final BackgroundJob job, final String status, final String error) {
        job.setStatus(status);
        if (error != null) job.setErrorMessage(error);
        job.setFinishedAt(Instant.now());
        jobRepository.save(job);
        auditJobTerminal(job, error);
        notifyOwner(job);
    }

    private void auditJobStarted(final BackgroundJob job) {
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", job.getId());
        details.put("type", BackgroundJob.TYPE_RDB_INGEST);
        details.put("sourceId", job.getSourceId() == null ? "" : job.getSourceId());
        details.put("sourceName", job.getSourceName() == null ? "" : job.getSourceName());
        details.put("batchId", job.getBatchId() == null ? "" : job.getBatchId());
        details.put("batchName", job.getBatchName() == null ? "" : job.getBatchName());
        auditLogService.logForUser(actor(job), "DATA_IMPORT_STARTED",
                "BackgroundJob", job.getId(),
                AuditLogService.OUTCOME_SUCCESS, details);
    }

    private void auditDocumentImport(final BackgroundJob job, final Document doc,
                                     final String outcome) {
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", job.getId());
        details.put("batchId", doc.getBatchId() == null ? "" : doc.getBatchId());
        details.put("filename", doc.getFilename() == null ? "" : doc.getFilename());
        details.put("sourceSystem", doc.getSourceSystem() == null ? "" : doc.getSourceSystem());
        details.put("sourceUrl", doc.getSourceUrl() == null ? "" : doc.getSourceUrl());
        details.put("sourceIndex", doc.getSourceIndex() == null ? "" : doc.getSourceIndex());
        details.put("sourceDocId", doc.getSourceDocId() == null ? "" : doc.getSourceDocId());
        auditLogService.logForUser(actor(job), "DOCUMENT_IMPORT",
                "Document", doc.getId(),
                outcome == null ? AuditLogService.OUTCOME_SUCCESS : outcome, details);
    }

    private void auditJobTerminal(final BackgroundJob job, final String error) {
        final boolean ok = BackgroundJob.STATUS_COMPLETED.equals(job.getStatus());
        final String action = ok ? "DATA_IMPORT_COMPLETED" : "DATA_IMPORT_FAILED";
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", job.getId());
        details.put("type", BackgroundJob.TYPE_RDB_INGEST);
        details.put("sourceId", job.getSourceId() == null ? "" : job.getSourceId());
        details.put("batchId", job.getBatchId() == null ? "" : job.getBatchId());
        details.put("processed", job.getProcessedDocuments());
        details.put("failed", job.getFailedDocuments());
        details.put("skipped", job.getSkippedDocuments());
        if (error != null && !error.isBlank()) details.put("error", error);
        auditLogService.logForUser(actor(job), action, "BackgroundJob", job.getId(),
                ok ? AuditLogService.OUTCOME_SUCCESS : AuditLogService.OUTCOME_FAILURE,
                details);
    }

    private static String actor(final BackgroundJob job) {
        return job.getCreatedBy() == null || job.getCreatedBy().isBlank()
                ? null : job.getCreatedBy();
    }

    private void notifyOwner(final BackgroundJob job) {
        final String email = job.getCreatedBy();
        if (email == null || email.isBlank()) return;
        inboxService.sendByEmail(email, summarize(job));
    }

    private static String summarize(final BackgroundJob job) {
        final String source = job.getSourceName() == null || job.getSourceName().isBlank()
                ? "(unknown source)" : job.getSourceName();
        final String batch = job.getBatchName() == null || job.getBatchName().isBlank()
                ? "(unknown batch)" : job.getBatchName();
        final boolean ok = BackgroundJob.STATUS_COMPLETED.equals(job.getStatus());
        final StringBuilder sb = new StringBuilder();
        sb.append("Relational database import from \"").append(source)
                .append("\" into batch \"").append(batch);
        if (ok) {
            sb.append("\" completed. Processed ").append(job.getProcessedDocuments());
            if (job.getTotalDocuments() >= 0) {
                sb.append(" of ").append(job.getTotalDocuments());
            }
            sb.append(" row(s)");
            if (job.getFailedDocuments() > 0) {
                sb.append(", ").append(job.getFailedDocuments()).append(" failed");
            }
            if (job.getSkippedDocuments() > 0) {
                sb.append(", ").append(job.getSkippedDocuments())
                        .append(" skipped (already imported)");
            }
            sb.append('.');
        } else {
            sb.append("\" failed.");
            if (job.getErrorMessage() != null && !job.getErrorMessage().isBlank()) {
                sb.append(' ').append(job.getErrorMessage());
                if (!job.getErrorMessage().endsWith(".")) sb.append('.');
            }
            if (job.getProcessedDocuments() > 0 || job.getFailedDocuments() > 0) {
                sb.append(" Processed ").append(job.getProcessedDocuments());
                if (job.getFailedDocuments() > 0) {
                    sb.append(", ").append(job.getFailedDocuments()).append(" failed");
                }
                sb.append(" before stopping.");
            }
        }
        return sb.toString();
    }

    /**
     * Pull the most-useful bit out of a {@link SQLException}. SQLSTATE plus the
     * top-level message gives operators something to grep for ("08001 means the
     * driver can't reach the host") without dumping a stack trace into the
     * background-jobs page.
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
     * Opens a JDBC connection for a given data source. Production:
     * {@link DefaultConnectionFactory} delegates to {@link DriverManager}.
     * Tests: an injected double can return a fake / in-memory connection.
     */
    interface ConnectionFactory {
        Connection open(String jdbcUrl, String username, String password) throws SQLException;
    }

    /** Default factory used by Spring. */
    static final class DefaultConnectionFactory implements ConnectionFactory {
        @Override
        public Connection open(final String jdbcUrl, final String username, final String password)
                throws SQLException {
            final Properties props = new Properties();
            if (username != null) props.setProperty("user", username);
            if (password != null) props.setProperty("password", password);
            final int previous = DriverManager.getLoginTimeout();
            DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
            try {
                return DriverManager.getConnection(jdbcUrl, props);
            } finally {
                DriverManager.setLoginTimeout(previous);
            }
        }
    }
}
