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
import ai.philterd.workspace.model.LocalDirectoryDataSource;
import ai.philterd.workspace.repository.BackgroundJobRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.LocalDirectoryDataSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalDirectoryIngestJobServiceTest {

    private BackgroundJobRepository jobRepository;
    private LocalDirectoryDataSourceRepository dataSourceRepository;
    private BatchRepository batchRepository;
    private DocumentRepository documentRepository;
    private IngestQueueService ingestQueueService;
    private InboxService inboxService;
    private LocalDirectoryIngestJobService service;

    private final GeneralSettingsService generalSettingsService = mock(GeneralSettingsService.class);

    @BeforeEach
    void setUp() {
        jobRepository = mock(BackgroundJobRepository.class);
        when(generalSettingsService.load()).thenReturn(new ai.philterd.workspace.model.GeneralSettings());
        dataSourceRepository = mock(LocalDirectoryDataSourceRepository.class);
        batchRepository = mock(BatchRepository.class);
        documentRepository = mock(DocumentRepository.class);
        ingestQueueService = mock(IngestQueueService.class);
        inboxService = mock(InboxService.class);
        // Make save() return its argument so the worker can chain saves.
        when(jobRepository.save(any(BackgroundJob.class))).thenAnswer(inv -> inv.getArgument(0));
        // enqueueText / enqueueFile build a Document; mirror that for assertions.
        when(ingestQueueService.enqueueText(any(Batch.class), anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> {
                    final Document d = new Document();
                    d.setId("doc-" + inv.<String>getArgument(1));
                    d.setBatchId(inv.<Batch>getArgument(0).getId());
                    d.setFilename(inv.getArgument(1));
                    d.setOriginalText(inv.getArgument(2));
                    return d;
                });
        when(ingestQueueService.enqueueFile(any(Batch.class), anyString(), any(byte[].class),
                anyString(), anyInt(), any()))
                .thenAnswer(inv -> {
                    final Document d = new Document();
                    d.setId("doc-" + inv.<String>getArgument(1));
                    d.setBatchId(inv.<Batch>getArgument(0).getId());
                    d.setFilename(inv.getArgument(1));
                    return d;
                });
        service = new LocalDirectoryIngestJobService(jobRepository, dataSourceRepository,
                batchRepository, documentRepository, ingestQueueService, inboxService,
                mock(AuditLogService.class), mock(DataImportLogService.class),
                new LocalDirectoryRoots(System.getProperty("java.io.tmpdir")), generalSettingsService);
    }

    private static LocalDirectoryDataSource source(final String id, final Path dir, final String glob) {
        final LocalDirectoryDataSource s = new LocalDirectoryDataSource();
        s.setId(id);
        s.setName("Demo Local");
        s.setDirectoryPath(dir.toString());
        s.setFilenameGlob(glob);
        return s;
    }

    private static Batch batch(final String id) {
        final Batch b = new Batch();
        b.setId(id);
        b.setName("Sample");
        return b;
    }

    // ---------- start() ----------

    @Test
    void startMissingSourceReturnsFailedJobImmediately() {
        when(dataSourceRepository.findById("ghost")).thenReturn(Optional.empty());

        final BackgroundJob job = service.start("ghost", "b1", 2, "actor@example.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertEquals(BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST, job.getType());
        assertEquals("Local directory data source not found.", job.getErrorMessage());
        assertNotNull(job.getStartedAt());
        assertNotNull(job.getFinishedAt());
        verify(inboxService).sendByEmail(eq("actor@example.com"), anyString());
    }

    @Test
    void startMissingBatchReturnsFailedJobImmediately(@TempDir final Path dir) throws Exception {
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(source("src-1", dir, "*.txt")));
        when(batchRepository.findById("ghost")).thenReturn(Optional.empty());

        final BackgroundJob job = service.start("src-1", "ghost", 2, "actor@example.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertEquals("Batch not found.", job.getErrorMessage());
    }

    @Test
    void startMissingDirectoryReturnsFailedJobImmediately() {
        final LocalDirectoryDataSource s = new LocalDirectoryDataSource();
        s.setId("src-1");
        s.setName("Demo");
        s.setDirectoryPath("/this/path/does/not/exist/nope");
        s.setFilenameGlob("*.txt");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(s));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        final BackgroundJob job = service.start("src-1", "b1", 2, "actor@example.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertTrue(job.getErrorMessage().contains("does not exist"),
                "expected missing-directory error, got: " + job.getErrorMessage());
    }

    @Test
    void startNonDirectoryPathReturnsFailedJobImmediately(@TempDir final Path dir) throws Exception {
        // Point the data source at a regular file (not a directory) — should fail up front.
        final Path file = Files.writeString(dir.resolve("not-a-dir.txt"), "hello");
        final LocalDirectoryDataSource s = new LocalDirectoryDataSource();
        s.setId("src-1");
        s.setName("Demo");
        s.setDirectoryPath(file.toString());
        s.setFilenameGlob("*.txt");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(s));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        final BackgroundJob job = service.start("src-1", "b1", 2, "actor@example.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertTrue(job.getErrorMessage().contains("not a directory"),
                "expected not-a-directory error, got: " + job.getErrorMessage());
    }

    @Test
    void startHappyPathPersistsPendingJob(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(source("src-1", dir, "*.txt")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        final BackgroundJob job = service.start("src-1", "b1", 2, "actor@example.com");

        assertEquals(BackgroundJob.STATUS_PENDING, job.getStatus());
        assertEquals(BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST, job.getType());
        assertEquals("b1", job.getBatchId());
        assertEquals("actor@example.com", job.getCreatedBy());
    }

    // ---------- run() ----------

    /** A service whose only allowed root is {@code root}, unlike setUp's, which allows the whole temp folder. */
    private LocalDirectoryIngestJobService confinedTo(final Path root) {
        return new LocalDirectoryIngestJobService(jobRepository, dataSourceRepository,
                batchRepository, documentRepository, ingestQueueService, inboxService,
                mock(AuditLogService.class), mock(DataImportLogService.class),
                new LocalDirectoryRoots(root.toString()), generalSettingsService);
    }

    @Test
    void aSymbolicLinkOutOfTheRootAndAFileOverTheUploadLimitAreSkipped(@TempDir final Path tmp) throws Exception {
        // #72: files are read through their real path, which must stay under the root, and no larger than an upload.
        final Path root = Files.createDirectory(tmp.resolve("root"));
        final Path secret = Files.writeString(Files.createDirectory(tmp.resolve("secrets")).resolve("key.txt"),
                "outside");
        Files.writeString(root.resolve("ok.txt"), "inside", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("big.txt"), "x".repeat(2_000), StandardCharsets.UTF_8);
        Files.createSymbolicLink(root.resolve("leak.txt"), secret);
        final ai.philterd.workspace.model.GeneralSettings settings = new ai.philterd.workspace.model.GeneralSettings();
        settings.setMaxUploadFileSizeBytes(1_000);
        when(generalSettingsService.load()).thenReturn(settings);
        final BackgroundJob pending = pendingJob("j1", "src-1", "b1");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(pending));
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(source("src-1", root, "*.txt")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        confinedTo(root).run("j1");

        verify(ingestQueueService).enqueueText(any(Batch.class), eq("ok.txt"), eq("inside"), anyInt(), any());
        verify(ingestQueueService, never()).enqueueText(any(Batch.class), eq("leak.txt"), anyString(), anyInt(), any());
        verify(ingestQueueService, never()).enqueueText(any(Batch.class), eq("big.txt"), anyString(), anyInt(), any());
        assertEquals(2, pending.getFailedDocuments());
        assertTrue(pending.getFailureMessages().stream().anyMatch(m -> m.contains("Outside the allowed root")));
        assertTrue(pending.getFailureMessages().stream().anyMatch(m -> m.contains("upload limit")));
    }

    @Test
    void aDirectoryOutsideTheAllowedRootsIsRefused(@TempDir final Path tmp) throws Exception {
        final Path root = Files.createDirectory(tmp.resolve("root"));
        final Path outside = Files.createDirectory(tmp.resolve("etc"));
        Files.writeString(outside.resolve("passwd.txt"), "root:x:0:0");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(source("src-1", outside, "*.txt")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        final BackgroundJob job = confinedTo(root).start("src-1", "b1", 2, "lead@example.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertEquals(LocalDirectoryRoots.NOT_ALLOWED, job.getErrorMessage());
        verify(ingestQueueService, never()).enqueueText(any(Batch.class), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void runEnqueuesEachMatchingFileWithSourceAttribution(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.txt"), "world", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("c.csv"), "ignored,row");

        final BackgroundJob pending = pendingJob("j1", "src-1", "b1");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(pending));
        when(dataSourceRepository.findById("src-1"))
                .thenReturn(Optional.of(source("src-1", dir, "*.txt")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        service.run("j1");

        // Two .txt files enqueued as text; the .csv is ignored by the glob.
        verify(ingestQueueService, times(2))
                .enqueueText(any(Batch.class), anyString(), anyString(), eq(2), any());
        verify(ingestQueueService, never())
                .enqueueFile(any(Batch.class), anyString(), any(byte[].class), anyString(), anyInt(), any());

        // Each saved Document carries source attribution pointing back at the file.
        final ArgumentCaptor<Document> savedDocs = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, times(2)).save(savedDocs.capture());
        for (Document d : savedDocs.getAllValues()) {
            assertEquals(LocalDirectoryIngestJobService.SOURCE_SYSTEM, d.getSourceSystem());
            assertEquals(dir.toAbsolutePath().normalize().toString(), d.getSourceIndex());
            assertNotNull(d.getSourceDocId());
            assertNotNull(d.getImportedAt());
        }

        // Job ends COMPLETED with totalDocuments and processedDocuments matching the count.
        final ArgumentCaptor<BackgroundJob> savedJob = ArgumentCaptor.forClass(BackgroundJob.class);
        verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(savedJob.capture());
        final BackgroundJob terminal = savedJob.getAllValues().get(savedJob.getAllValues().size() - 1);
        assertEquals(BackgroundJob.STATUS_COMPLETED, terminal.getStatus());
        assertEquals(2, terminal.getProcessedDocuments());
        assertEquals(2, terminal.getTotalDocuments());
    }

    @Test
    void runEnqueuesPdfFilesAsBinaryUploads(@TempDir final Path dir) throws Exception {
        Files.write(dir.resolve("invoice.pdf"), new byte[]{1, 2, 3, 4, 5});

        final BackgroundJob pending = pendingJob("j1", "src-1", "b1");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(pending));
        when(dataSourceRepository.findById("src-1"))
                .thenReturn(Optional.of(source("src-1", dir, "*.pdf")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        service.run("j1");

        verify(ingestQueueService).enqueueFile(any(Batch.class), eq("invoice.pdf"),
                any(byte[].class), eq("application/pdf"), eq(2), any());
        verify(ingestQueueService, never())
                .enqueueText(any(Batch.class), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void runRecursiveGlobWalksSubdirectories(@TempDir final Path dir) throws Exception {
        // The Java glob `**.pdf` matches PDFs at any depth — including the top level —
        // because `**` greedily spans path separators. (`**/*.pdf` would skip top-level
        // files since it requires at least one slash; this test exercises the recursive
        // walk with the glob users would actually configure to "find all PDFs.")
        final Path nested = Files.createDirectories(dir.resolve("nested/deep"));
        Files.writeString(dir.resolve("top.pdf"), "");
        Files.writeString(nested.resolve("inner.pdf"), "");
        // A non-matching file should be ignored.
        Files.writeString(nested.resolve("other.txt"), "");

        final BackgroundJob pending = pendingJob("j1", "src-1", "b1");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(pending));
        when(dataSourceRepository.findById("src-1"))
                .thenReturn(Optional.of(source("src-1", dir, "**.pdf")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        service.run("j1");

        verify(ingestQueueService, times(2))
                .enqueueFile(any(Batch.class), anyString(), any(byte[].class), anyString(), anyInt(), any());
    }

    @Test
    void runTopLevelGlobIgnoresNestedFiles(@TempDir final Path dir) throws Exception {
        // Companion test: `*.pdf` matches only files directly in the configured dir, so
        // a nested PDF must be left alone. This guards against accidentally widening the
        // glob in the future (e.g. matching against the absolute path instead of the
        // path relative to the configured root).
        final Path nested = Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("top.pdf"), "");
        Files.writeString(nested.resolve("inner.pdf"), "");

        final BackgroundJob pending = pendingJob("j1", "src-1", "b1");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(pending));
        when(dataSourceRepository.findById("src-1"))
                .thenReturn(Optional.of(source("src-1", dir, "*.pdf")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        service.run("j1");

        verify(ingestQueueService, times(1))
                .enqueueFile(any(Batch.class), eq("top.pdf"), any(byte[].class), anyString(), anyInt(), any());
    }

    @Test
    void runDedupesFilesAlreadyImportedFromSameDirectory(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello");
        Files.writeString(dir.resolve("b.txt"), "world");

        final String sourceIndex = dir.toAbsolutePath().normalize().toString();
        // a.txt is already in the database from a previous import; b.txt is fresh.
        when(documentRepository.existsBySourceIndexAndSourceDocId(sourceIndex, "a.txt")).thenReturn(true);
        when(documentRepository.existsBySourceIndexAndSourceDocId(sourceIndex, "b.txt")).thenReturn(false);

        final BackgroundJob pending = pendingJob("j1", "src-1", "b1");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(pending));
        when(dataSourceRepository.findById("src-1"))
                .thenReturn(Optional.of(source("src-1", dir, "*.txt")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        service.run("j1");

        // Only b.txt is enqueued; a.txt's content is not re-imported.
        verify(ingestQueueService, times(1))
                .enqueueText(any(Batch.class), eq("b.txt"), anyString(), anyInt(), any());
        verify(ingestQueueService, never())
                .enqueueText(any(Batch.class), eq("a.txt"), anyString(), anyInt(), any());

        final ArgumentCaptor<BackgroundJob> savedJob = ArgumentCaptor.forClass(BackgroundJob.class);
        verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(savedJob.capture());
        final BackgroundJob terminal = savedJob.getAllValues().get(savedJob.getAllValues().size() - 1);
        assertEquals(BackgroundJob.STATUS_COMPLETED, terminal.getStatus());
        assertEquals(1, terminal.getProcessedDocuments());
        assertEquals(1, terminal.getSkippedDocuments());
    }

    @Test
    void runMissingDirectoryAtRunTimeFailsTheJob(@TempDir final Path dir) throws Exception {
        // start() sees the directory; we delete it before run() can iterate. The dispatcher
        // can race with operator filesystem changes, so this path must produce a clean
        // failure rather than a stack trace.
        final BackgroundJob pending = pendingJob("j1", "src-1", "b1");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(pending));
        // Point the source at a path that no longer exists.
        final LocalDirectoryDataSource s = source("src-1", dir.resolve("gone"), "*.txt");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(s));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        service.run("j1");

        final ArgumentCaptor<BackgroundJob> saved = ArgumentCaptor.forClass(BackgroundJob.class);
        verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        final BackgroundJob terminal = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertEquals(BackgroundJob.STATUS_FAILED, terminal.getStatus());
        assertTrue(terminal.getErrorMessage().contains("does not exist"),
                "expected missing-directory error, got: " + terminal.getErrorMessage());
    }

    @Test
    void runUnparseableGlobFailsTheJob(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello");

        final BackgroundJob pending = pendingJob("j1", "src-1", "b1");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(pending));
        when(dataSourceRepository.findById("src-1"))
                .thenReturn(Optional.of(source("src-1", dir, "")));   // blank glob
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        service.run("j1");

        final ArgumentCaptor<BackgroundJob> saved = ArgumentCaptor.forClass(BackgroundJob.class);
        verify(jobRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        final BackgroundJob terminal = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertEquals(BackgroundJob.STATUS_FAILED, terminal.getStatus());
        assertTrue(terminal.getErrorMessage().contains("Filename glob"),
                "expected glob error, got: " + terminal.getErrorMessage());
    }

    private static BackgroundJob pendingJob(final String id, final String sourceId, final String batchId) {
        final BackgroundJob j = new BackgroundJob();
        j.setId(id);
        j.setType(BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST);
        j.setSourceId(sourceId);
        j.setBatchId(batchId);
        j.setBatchName("Sample");
        j.setPriority(2);
        j.setStatus(BackgroundJob.STATUS_PENDING);
        j.setCreatedBy("actor@example.com");
        return j;
    }

    // ---------- case metadata sidecars (#16) ----------

    @Test
    void aSidecarGivesItsFileCaseMetadataAndIsNotImportedItself(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("motion.txt"), "Jane Roe moves.", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("motion.txt.case.json"),
                "{\"caseNumber\": \"1:26-cv-01234\", \"caseType\": \"Civil\", \"ifp\": true}",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("order.txt"), "So ordered.", StandardCharsets.UTF_8);
        final BackgroundJob pending = pendingJob("j1", "src-1", "b1");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(pending));
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(source("src-1", dir, "*")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        service.run("j1");

        final ArgumentCaptor<ai.philterd.workspace.model.CaseMetadata> motion =
                ArgumentCaptor.forClass(ai.philterd.workspace.model.CaseMetadata.class);
        verify(ingestQueueService).enqueueText(any(Batch.class), eq("motion.txt"), anyString(), anyInt(),
                motion.capture());
        assertEquals("civil", motion.getValue().getCaseType());
        assertEquals("1:26-cv-01234", motion.getValue().getCaseNumber());
        assertEquals(true, motion.getValue().getIfp());
        verify(ingestQueueService).enqueueText(any(Batch.class), eq("order.txt"), anyString(), anyInt(),
                org.mockito.ArgumentMatchers.isNull());
        verify(ingestQueueService, never()).enqueueText(any(Batch.class), eq("motion.txt.case.json"), anyString(),
                anyInt(), any());
        assertEquals(2, pending.getTotalDocuments(), "the sidecar is not counted as a document");
    }

    @Test
    void aFileWithAnInvalidSidecarIsNotImported(@TempDir final Path dir) throws Exception {
        Files.writeString(dir.resolve("motion.txt"), "Jane Roe moves.", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("motion.txt.case.json"), "{\"feeStatus\": \"maybe\"}", StandardCharsets.UTF_8);
        final BackgroundJob pending = pendingJob("j1", "src-1", "b1");
        when(jobRepository.findById("j1")).thenReturn(Optional.of(pending));
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(source("src-1", dir, "*.txt")));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch("b1")));

        service.run("j1");

        verify(ingestQueueService, never()).enqueueText(any(Batch.class), anyString(), anyString(), anyInt(), any());
        assertEquals(1, pending.getFailedDocuments());
    }
}
