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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RdbIngestJobService}. JDBC calls go through a Mockito-driven
 * stub {@link Connection}/{@link Statement}/{@link ResultSet} chain — no real database
 * is required.
 */
class RdbIngestJobServiceTest {

    private BackgroundJobRepository jobRepository;
    private RelationalDbDataSourceRepository dataSourceRepository;
    private BatchRepository batchRepository;
    private DocumentRepository documentRepository;
    private IngestQueueService ingestQueueService;
    private SymmetricCipher cipher;
    private InboxService inboxService;
    private AuditLogService auditLogService;
    private DataImportLogService importLogService;
    private RdbIngestJobService service;
    // Each test plugs the connection's stmt → rs chain into these fields so the
    // factory below returns a Connection that issues exactly what the test wants.
    private Connection stubConnection;
    private PreparedStatement stubStatement;

    @BeforeEach
    void setUp() throws SQLException {
        jobRepository = mock(BackgroundJobRepository.class);
        when(jobRepository.save(any(BackgroundJob.class)))
                .thenAnswer(inv -> inv.getArgument(0, BackgroundJob.class));
        dataSourceRepository = mock(RelationalDbDataSourceRepository.class);
        batchRepository = mock(BatchRepository.class);
        documentRepository = mock(DocumentRepository.class);
        when(documentRepository.save(any(Document.class)))
                .thenAnswer(inv -> inv.getArgument(0, Document.class));
        ingestQueueService = mock(IngestQueueService.class);
        when(ingestQueueService.enqueueText(any(Batch.class), anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> {
                    final Document d = new Document();
                    d.setId("doc-" + inv.getArgument(1, String.class));
                    d.setFilename(inv.getArgument(1, String.class));
                    return d;
                });
        cipher = mock(SymmetricCipher.class);
        when(cipher.decrypt(anyString())).thenAnswer(inv -> {
            final String s = inv.getArgument(0, String.class);
            return s.startsWith("enc:") ? s.substring(4) : s;
        });
        inboxService = mock(InboxService.class);
        auditLogService = mock(AuditLogService.class);
        importLogService = mock(DataImportLogService.class);

        stubConnection = mock(Connection.class);
        stubStatement = mock(PreparedStatement.class);
        when(stubConnection.prepareStatement(anyString(), anyInt(), anyInt()))
                .thenReturn(stubStatement);
        // setMaxRows / setQueryTimeout / setFetchSize / setString: nothing to stub, they're void.

        service = new RdbIngestJobService(jobRepository, dataSourceRepository, batchRepository,
                documentRepository, ingestQueueService, cipher, inboxService,
                auditLogService, importLogService,
                (url, user, pass) -> stubConnection, new JdbcUrlValidator(new DataSourceHostAllowList("")));
    }

    // ---------- start() validation ----------

    @Test
    void startFailsImmediatelyWhenSourceIsMissing() {
        when(dataSourceRepository.findById("missing")).thenReturn(Optional.empty());
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));

        final BackgroundJob job = service.start("missing", "b-1", 2, "alice@x.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertEquals("Relational database data source not found.", job.getErrorMessage());
    }

    @Test
    void startFailsImmediatelyWhenBatchIsMissing() {
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(source("src-1")));
        when(batchRepository.findById("ghost")).thenReturn(Optional.empty());

        final BackgroundJob job = service.start("src-1", "ghost", 2, "alice@x.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertEquals("Batch not found.", job.getErrorMessage());
    }

    @Test
    void startFailsImmediatelyWhenJdbcUrlIsBlank() {
        final RelationalDbDataSource s = source("src-1");
        // The URL field is now stored encrypted; an empty ciphertext stands in
        // for "no URL configured" (the controller never persists an empty plaintext).
        s.setEncryptedJdbcUrl("");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(s));
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));

        final BackgroundJob job = service.start("src-1", "b-1", 2, "alice@x.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertTrue(job.getErrorMessage().contains("JDBC URL"));
    }

    @Test
    void startFailsImmediatelyWhenSqlIsBlank() {
        final RelationalDbDataSource s = source("src-1");
        s.setSqlQuery("   ");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(s));
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));

        final BackgroundJob job = service.start("src-1", "b-1", 2, "alice@x.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertTrue(job.getErrorMessage().contains("SQL query"));
    }

    @Test
    void startFailsImmediatelyWhenSqlIsDangerous() {
        // Defense in depth: even though the saved-state guard refuses these,
        // start() re-runs the validator so a hand-edited Mongo row can't slip
        // a DELETE past the runtime.
        final RelationalDbDataSource s = source("src-1");
        s.setSqlQuery("DELETE FROM documents");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(s));
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));

        final BackgroundJob job = service.start("src-1", "b-1", 2, "alice@x.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
    }

    @Test
    void startPersistsPendingJobOnSuccess() {
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(source("src-1")));
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));

        final BackgroundJob job = service.start("src-1", "b-1", 3, "alice@x.com");

        assertEquals(BackgroundJob.STATUS_PENDING, job.getStatus());
        assertEquals(BackgroundJob.TYPE_RDB_INGEST, job.getType());
        assertEquals(3, job.getPriority());
        assertEquals("alice@x.com", job.getCreatedBy());
        assertNotNull(job.getCreatedAt());
        verify(jobRepository).save(any(BackgroundJob.class));
    }

    // ---------- run(): row → Document mapping ----------

    @Test
    void runEnqueuesOneDocumentPerRowUsingFilenameColumn() throws SQLException {
        final RelationalDbDataSource src = source("src-1");
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        final ResultSet rs = resultSetWith(
                new String[] {"text", "filename"},
                new String[][] {
                        {"alpha body", "a.txt"},
                        {"bravo body", "b.txt"}
                });
        when(stubStatement.executeQuery()).thenReturn(rs);

        // Job pre-saved in PENDING state — that's what run() reads on entry.
        final BackgroundJob pending = pendingJob("job-1", src, batch);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pending));

        service.run("job-1");

        final ArgumentCaptor<String> filenames = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(ingestQueueService, org.mockito.Mockito.times(2))
                .enqueueText(eq(batch), filenames.capture(), bodies.capture(), anyInt());
        assertEquals(java.util.List.of("a.txt", "b.txt"), filenames.getAllValues());
        assertEquals(java.util.List.of("alpha body", "bravo body"), bodies.getAllValues());

        // Each saved Document gets RDB attribution stamped on.
        final ArgumentCaptor<Document> docs = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, org.mockito.Mockito.atLeast(2)).save(docs.capture());
        final Document first = docs.getAllValues().get(0);
        assertEquals("RDB", first.getSourceSystem());
        assertEquals("src-1", first.getSourceIndex(),
                "sourceIndex should be the data source id so two RDB sources don't collide on filename");
        assertEquals("a.txt", first.getSourceDocId());
    }

    @Test
    void runSynthesizesFilenameWhenNoFilenameColumnPresent() throws SQLException {
        final RelationalDbDataSource src = source("src-1");
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        // No filename column — only the text column. The service should fall back to row-N.txt.
        final ResultSet rs = resultSetWith(
                new String[] {"text"},
                new String[][] {
                        {"alpha body"},
                        {"bravo body"}
                });
        when(stubStatement.executeQuery()).thenReturn(rs);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pendingJob("job-1", src, batch)));

        service.run("job-1");

        final ArgumentCaptor<String> filenames = ArgumentCaptor.forClass(String.class);
        verify(ingestQueueService, org.mockito.Mockito.times(2))
                .enqueueText(eq(batch), filenames.capture(), anyString(), anyInt());
        assertEquals(java.util.List.of("row-1.txt", "row-2.txt"), filenames.getAllValues());
    }

    @Test
    void runSkipsDuplicateRowsByExistingSourceIndexAndDocId() throws SQLException {
        final RelationalDbDataSource src = source("src-1");
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        final ResultSet rs = resultSetWith(
                new String[] {"text", "filename"},
                new String[][] {
                        {"alpha body", "a.txt"},   // already imported
                        {"bravo body", "b.txt"}    // new
                });
        when(stubStatement.executeQuery()).thenReturn(rs);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pendingJob("job-1", src, batch)));
        when(documentRepository.existsBySourceIndexAndSourceDocId("src-1", "a.txt"))
                .thenReturn(true);

        service.run("job-1");

        // The duplicate must NOT call enqueueText — the SKIPPED placeholder is persisted directly.
        verify(ingestQueueService, never()).enqueueText(any(), eq("a.txt"), anyString(), anyInt());
        verify(ingestQueueService).enqueueText(any(), eq("b.txt"), eq("bravo body"), anyInt());
        verify(importLogService).skipped(eq("job-1"), eq("a.txt"), eq("a.txt"));
    }

    @Test
    void runMarksRowFailedWhenFirstColumnIsNull() throws SQLException {
        final RelationalDbDataSource src = source("src-1");
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        final ResultSet rs = resultSetWith(
                new String[] {"text", "filename"},
                new String[][] {
                        {null, "a.txt"},
                        {"bravo body", "b.txt"}
                });
        when(stubStatement.executeQuery()).thenReturn(rs);
        final BackgroundJob pending = pendingJob("job-1", src, batch);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pending));

        service.run("job-1");

        // a.txt: failed (null text); b.txt: enqueued.
        verify(ingestQueueService, never()).enqueueText(any(), eq("a.txt"), anyString(), anyInt());
        verify(ingestQueueService).enqueueText(any(), eq("b.txt"), eq("bravo body"), anyInt());
        verify(importLogService).failed(eq("job-1"), eq("a.txt"), eq("a.txt"), anyString());
    }

    @Test
    void runFailsJobWhenSqlExecutionRaises() throws SQLException {
        final RelationalDbDataSource src = source("src-1");
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        when(stubStatement.executeQuery())
                .thenThrow(new SQLException("relation \"documents\" does not exist", "42P01"));
        final BackgroundJob pending = pendingJob("job-1", src, batch);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pending));

        service.run("job-1");

        assertEquals(BackgroundJob.STATUS_FAILED, pending.getStatus());
        assertNotNull(pending.getErrorMessage());
        assertTrue(pending.getErrorMessage().contains("42P01"),
                "SQLSTATE should surface in the operator-visible error: " + pending.getErrorMessage());
    }

    @Test
    void runFailsWhenSavedSqlBecomesDangerous() throws SQLException {
        // Defense in depth at run() time too: a hand-edited Mongo row that put
        // DELETE into the saved SQL must be refused before the connection is even
        // opened. The factory must not be touched.
        final RelationalDbDataSource src = source("src-1");
        src.setSqlQuery("DELETE FROM documents");
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        final BackgroundJob pending = pendingJob("job-1", src, batch);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pending));

        // Use a factory that throws if it's called — proves run() short-circuits
        // before opening a connection.
        service = new RdbIngestJobService(jobRepository, dataSourceRepository, batchRepository,
                documentRepository, ingestQueueService, cipher, inboxService,
                auditLogService, importLogService,
                (url, user, pass) -> {
                    throw new AssertionError("Connection must not be opened for dangerous SQL.");
                }, new JdbcUrlValidator(new DataSourceHostAllowList("")));
        service.run("job-1");

        assertEquals(BackgroundJob.STATUS_FAILED, pending.getStatus());
        verify(ingestQueueService, never()).enqueueText(any(), anyString(), anyString(), anyInt());
    }

    @Test
    void runRevalidatesTheJdbcUrlBeforeConnecting() {
        // #73: a URL that now fails the check, such as one whose host resolves to a private address, is refused at
        // run time, before a connection is opened.
        for (String url : java.util.List.of("jdbc:postgresql://10.0.0.5:5432/db",
                "jdbc:postgresql://db.example.com:5432/db?PGHOST=10.0.0.5")) {
            final RelationalDbDataSource src = source("src-1");
            src.setEncryptedJdbcUrl("enc:" + url);
            final Batch batch = batch("b-1");
            primeForRun(src, batch);
            final BackgroundJob pending = pendingJob("job-1", src, batch);
            when(jobRepository.findById("job-1")).thenReturn(Optional.of(pending));
            service = new RdbIngestJobService(jobRepository, dataSourceRepository, batchRepository,
                    documentRepository, ingestQueueService, cipher, inboxService,
                    auditLogService, importLogService,
                    (u, user, pass) -> {
                        throw new AssertionError("Connection must not be opened for a refused URL.");
                    }, new JdbcUrlValidator(new DataSourceHostAllowList("")));

            service.run("job-1");

            assertEquals(BackgroundJob.STATUS_FAILED, pending.getStatus(), url);
        }
    }

    // ---------- helpers ----------

    private void primeForRun(final RelationalDbDataSource src, final Batch batch) {
        when(dataSourceRepository.findById(src.getId())).thenReturn(Optional.of(src));
        when(batchRepository.findById(batch.getId())).thenReturn(Optional.of(batch));
    }

    private static RelationalDbDataSource source(final String id) {
        final RelationalDbDataSource s = new RelationalDbDataSource();
        s.setId(id);
        s.setName("warehouse");
        // Store the JDBC URL as if it had already been encrypted by the controller.
        // The cipher mock in setUp() decrypts "enc:<plain>" back to "<plain>", so the
        // ingest service's connectionFactory.open call sees the expected plaintext URL.
        s.setEncryptedJdbcUrl("enc:jdbc:postgresql://host:5432/db");
        s.setSqlQuery("SELECT text, filename FROM documents");
        return s;
    }

    private static Batch batch(final String id) {
        final Batch b = new Batch();
        b.setId(id);
        b.setName("Q4 docs");
        return b;
    }

    private static BackgroundJob pendingJob(final String id, final RelationalDbDataSource src,
                                            final Batch batch) {
        final BackgroundJob j = new BackgroundJob();
        j.setId(id);
        j.setType(BackgroundJob.TYPE_RDB_INGEST);
        j.setSourceId(src.getId());
        j.setSourceName(src.getName());
        j.setBatchId(batch.getId());
        j.setBatchName(batch.getName());
        j.setStatus(BackgroundJob.STATUS_PENDING);
        j.setPriority(2);
        j.setCreatedBy("alice@x.com");
        return j;
    }

    // ---------- watermark (:lastKey substitution + advance) ----------

    @Test
    void firstRunSubstitutesNullForLastKeyAndDoesNotBindParameter() throws SQLException {
        // A source with watermark enabled but no stored value yet. The
        // canonical COALESCE(:lastKey, 0) pattern needs the worker to put a
        // SQL literal NULL into the query, NOT a bound parameter (binding a
        // null String would still require setString and break drivers that
        // can't type-coerce a null bind).
        final RelationalDbDataSource src = source("src-1");
        src.setSqlQuery("SELECT body, id AS _wm FROM documents "
                + "WHERE id > COALESCE(:lastKey::bigint, 0) ORDER BY id");
        src.setWatermarkColumn("_wm");
        src.setLastImportedKey(null);
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        final ResultSet __rs = resultSetWith(new String[]{"body", "_wm"}, new String[][]{});
        when(stubStatement.executeQuery()).thenReturn(__rs);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pendingJob("job-1", src, batch)));

        service.run("job-1");

        // The placeholder is replaced by the literal NULL before prepare().
        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(stubConnection).prepareStatement(sql.capture(), anyInt(), anyInt());
        assertTrue(sql.getValue().contains("COALESCE(NULL::bigint, 0)"),
                "first-run SQL must inline NULL for :lastKey, got: " + sql.getValue());
        // ...and no parameter is bound because there's no placeholder left.
        verify(stubStatement, never()).setString(anyInt(), anyString());
    }

    @Test
    void subsequentRunBindsStoredKeyAsParameter() throws SQLException {
        // Source has run before. The placeholder becomes "?" and the stored
        // value is bound. Verify the SQL and the bound argument.
        final RelationalDbDataSource src = source("src-1");
        src.setSqlQuery("SELECT body, id AS _wm FROM documents "
                + "WHERE id > :lastKey ORDER BY id");
        src.setWatermarkColumn("_wm");
        src.setLastImportedKey("12345");
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        final ResultSet __rs = resultSetWith(new String[]{"body", "_wm"}, new String[][]{});
        when(stubStatement.executeQuery()).thenReturn(__rs);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pendingJob("job-1", src, batch)));

        service.run("job-1");

        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(stubConnection).prepareStatement(sql.capture(), anyInt(), anyInt());
        assertTrue(sql.getValue().contains("id > ?"),
                "stored-key SQL must use a bind parameter, got: " + sql.getValue());
        verify(stubStatement).setString(eq(1), eq("12345"));
    }

    @Test
    void sourceWithoutLastKeyPlaceholderRunsUnchanged() throws SQLException {
        // Backwards compatibility: a source whose SQL has no :lastKey at all
        // must continue to behave exactly as before — no substitution, no
        // bind, full-scan ingest.
        final RelationalDbDataSource src = source("src-1");
        src.setSqlQuery("SELECT body FROM documents WHERE imported_at IS NULL");
        src.setWatermarkColumn(null);
        src.setLastImportedKey(null);
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        final ResultSet __rs = resultSetWith(new String[]{"body"}, new String[][]{});
        when(stubStatement.executeQuery()).thenReturn(__rs);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pendingJob("job-1", src, batch)));

        service.run("job-1");

        verify(stubConnection).prepareStatement(eq(src.getSqlQuery()), anyInt(), anyInt());
        verify(stubStatement, never()).setString(anyInt(), anyString());
    }

    @Test
    void successfulRunAdvancesWatermarkToLastSeenValue() throws SQLException {
        final RelationalDbDataSource src = source("src-1");
        src.setSqlQuery("SELECT body, id AS _wm FROM documents "
                + "WHERE id > COALESCE(:lastKey::bigint, 0) ORDER BY id");
        src.setWatermarkColumn("_wm");
        src.setLastImportedKey("100");
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        // Three rows; the watermark column holds 101, 102, 103. Because the
        // admin's SQL orders by it, "last-seen" equals "max-seen".
        final ResultSet __rs = resultSetWith(
                new String[]{"body", "_wm"},
                new String[][]{
                        {"row 101", "101"},
                        {"row 102", "102"},
                        {"row 103", "103"}
                });
        when(stubStatement.executeQuery()).thenReturn(__rs);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pendingJob("job-1", src, batch)));

        service.run("job-1");

        final ArgumentCaptor<RelationalDbDataSource> saved =
                ArgumentCaptor.forClass(RelationalDbDataSource.class);
        verify(dataSourceRepository).save(saved.capture());
        assertEquals("103", saved.getValue().getLastImportedKey(),
                "watermark must advance to the last-seen value after a successful run");
        assertNotNull(saved.getValue().getLastImportedAt(),
                "lastImportedAt must be stamped when the watermark advances");
        verify(auditLogService).log(eq("RDB_WATERMARK_ADVANCE"),
                eq("RelationalDbDataSource"), eq("src-1"), any());
    }

    @Test
    void watermarkDoesNotAdvanceWhenNoColumnIsConfigured() throws SQLException {
        final RelationalDbDataSource src = source("src-1");
        src.setSqlQuery("SELECT body FROM documents");
        src.setWatermarkColumn(null);                // not configured
        src.setLastImportedKey(null);
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        final ResultSet __rs = resultSetWith(
                new String[]{"body"},
                new String[][]{{"row 1"}});
        when(stubStatement.executeQuery()).thenReturn(__rs);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pendingJob("job-1", src, batch)));

        service.run("job-1");

        // The data source is never saved on the watermark path — the source
        // only gets persisted again if the watermark column is configured AND
        // a non-null value was seen AND it's different from the stored one.
        verify(dataSourceRepository, never()).save(any(RelationalDbDataSource.class));
        verify(auditLogService, never()).log(eq("RDB_WATERMARK_ADVANCE"),
                anyString(), anyString(), any());
    }

    @Test
    void watermarkDoesNotAdvanceWhenSqlExceptionOccurs() throws SQLException {
        // The advance only runs after processRows() returns normally, which
        // doesn't happen if executeQuery throws. The stored watermark must
        // be untouched so the next run picks up where this one started.
        final RelationalDbDataSource src = source("src-1");
        src.setSqlQuery("SELECT body, id AS _wm FROM documents WHERE id > :lastKey ORDER BY id");
        src.setWatermarkColumn("_wm");
        src.setLastImportedKey("50");
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        when(stubStatement.executeQuery())
                .thenThrow(new SQLException("connection reset"));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pendingJob("job-1", src, batch)));

        service.run("job-1");

        verify(dataSourceRepository, never()).save(any(RelationalDbDataSource.class));
        verify(auditLogService, never()).log(eq("RDB_WATERMARK_ADVANCE"),
                anyString(), anyString(), any());
    }

    @Test
    void watermarkDoesNotMoveWhenLastSeenEqualsCurrentValue() throws SQLException {
        // No-op advance: if the cursor returned no new rows, the watermark
        // column never produces a value, and the source stays untouched.
        // (This is the common "no new rows since last run" steady state for
        // a scheduled incremental ingest.)
        final RelationalDbDataSource src = source("src-1");
        src.setSqlQuery("SELECT body, id AS _wm FROM documents WHERE id > :lastKey ORDER BY id");
        src.setWatermarkColumn("_wm");
        src.setLastImportedKey("999");
        final Batch batch = batch("b-1");
        primeForRun(src, batch);

        final ResultSet __rs = resultSetWith(
                new String[]{"body", "_wm"}, new String[][]{});
        when(stubStatement.executeQuery()).thenReturn(__rs);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pendingJob("job-1", src, batch)));

        service.run("job-1");

        verify(dataSourceRepository, never()).save(any(RelationalDbDataSource.class));
    }

    /**
     * Stand up a Mockito-backed {@link ResultSet} that walks the supplied rows. Each
     * row is an array of column values; columns are positional (1-based) and the
     * label list also drives {@link ResultSetMetaData} for case-insensitive
     * column lookup.
     */
    private static ResultSet resultSetWith(final String[] columns, final String[][] rows)
            throws SQLException {
        final ResultSet rs = mock(ResultSet.class);
        final ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(columns.length);
        for (int i = 0; i < columns.length; i++) {
            when(md.getColumnLabel(i + 1)).thenReturn(columns[i]);
        }
        when(rs.getMetaData()).thenReturn(md);

        // next(): true N times, then false. We pair each next() with a side effect
        // that loads the row's values into the getString(i) stubs.
        final int[] cursor = {0};
        when(rs.next()).thenAnswer(inv -> {
            if (cursor[0] >= rows.length) return false;
            cursor[0]++;
            return true;
        });
        for (int colIdx = 0; colIdx < columns.length; colIdx++) {
            final int col1Based = colIdx + 1;
            lenient().when(rs.getString(col1Based)).thenAnswer(inv -> {
                final int row = cursor[0] - 1;
                if (row < 0 || row >= rows.length) return null;
                return rows[row][col1Based - 1];
            });
        }
        return rs;
    }
}
