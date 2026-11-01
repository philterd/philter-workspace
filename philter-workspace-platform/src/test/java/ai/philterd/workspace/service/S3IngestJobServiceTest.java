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
import ai.philterd.workspace.model.S3DataSource;
import ai.philterd.workspace.repository.BackgroundJobRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.S3DataSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link S3IngestJobService}. Covers the start-time validation gates,
 * the listing + glob-filter contract, dedupe via {@code (sourceIndex, sourceDocId)},
 * happy-path enqueue, and the source-attribution stamped onto each imported document.
 *
 * The S3 SDK calls go through an in-memory fake bucket — no real AWS or MinIO is started.
 */
class S3IngestJobServiceTest {

    private BackgroundJobRepository jobRepository;
    private S3DataSourceRepository dataSourceRepository;
    private BatchRepository batchRepository;
    private DocumentRepository documentRepository;
    private IngestQueueService ingestQueueService;
    private SymmetricCipher cipher;
    private InboxService inboxService;
    private DataSourceHostAllowList hostAllowList;
    private AuditLogService auditLogService;
    private FakeS3 fakeBucket;
    private S3IngestJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(BackgroundJobRepository.class);
        when(jobRepository.save(any(BackgroundJob.class)))
                .thenAnswer(inv -> inv.getArgument(0, BackgroundJob.class));
        dataSourceRepository = mock(S3DataSourceRepository.class);
        batchRepository = mock(BatchRepository.class);
        documentRepository = mock(DocumentRepository.class);
        when(documentRepository.save(any(Document.class)))
                .thenAnswer(inv -> inv.getArgument(0, Document.class));
        ingestQueueService = mock(IngestQueueService.class);
        cipher = mock(SymmetricCipher.class);
        inboxService = mock(InboxService.class);
        hostAllowList = mock(DataSourceHostAllowList.class);
        when(hostAllowList.isAllowed(anyString())).thenReturn(true);
        auditLogService = mock(AuditLogService.class);
        fakeBucket = new FakeS3();
        service = new S3IngestJobService(jobRepository, dataSourceRepository, batchRepository,
                documentRepository, ingestQueueService, cipher, inboxService, hostAllowList,
                auditLogService, mock(DataImportLogService.class), (src, c) -> fakeBucket.client);
    }

    // ---------- start() validation ----------

    @Test
    void startFailsImmediatelyWhenSourceIsMissing() {
        when(dataSourceRepository.findById("missing")).thenReturn(Optional.empty());
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));

        final BackgroundJob job = service.start("missing", "b-1", 2, "alice@x.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertEquals("S3 data source not found.", job.getErrorMessage());
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
    void startFailsImmediatelyWhenEndpointIsBlockedByAllowList() {
        // An operator-supplied non-AWS endpoint must be on the data-source host allow-list,
        // matching the OpenSearch / Elasticsearch / Philter / Ollama posture. Without this,
        // a malicious admin could point a source at internal infrastructure.
        final S3DataSource s = source("src-1");
        s.setEndpoint("http://internal-only.example.com:9000");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(s));
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));
        when(hostAllowList.isAllowed("http://internal-only.example.com:9000")).thenReturn(false);

        final BackgroundJob job = service.start("src-1", "b-1", 2, "alice@x.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertTrue(job.getErrorMessage().contains("allow-list"),
                "Allow-list rejection should surface an actionable error pointing at the property.");
    }

    @Test
    void startFailsImmediatelyWhenBucketNameIsMissing() {
        final S3DataSource s = source("src-1");
        s.setBucketName(null);
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(s));
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));

        final BackgroundJob job = service.start("src-1", "b-1", 2, "alice@x.com");
        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertTrue(job.getErrorMessage().contains("bucket name"));
    }

    @Test
    void startFailsImmediatelyWhenGlobIsBlank() {
        final S3DataSource s = source("src-1");
        s.setFilenameGlob("   ");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(s));
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));

        final BackgroundJob job = service.start("src-1", "b-1", 2, "alice@x.com");
        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertTrue(job.getErrorMessage().contains("Filename glob"));
    }

    @Test
    void startPersistsPendingJobOnSuccess() {
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(source("src-1")));
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));

        final BackgroundJob job = service.start("src-1", "b-1", 3, "alice@x.com");

        assertEquals(BackgroundJob.STATUS_PENDING, job.getStatus());
        assertEquals(BackgroundJob.TYPE_S3_INGEST, job.getType());
        assertEquals(3, job.getPriority());
        assertEquals("alice@x.com", job.getCreatedBy());
        assertNotNull(job.getCreatedAt());
        verify(jobRepository).save(any(BackgroundJob.class));
    }

    // ---------- run(): the endpoint is checked again (#73) ----------

    @Test
    void runRefusesAnEndpointTheAllowListNoLongerAllows() {
        // Allowed when queued, refused by the time the job runs: the endpoint was edited, or its host now resolves
        // somewhere private.
        final S3DataSource src = source("src-1");
        src.setEndpoint("http://storage.example.com:9000");
        primeRunFor(src);
        when(hostAllowList.isAllowed("http://storage.example.com:9000")).thenReturn(false);
        final BackgroundJob persisted = pendingJob("job-1", src.getId());
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(persisted));

        service.run("job-1");

        assertEquals(BackgroundJob.STATUS_FAILED, persisted.getStatus());
        assertTrue(persisted.getErrorMessage().contains("allow-list"), persisted.getErrorMessage());
        verify(ingestQueueService, never()).enqueueText(any(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void eachRequestToAnEndpointIsCheckedBeforeItIsSent() {
        final S3IngestJobService.AllowListInterceptor interceptor =
                new S3IngestJobService.AllowListInterceptor(new DataSourceHostAllowList(""));
        final software.amazon.awssdk.http.SdkHttpRequest internal = software.amazon.awssdk.http.SdkHttpRequest.builder()
                .uri(java.net.URI.create("http://169.254.169.254/latest/meta-data"))
                .method(software.amazon.awssdk.http.SdkHttpMethod.GET).build();
        final software.amazon.awssdk.core.interceptor.Context.BeforeTransmission context =
                org.mockito.Mockito.mock(software.amazon.awssdk.core.interceptor.Context.BeforeTransmission.class);
        when(context.httpRequest()).thenReturn(internal);

        org.junit.jupiter.api.Assertions.assertThrows(DataSourceHostAllowList.HostNotAllowedException.class,
                () -> interceptor.beforeTransmission(context,
                        new software.amazon.awssdk.core.interceptor.ExecutionAttributes()));
    }

    // ---------- run(): listing + glob filter ----------

    @Test
    void runListsObjectsAppliesGlobFilterAndEnqueuesEachMatch() {
        // Three objects in the bucket; only two match the *.txt glob.
        fakeBucket.put("docs/a.txt", "alpha".getBytes(StandardCharsets.UTF_8));
        fakeBucket.put("docs/b.pdf", new byte[]{1, 2, 3});
        fakeBucket.put("docs/c.txt", "charlie".getBytes(StandardCharsets.UTF_8));

        final S3DataSource src = source("src-1");
        src.setBucketKey("docs/");
        src.setFilenameGlob("*.txt");
        primeRunFor(src);
        when(ingestQueueService.enqueueText(any(), anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> docFromEnqueue(inv.getArgument(1)));

        runNewJob(src);

        // Two text files enqueued; the PDF was filtered out by the *.txt glob.
        verify(ingestQueueService, atLeastOnce()).enqueueText(any(), eq("a.txt"),
                eq("alpha"), anyInt(), any());
        verify(ingestQueueService, atLeastOnce()).enqueueText(any(), eq("c.txt"),
                eq("charlie"), anyInt(), any());
        verify(ingestQueueService, never()).enqueueText(any(), eq("b.pdf"), anyString(), anyInt(), any());
    }

    @Test
    void aSidecarObjectGivesItsDocumentCaseMetadataAndIsNotImportedItself() {
        // S3 lists keys in order, so a document comes just before its sidecar.
        fakeBucket.put("docs/a.txt", "alpha".getBytes(StandardCharsets.UTF_8));
        fakeBucket.put("docs/a.txt.case.json",
                "{\"caseType\": \"criminal\", \"filer\": \"Acme Dental LLC\"}".getBytes(StandardCharsets.UTF_8));
        fakeBucket.put("docs/c.txt", "charlie".getBytes(StandardCharsets.UTF_8));
        final S3DataSource src = source("src-1");
        src.setBucketKey("docs/");
        src.setFilenameGlob("*");
        primeRunFor(src);
        when(ingestQueueService.enqueueText(any(), anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> docFromEnqueue(inv.getArgument(1)));

        runNewJob(src);

        final org.mockito.ArgumentCaptor<ai.philterd.workspace.model.CaseMetadata> meta =
                org.mockito.ArgumentCaptor.forClass(ai.philterd.workspace.model.CaseMetadata.class);
        verify(ingestQueueService).enqueueText(any(), eq("a.txt"), eq("alpha"), anyInt(), meta.capture());
        assertEquals("criminal", meta.getValue().getCaseType());
        assertEquals("Acme Dental LLC", meta.getValue().getFiler());
        verify(ingestQueueService).enqueueText(any(), eq("c.txt"), eq("charlie"), anyInt(),
                org.mockito.ArgumentMatchers.isNull());
        verify(ingestQueueService, never()).enqueueText(any(), eq("a.txt.case.json"), anyString(), anyInt(), any());
    }

    @Test
    void runRoutesPdfObjectsToEnqueueFile() {
        // PDF objects must be enqueued through enqueueFile so the binary path is preserved
        // (rather than UTF-8 decoded into garbage).
        final byte[] pdfBytes = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37};  // "%PDF-1.7"
        fakeBucket.put("docs/report.pdf", pdfBytes);
        final S3DataSource src = source("src-1");
        src.setBucketKey("docs/");
        // *.pdf matches the relative key directly under the prefix. (**/*.pdf would
        // require the file to live in a nested directory under the prefix.)
        src.setFilenameGlob("*.pdf");
        primeRunFor(src);
        when(ingestQueueService.enqueueFile(any(), anyString(), any(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> docFromEnqueue(inv.getArgument(1)));

        runNewJob(src);

        verify(ingestQueueService).enqueueFile(any(), eq("report.pdf"), eq(pdfBytes),
                eq("application/pdf"), anyInt(), any());
        verify(ingestQueueService, never()).enqueueText(any(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void runStampsSourceAttributionOnEachImportedDocument() {
        fakeBucket.put("docs/a.txt", "alpha".getBytes(StandardCharsets.UTF_8));
        final S3DataSource src = source("src-1");
        src.setBucketKey("docs/");
        src.setFilenameGlob("*.txt");
        primeRunFor(src);
        when(ingestQueueService.enqueueText(any(), anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> docFromEnqueue(inv.getArgument(1)));

        runNewJob(src);

        // Capture the document save that happens after enqueue to apply source attribution.
        final ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeastOnce()).save(captor.capture());
        final Document saved = captor.getAllValues().stream()
                .filter(d -> "S3".equals(d.getSourceSystem()))
                .findFirst().orElseThrow();
        assertEquals("S3", saved.getSourceSystem());
        assertEquals("test-bucket", saved.getSourceIndex(),
                "sourceIndex must be the bucket name so dedupe is keyed on (bucket, key).");
        assertEquals("docs/a.txt", saved.getSourceDocId());
        assertNotNull(saved.getImportedAt());
    }

    @Test
    void runSkipsObjectsAlreadyImportedKeyedOnBucketAndKey() {
        fakeBucket.put("docs/a.txt", "alpha".getBytes(StandardCharsets.UTF_8));
        fakeBucket.put("docs/b.txt", "bravo".getBytes(StandardCharsets.UTF_8));
        // a.txt already imported in a prior run.
        when(documentRepository.existsBySourceIndexAndSourceDocId("test-bucket", "docs/a.txt"))
                .thenReturn(true);
        final S3DataSource src = source("src-1");
        src.setBucketKey("docs/");
        src.setFilenameGlob("*.txt");
        primeRunFor(src);
        when(ingestQueueService.enqueueText(any(), anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> docFromEnqueue(inv.getArgument(1)));

        runNewJob(src);

        // Only b.txt got enqueued — a.txt was deduped.
        verify(ingestQueueService, never()).enqueueText(any(), eq("a.txt"), anyString(), anyInt(), any());
        verify(ingestQueueService).enqueueText(any(), eq("b.txt"), eq("bravo"), anyInt(), any());

        // The skipped row was persisted as a SKIPPED placeholder so the audit trail is honest.
        final ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeastOnce()).save(saved.capture());
        assertTrue(saved.getAllValues().stream().anyMatch(d ->
                "SKIPPED".equals(d.getStatus())
                        && "docs/a.txt".equals(d.getSourceDocId())),
                "Re-imported objects must produce a SKIPPED placeholder document.");
    }

    @Test
    void runDoesNotEnqueueObjectsWithKeysEndingInSlash() {
        // Pseudo-folders show up as zero-byte keys ending in "/". They must be skipped
        // (otherwise the enqueue path would try to decode an empty body as text).
        fakeBucket.put("docs/", new byte[0]);
        fakeBucket.put("docs/a.txt", "alpha".getBytes(StandardCharsets.UTF_8));
        final S3DataSource src = source("src-1");
        src.setBucketKey("docs/");
        src.setFilenameGlob("**");
        primeRunFor(src);
        when(ingestQueueService.enqueueText(any(), anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> docFromEnqueue(inv.getArgument(1)));

        runNewJob(src);

        verify(ingestQueueService).enqueueText(any(), eq("a.txt"), eq("alpha"), anyInt(), any());
        verify(ingestQueueService, never()).enqueueText(any(), eq(""), anyString(), anyInt(), any());
    }

    @Test
    void runFailsCleanlyWhenSourceWasDeletedBetweenStartAndDispatch() {
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(pendingJob("job-1", "deleted-src")));
        when(dataSourceRepository.findById("deleted-src")).thenReturn(Optional.empty());
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));

        service.run("job-1");

        // Job is finalised as FAILED with a clear message.
        final ArgumentCaptor<BackgroundJob> saved = ArgumentCaptor.forClass(BackgroundJob.class);
        verify(jobRepository, atLeastOnce()).save(saved.capture());
        final BackgroundJob terminal = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertEquals(BackgroundJob.STATUS_FAILED, terminal.getStatus());
        assertTrue(terminal.getErrorMessage().contains("deleted"));
    }

    // ---------- demo MinIO scenario ----------

    @Test
    void runFindsAllThreeSampleFilesInDemoMinioConfiguration() {
        // Mirrors exactly what DemoDataSourceLoader#ensureMinioDataSource registers and
        // what docker-compose.yaml's minio-init copies into the bucket. If this test
        // passes but the live demo still imports zero files, the running database has
        // a stale S3DataSource row (the loader skips re-creating it) or the MinIO
        // volume holds the pre-fix object layout — fix by `docker compose down -v`
        // then `up` to rebuild both.
        fakeBucket.put("philter-workspace-demo/test.txt", "alpha".getBytes(StandardCharsets.UTF_8));
        fakeBucket.put("philter-workspace-demo/test2.txt", "bravo".getBytes(StandardCharsets.UTF_8));
        fakeBucket.put("philter-workspace-demo/test3.txt", "charlie".getBytes(StandardCharsets.UTF_8));

        final S3DataSource src = new S3DataSource();
        src.setId("demo-minio");
        src.setName("Demo MinIO (S3-compatible)");
        src.setEndpoint("http://minio:9000");
        src.setBucketName("philter-workspace-demo");
        src.setBucketKey("philter-workspace-demo/");
        src.setFilenameGlob("*.txt");
        primeRunFor(src);
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));
        when(ingestQueueService.enqueueText(any(), anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> docFromEnqueue(inv.getArgument(1)));

        runNewJob(src);

        // All three text files must be enqueued — one call per file, with the
        // file's bare name (not the full key) used as the document filename.
        verify(ingestQueueService).enqueueText(any(), eq("test.txt"), eq("alpha"), anyInt(), any());
        verify(ingestQueueService).enqueueText(any(), eq("test2.txt"), eq("bravo"), anyInt(), any());
        verify(ingestQueueService).enqueueText(any(), eq("test3.txt"), eq("charlie"), anyInt(), any());
    }

    @Test
    void matchesGlobAcceptsDemoMinioKeyShape() {
        // The exact shape that DemoDataSourceLoader configures and minio-init seeds:
        // bucketKey "philter-workspace-demo/", glob "*.txt", keys "philter-workspace-demo/test.txt" etc.
        final PathMatcher m = S3IngestJobService.compileGlob("*.txt");
        assertTrue(S3IngestJobService.matchesGlob(m, "philter-workspace-demo/", "philter-workspace-demo/test.txt"),
                "Demo MinIO prefix 'philter-workspace-demo/' + glob '*.txt' must match key "
                        + "'philter-workspace-demo/test.txt' — this is the production setup.");
        assertTrue(S3IngestJobService.matchesGlob(m, "philter-workspace-demo/", "philter-workspace-demo/test2.txt"));
        assertTrue(S3IngestJobService.matchesGlob(m, "philter-workspace-demo/", "philter-workspace-demo/test3.txt"));
    }

    @Test
    void matchesGlobIgnoresPrefixFolderMarkerKey() {
        // MinIO and AWS can return the prefix itself as a zero-byte object marker
        // (key == prefix). The matcher must not treat that as a file match — the
        // collectCandidates loop already filters keys ending in "/", and matchesGlob
        // also returns false when stripping the prefix leaves an empty string.
        final PathMatcher m = S3IngestJobService.compileGlob("*.txt");
        assertEquals(false, S3IngestJobService.matchesGlob(m, "philter-workspace-demo/", "philter-workspace-demo/"),
                "Folder-marker key (key == prefix) must not match.");
    }

    @Test
    void matchesGlobHandlesPrefixWithoutTrailingSlash() {
        // Operators sometimes omit the trailing slash on the prefix. The relative-path
        // calculation must still produce a single-component filename.
        final PathMatcher m = S3IngestJobService.compileGlob("*.txt");
        assertTrue(S3IngestJobService.matchesGlob(m, "philter-workspace-demo", "philter-workspace-demo/test.txt"),
                "Prefix 'philter-workspace-demo' (no trailing slash) must still strip the leading slash "
                        + "from the relative path so '*.txt' matches.");
    }

    // ---------- glob matching ----------

    @Test
    void matchesGlobMatchesRelativePathUnderPrefix() {
        // The glob is matched against the key relative to the configured prefix, mirroring
        // LocalDirectoryIngestJobService's behavior.
        final PathMatcher m = S3IngestJobService.compileGlob("*.txt");
        assertTrue(S3IngestJobService.matchesGlob(m, "docs/", "docs/a.txt"));
        assertTrue(S3IngestJobService.matchesGlob(m, "docs/", "docs/b.txt"));
        assertEquals(false, S3IngestJobService.matchesGlob(m, "docs/", "docs/sub/c.txt"),
                "*.txt is single-level — must not match keys deeper than the prefix.");
    }

    @Test
    void matchesGlobHandlesEmptyPrefix() {
        final PathMatcher m = S3IngestJobService.compileGlob("**/*.pdf");
        assertTrue(S3IngestJobService.matchesGlob(m, "", "any/depth/file.pdf"));
        assertEquals(false, S3IngestJobService.matchesGlob(m, "", "top.pdf"),
                "**/*.pdf requires at least one directory level.");
    }

    @Test
    void compileGlobReturnsNullForBlankInput() {
        assertEquals(null, S3IngestJobService.compileGlob(null));
        assertEquals(null, S3IngestJobService.compileGlob(""));
        assertEquals(null, S3IngestJobService.compileGlob("   "));
    }

    // ---------- helpers ----------

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    /** Stub the per-job lookup chain used by run() so the test can drive a single iteration. */
    private void primeRunFor(final S3DataSource src) {
        when(dataSourceRepository.findById(src.getId())).thenReturn(Optional.of(src));
        when(batchRepository.findById("b-1")).thenReturn(Optional.of(batch("b-1")));
        // Note: we do NOT set a broad default on existsBySourceIndexAndSourceDocId here
        // because individual tests may have already stubbed specific (bucket, key) pairs
        // to return true (the skip-already-imported case) and a broad default would
        // overwrite those. The unstubbed Mockito default of `false` covers the rest.
    }

    /** Build a Document the way IngestQueueService would for a given filename. */
    private static Document docFromEnqueue(final String filename) {
        final Document d = new Document();
        d.setId("doc-" + filename);
        d.setBatchId("b-1");
        d.setFilename(filename);
        d.setStatus("PENDING");
        return d;
    }

    private void runNewJob(final S3DataSource src) {
        final BackgroundJob persisted = pendingJob("job-1", src.getId());
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(persisted));
        service.run("job-1");
    }

    private static S3DataSource source(final String id) {
        final S3DataSource s = new S3DataSource();
        s.setId(id);
        s.setName("test-source");
        s.setBucketName("test-bucket");
        s.setBucketKey("docs/");
        s.setFilenameGlob("*.txt");
        return s;
    }

    private static Batch batch(final String id) {
        final Batch b = new Batch();
        b.setId(id);
        b.setName("test-batch");
        return b;
    }

    private static BackgroundJob pendingJob(final String id, final String sourceId) {
        final BackgroundJob job = new BackgroundJob();
        job.setId(id);
        job.setType(BackgroundJob.TYPE_S3_INGEST);
        job.setSourceId(sourceId);
        job.setBatchId("b-1");
        job.setPriority(2);
        job.setStatus(BackgroundJob.STATUS_PENDING);
        return job;
    }

    /**
     * In-memory fake S3. Stores keys → bytes, answers ListObjectsV2 (no pagination — the
     * test sizes are small) and GetObjectAsBytes against the same map. Only the SDK methods
     * the ingest service actually invokes are stubbed.
     */
    static final class FakeS3 {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        final S3Client client = mock(S3Client.class);

        FakeS3() {
            when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(inv -> {
                final ListObjectsV2Request req = inv.getArgument(0, ListObjectsV2Request.class);
                final String prefix = req.prefix() == null ? "" : req.prefix();
                final List<S3Object> matched = new ArrayList<>();
                for (Map.Entry<String, byte[]> e : objects.entrySet()) {
                    if (e.getKey().startsWith(prefix)) {
                        matched.add(S3Object.builder()
                                .key(e.getKey())
                                .size((long) e.getValue().length)
                                .build());
                    }
                }
                return ListObjectsV2Response.builder()
                        .contents(matched)
                        .isTruncated(false)
                        .build();
            });
            when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(inv -> {
                final GetObjectRequest req = inv.getArgument(0, GetObjectRequest.class);
                final byte[] body = objects.getOrDefault(req.key(), new byte[0]);
                final GetObjectResponse resp = GetObjectResponse.builder()
                        .contentLength((long) body.length)
                        .build();
                return ResponseBytes.fromByteArray(resp, body);
            });
            // close() is a no-op on the mock — Mockito returns void naturally.
        }

        void put(final String key, final byte[] body) {
            objects.put(key, body);
        }
    }
}
