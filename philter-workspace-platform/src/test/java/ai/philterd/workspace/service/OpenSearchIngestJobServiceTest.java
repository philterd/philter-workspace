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
import ai.philterd.workspace.model.OpenSearchDataSource;
import ai.philterd.workspace.repository.BackgroundJobRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.OpenSearchDataSourceRepository;
import ai.philterd.workspace.service.InboxService;
import ai.philterd.workspace.service.SymmetricCipher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenSearchIngestJobServiceTest {

    private BackgroundJobRepository jobRepository;
    private OpenSearchDataSourceRepository dataSourceRepository;
    private BatchRepository batchRepository;
    private DocumentRepository documentRepository;
    private IngestQueueService ingestQueueService;
    private SymmetricCipher cipher;
    private InboxService inboxService;
    private OpenSearchIngestJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(BackgroundJobRepository.class);
        dataSourceRepository = mock(OpenSearchDataSourceRepository.class);
        batchRepository = mock(BatchRepository.class);
        documentRepository = mock(DocumentRepository.class);
        ingestQueueService = mock(IngestQueueService.class);
        cipher = mock(SymmetricCipher.class);
        inboxService = mock(InboxService.class);
        // Make the repo's save() return its argument so the worker thread can chain saves.
        when(jobRepository.save(any(BackgroundJob.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        service = new OpenSearchIngestJobService(jobRepository, dataSourceRepository,
                batchRepository, documentRepository, ingestQueueService, new ObjectMapper(), cipher,
                inboxService, new DataSourceHostAllowList(""), mock(AuditLogService.class),
                mock(DataImportLogService.class));
    }

    // ---------- start() ----------

    @Test
    void startMissingSourceReturnsFailedJobImmediately() {
        when(dataSourceRepository.findById("ghost-src")).thenReturn(Optional.empty());

        final BackgroundJob job = service.start("ghost-src", "b1", 2, "actor@example.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertEquals(BackgroundJob.TYPE_OPENSEARCH_INGEST, job.getType());
        assertEquals("OpenSearch data source not found.", job.getErrorMessage());
        assertNotNull(job.getStartedAt());
        assertNotNull(job.getFinishedAt());
        verify(jobRepository).save(any(BackgroundJob.class));

        // Even synchronous failures notify the user — they may have moved on by the time
        // the click finishes the round trip.
        final ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(inboxService).sendByEmail(eq("actor@example.com"), messageCaptor.capture());
        assertTrue(messageCaptor.getValue().contains("failed"),
                "expected message to flag failure: " + messageCaptor.getValue());
    }

    @Test
    void noActorEmailMeansNoInboxNotification() {
        when(dataSourceRepository.findById("ghost")).thenReturn(Optional.empty());

        service.start("ghost", "b1", 2, "");

        verify(inboxService, org.mockito.Mockito.never()).sendByEmail(any(), any());
    }

    @Test
    void startQueuesPendingEvenWhenAnotherJobIsAlreadyRunning() {
        // Multiple imports can be queued for the same batch — start() persists a PENDING
        // row regardless of whether another job is already running. The DataImportDispatcher
        // promotes them to RUNNING one at a time.
        final OpenSearchDataSource src = new OpenSearchDataSource();
        src.setId("src-1");
        src.setName("Demo");
        // Public host so the empty default allow-list passes it through; the negative-path
        // case is exercised by startRejectsEndpointNotOnAllowList below.
        src.setEndpoint("http://example.com:9200");
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Sample");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(src));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        final BackgroundJob job = service.start("src-1", "b1", 2, "alice@example.com");

        assertEquals(BackgroundJob.STATUS_PENDING, job.getStatus());
        assertEquals(BackgroundJob.TYPE_OPENSEARCH_INGEST, job.getType());
        assertEquals("b1", job.getBatchId());
        assertEquals("alice@example.com", job.getCreatedBy());
        verify(jobRepository, atLeastOnce()).save(any(BackgroundJob.class));
    }

    @Test
    void startMissingBatchReturnsFailedJobImmediately() {
        final OpenSearchDataSource src = new OpenSearchDataSource();
        src.setId("src-1");
        src.setName("Demo");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(src));
        when(batchRepository.findById("ghost-batch")).thenReturn(Optional.empty());

        final BackgroundJob job = service.start("src-1", "ghost-batch", 2, "actor@example.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertEquals("Batch not found.", job.getErrorMessage());
        verify(jobRepository).save(any(BackgroundJob.class));
    }

    @Test
    void startHappyPathPersistsPendingJobWithFullMetadata() {
        final OpenSearchDataSource src = new OpenSearchDataSource();
        src.setId("src-1");
        src.setName("Demo OpenSearch");
        src.setEndpoint("http://example.com:9200");
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Sample");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(src));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        final BackgroundJob job = service.start("src-1", "b1", 3, "alice@example.com");

        assertEquals(BackgroundJob.TYPE_OPENSEARCH_INGEST, job.getType());
        assertEquals("src-1", job.getSourceId());
        assertEquals("Demo OpenSearch", job.getSourceName());
        assertEquals("b1", job.getBatchId());
        assertEquals("Sample", job.getBatchName());
        assertEquals(3, job.getPriority());
        assertEquals("alice@example.com", job.getCreatedBy());
        // Status is at least PENDING when start() returns; the executor may have already
        // begun running and advanced it. Either is acceptable.
        assertTrue(BackgroundJob.STATUS_PENDING.equals(job.getStatus())
                || BackgroundJob.STATUS_RUNNING.equals(job.getStatus())
                || BackgroundJob.STATUS_FAILED.equals(job.getStatus()),
                "unexpected status: " + job.getStatus());
        assertNotNull(job.getCreatedAt());

        // Verify a PENDING save happened (start() persists before submitting to the executor).
        final ArgumentCaptor<BackgroundJob> captor = ArgumentCaptor.forClass(BackgroundJob.class);
        verify(jobRepository, atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                        .anyMatch(j -> BackgroundJob.STATUS_PENDING.equals(j.getStatus())),
                "expected at least one PENDING save");
    }

    @Test
    void runChecksTheEndpointAgainBeforeEachRequest() {
        // #73: allowed when queued is not enough. The endpoint may have been edited since, or its host may now
        // resolve to a private address; the request is refused before it is sent.
        final ai.philterd.workspace.model.OpenSearchDataSource src = new ai.philterd.workspace.model.OpenSearchDataSource();
        src.setId("src-1");
        src.setName("Demo");
        src.setEndpoint("http://169.254.169.254:9200");
        src.setQuery("contracts/_search {}");
        src.setTextField("body");
        final Batch batch = new Batch();
        batch.setId("b1");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(src));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        final BackgroundJob job = new BackgroundJob();
        job.setId("job-1");
        job.setSourceId("src-1");
        job.setBatchId("b1");
        job.setStatus(BackgroundJob.STATUS_PENDING);
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));

        service.run("job-1");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertTrue(job.getErrorMessage().contains("not permitted"), job.getErrorMessage());
    }

    @Test
    void startRejectsEndpointNotOnAllowList() {
        // Build a service with an allow-list that excludes the source's endpoint host.
        final OpenSearchIngestJobService restrictedService = new OpenSearchIngestJobService(
                jobRepository, dataSourceRepository, batchRepository, documentRepository,
                ingestQueueService, new ObjectMapper(), cipher, inboxService,
                new DataSourceHostAllowList("opensearch.internal"), mock(AuditLogService.class),
                mock(DataImportLogService.class));

        final OpenSearchDataSource src = new OpenSearchDataSource();
        src.setId("src-1");
        src.setName("Demo");
        src.setEndpoint("http://attacker.example.com:9200");
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Sample");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(src));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        final BackgroundJob job = restrictedService.start("src-1", "b1", 2, "actor@example.com");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertTrue(job.getErrorMessage() != null
                        && job.getErrorMessage().contains("allow-list"),
                "expected allow-list error, got: " + job.getErrorMessage());
    }

    @Test
    void startNullActorEmailDoesNotNpe() {
        when(dataSourceRepository.findById("ghost")).thenReturn(Optional.empty());

        final BackgroundJob job = service.start("ghost", "b1", 2, null);

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertEquals("", job.getCreatedBy());
    }

    // ---------- withSize() ----------

    @Test
    void withSizeOverridesExistingSize() throws Exception {
        final String body = service.withSize("{\"size\":5000,\"query\":{\"match_all\":{}}}", 100);
        final JsonNode parsed = new ObjectMapper().readTree(body);
        assertEquals(100, parsed.path("size").asInt());
        // The query body is preserved.
        assertTrue(parsed.path("query").path("match_all").isObject());
    }

    @Test
    void withSizeSetsSizeWhenAbsent() throws Exception {
        final String body = service.withSize("{\"query\":{\"match_all\":{}}}", 100);
        final JsonNode parsed = new ObjectMapper().readTree(body);
        assertEquals(100, parsed.path("size").asInt());
    }

    @Test
    void withSizeHandlesEmptyAndNullBody() throws Exception {
        for (String input : new String[] { null, "", "   " }) {
            final String body = service.withSize(input, 100);
            final JsonNode parsed = new ObjectMapper().readTree(body);
            assertEquals(100, parsed.path("size").asInt());
        }
    }

    @Test
    void withSizeFallsBackToMinimalBodyOnInvalidJson() throws Exception {
        final String body = service.withSize("{ this is not json", 100);
        final JsonNode parsed = new ObjectMapper().readTree(body);
        assertEquals(100, parsed.path("size").asInt());
    }

    // ---------- parseQueryIndex() ----------

    @Test
    void parseQueryIndexExtractsFirstSegment() {
        assertEquals("contracts",
                OpenSearchIngestJobService.parseQueryIndex("contracts/_search { \"query\": {} }"));
        assertEquals("contracts",
                OpenSearchIngestJobService.parseQueryIndex("/contracts/_search { \"query\": {} }"));
    }

    @Test
    void parseQueryIndexHandlesPathOnlyQueries() {
        assertEquals("orders", OpenSearchIngestJobService.parseQueryIndex("orders/_search"));
    }

    @Test
    void parseQueryIndexEmptyInputs() {
        assertEquals("", OpenSearchIngestJobService.parseQueryIndex(null));
        assertEquals("", OpenSearchIngestJobService.parseQueryIndex(""));
        assertEquals("", OpenSearchIngestJobService.parseQueryIndex("   "));
    }

    // ---------- resolveFilename() ----------

    @Test
    void resolveFilenameUsesIdWhenNoFilenameField() throws Exception {
        final JsonNode source = new ObjectMapper().readTree("{\"body\":\"hi\"}");
        assertEquals("abc123",
                OpenSearchIngestJobService.resolveFilename(source, null, "abc123"));
        assertEquals("abc123",
                OpenSearchIngestJobService.resolveFilename(source, "", "abc123"));
    }

    @Test
    void resolveFilenameUsesValueOfConfiguredField() throws Exception {
        final JsonNode source = new ObjectMapper().readTree(
                "{\"title\":\"my-doc.txt\",\"body\":\"hello\"}");
        assertEquals("my-doc.txt",
                OpenSearchIngestJobService.resolveFilename(source, "title", "abc123"));
    }

    @Test
    void resolveFilenameFallsBackToIdWhenFieldMissingOrBlank() throws Exception {
        final JsonNode source = new ObjectMapper().readTree(
                "{\"title\":\"\",\"body\":\"hi\"}");
        // Empty value → fall back to id.
        assertEquals("abc123",
                OpenSearchIngestJobService.resolveFilename(source, "title", "abc123"));
        // Field missing → fall back to id.
        assertEquals("abc123",
                OpenSearchIngestJobService.resolveFilename(source, "missing", "abc123"));
        // null source → fall back to id.
        assertEquals("abc123",
                OpenSearchIngestJobService.resolveFilename(null, "title", "abc123"));
    }

    @Test
    void resolveFilenameSerializesNonStringValues() throws Exception {
        final JsonNode source = new ObjectMapper().readTree("{\"id\":42}");
        // Numbers come back as their JSON serialization (string "42").
        final String result = OpenSearchIngestJobService.resolveFilename(source, "id", "fallback");
        assertNotNull(result);
        assertTrue(result.contains("42"));
    }

    // Make sure jobs created here always carry the right type — guards against a regression
    // where the type is reused for some other category and breaks the Background Jobs page.
    @Test
    void allCreatedJobsCarryDataImportCategory() {
        when(dataSourceRepository.findById("ghost")).thenReturn(Optional.empty());
        final BackgroundJob job = service.start("ghost", "b1", 2, "a@x");
        assertEquals(BackgroundJob.CATEGORY_DATA_IMPORT, job.getCategory());
        assertEquals(BackgroundJob.TYPE_OPENSEARCH_INGEST, job.getType());
    }
}
