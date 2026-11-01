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
import ai.philterd.workspace.model.ElasticsearchDataSource;
import ai.philterd.workspace.repository.BackgroundJobRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.ElasticsearchDataSourceRepository;
import ai.philterd.workspace.service.InboxService;
import ai.philterd.workspace.service.SymmetricCipher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirror of {@link OpenSearchIngestJobServiceTest} for the Elasticsearch ingest service —
 * the wire protocol and behavior are identical, but we exercise the parallel implementation
 * separately to catch divergence between the two services.
 */
class ElasticsearchIngestJobServiceTest {

    private BackgroundJobRepository jobRepository;
    private ElasticsearchDataSourceRepository dataSourceRepository;
    private BatchRepository batchRepository;
    private DocumentRepository documentRepository;
    private IngestQueueService ingestQueueService;
    private SymmetricCipher cipher;
    private InboxService inboxService;
    private ElasticsearchIngestJobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(BackgroundJobRepository.class);
        dataSourceRepository = mock(ElasticsearchDataSourceRepository.class);
        batchRepository = mock(BatchRepository.class);
        documentRepository = mock(DocumentRepository.class);
        ingestQueueService = mock(IngestQueueService.class);
        cipher = mock(SymmetricCipher.class);
        inboxService = mock(InboxService.class);
        when(jobRepository.save(any(BackgroundJob.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        service = new ElasticsearchIngestJobService(jobRepository, dataSourceRepository,
                batchRepository, documentRepository, ingestQueueService, new ObjectMapper(), cipher,
                inboxService, new DataSourceHostAllowList(""), mock(AuditLogService.class),
                mock(DataImportLogService.class));
    }

    @Test
    void startMissingSourceReturnsFailedJob() {
        when(dataSourceRepository.findById("ghost")).thenReturn(Optional.empty());

        final BackgroundJob job = service.start("ghost", "b1", 2, "a@x");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertEquals(BackgroundJob.TYPE_ELASTICSEARCH_INGEST, job.getType());
        assertEquals(BackgroundJob.CATEGORY_DATA_IMPORT, job.getCategory());
        assertEquals("Elasticsearch data source not found.", job.getErrorMessage());
        verify(jobRepository).save(any(BackgroundJob.class));
    }

    @Test
    void startMissingBatchReturnsFailedJob() {
        final ElasticsearchDataSource src = new ElasticsearchDataSource();
        src.setId("src-1");
        src.setName("Demo ES");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(src));
        when(batchRepository.findById("ghost")).thenReturn(Optional.empty());

        final BackgroundJob job = service.start("src-1", "ghost", 2, "a@x");

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertEquals("Batch not found.", job.getErrorMessage());
    }

    @Test
    void runChecksTheEndpointAgainBeforeEachRequest() {
        // #73: allowed when queued is not enough. The endpoint may have been edited since, or its host may now
        // resolve to a private address; the request is refused before it is sent.
        final ai.philterd.workspace.model.ElasticsearchDataSource src = new ai.philterd.workspace.model.ElasticsearchDataSource();
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
        final ElasticsearchIngestJobService restrictedService = new ElasticsearchIngestJobService(
                jobRepository, dataSourceRepository, batchRepository, documentRepository,
                ingestQueueService, new ObjectMapper(), cipher, inboxService,
                new DataSourceHostAllowList("elastic.internal"), mock(AuditLogService.class),
                mock(DataImportLogService.class));

        final ElasticsearchDataSource src = new ElasticsearchDataSource();
        src.setId("src-1");
        src.setName("Demo ES");
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
    void startHappyPathPersistsPendingJob() {
        final ElasticsearchDataSource src = new ElasticsearchDataSource();
        src.setId("src-1");
        src.setName("Demo ES");
        // Public host so the empty default allow-list passes it through; the negative-path
        // case is exercised by startRejectsEndpointNotOnAllowList above.
        src.setEndpoint("http://example.com:9200");
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Sample");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(src));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        final BackgroundJob job = service.start("src-1", "b1", 1, "alice@example.com");

        assertEquals(BackgroundJob.STATUS_PENDING, job.getStatus());
        assertEquals(BackgroundJob.TYPE_ELASTICSEARCH_INGEST, job.getType());
        assertEquals(BackgroundJob.CATEGORY_DATA_IMPORT, job.getCategory());
        assertEquals("src-1", job.getSourceId());
        assertEquals("Demo ES", job.getSourceName());
        assertEquals("b1", job.getBatchId());
        assertEquals("Sample", job.getBatchName());
        assertEquals(1, job.getPriority());
        assertNotNull(job.getCreatedAt());
    }

    @Test
    void startQueuesPendingEvenWhenAnotherJobIsAlreadyRunning() {
        // Multiple imports can be queued for the same batch — the dispatcher promotes
        // them to RUNNING one at a time. start() never refuses based on existing jobs.
        final ElasticsearchDataSource src = new ElasticsearchDataSource();
        src.setId("src-1");
        src.setName("Demo ES");
        src.setEndpoint("http://example.com:9200");
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Sample");
        when(dataSourceRepository.findById("src-1")).thenReturn(Optional.of(src));
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        final BackgroundJob first = service.start("src-1", "b1", 2, "alice@example.com");
        final BackgroundJob second = service.start("src-1", "b1", 2, "alice@example.com");

        assertEquals(BackgroundJob.STATUS_PENDING, first.getStatus());
        assertEquals(BackgroundJob.STATUS_PENDING, second.getStatus());
    }

    @Test
    void withSizeOverridesAndDefaults() throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        // Override existing size.
        JsonNode parsed = mapper.readTree(service.withSize("{\"size\": 9999}", 100));
        assertEquals(100, parsed.path("size").asInt());
        // Set when absent.
        parsed = mapper.readTree(service.withSize("{\"query\":{\"match_all\":{}}}", 100));
        assertEquals(100, parsed.path("size").asInt());
        // Empty body.
        parsed = mapper.readTree(service.withSize("", 100));
        assertEquals(100, parsed.path("size").asInt());
        // Invalid JSON falls back to a minimal body.
        parsed = mapper.readTree(service.withSize("not json {", 100));
        assertEquals(100, parsed.path("size").asInt());
    }

    @Test
    void parseQueryIndexExtractsFirstSegment() {
        assertEquals("contracts",
                ElasticsearchIngestJobService.parseQueryIndex("contracts/_search { }"));
        assertEquals("contracts",
                ElasticsearchIngestJobService.parseQueryIndex("/contracts/_search"));
        assertEquals("orders",
                ElasticsearchIngestJobService.parseQueryIndex("orders/_search"));
        assertEquals("", ElasticsearchIngestJobService.parseQueryIndex(null));
        assertEquals("", ElasticsearchIngestJobService.parseQueryIndex(""));
    }

    @Test
    void resolveFilenameUsesFieldOrFallsBackToId() throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode source = mapper.readTree("{\"title\":\"my.txt\"}");
        assertEquals("my.txt",
                ElasticsearchIngestJobService.resolveFilename(source, "title", "id-1"));
        assertEquals("id-1",
                ElasticsearchIngestJobService.resolveFilename(source, null, "id-1"));
        assertEquals("id-1",
                ElasticsearchIngestJobService.resolveFilename(source, "missing", "id-1"));
        assertEquals("id-1",
                ElasticsearchIngestJobService.resolveFilename(null, "title", "id-1"));
        // Blank value → fall back.
        final JsonNode blank = mapper.readTree("{\"title\":\"   \"}");
        assertEquals("id-1",
                ElasticsearchIngestJobService.resolveFilename(blank, "title", "id-1"));
    }

    @Test
    void resolveFilenameSerializesNonTextual() throws Exception {
        final JsonNode source = new ObjectMapper().readTree("{\"id\":99}");
        final String result =
                ElasticsearchIngestJobService.resolveFilename(source, "id", "fallback");
        assertNotNull(result);
        assertTrue(result.contains("99"));
    }
}
