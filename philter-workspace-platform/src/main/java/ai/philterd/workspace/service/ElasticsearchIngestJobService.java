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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.UUID;

/**
 * Background ingest worker for Elasticsearch data sources. Behaves identically to
 * {@link OpenSearchIngestJobService} — Elasticsearch and OpenSearch share the same
 * {@code _search} / scroll wire protocol.
 */
@Service
public class ElasticsearchIngestJobService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchIngestJobService.class);

    static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 100_000;
    private static final String SCROLL_KEEPALIVE = "1m";

    private final BackgroundJobRepository jobRepository;
    private final ElasticsearchDataSourceRepository dataSourceRepository;
    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final IngestQueueService ingestQueueService;
    private final ObjectMapper objectMapper;
    private final SymmetricCipher cipher;
    private final InboxService inboxService;
    private final DataSourceHostAllowList hostAllowList;
    private final AuditLogService auditLogService;
    private final DataImportLogService importLogService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ElasticsearchIngestJobService(final BackgroundJobRepository jobRepository,
                                         final ElasticsearchDataSourceRepository dataSourceRepository,
                                         final BatchRepository batchRepository,
                                         final DocumentRepository documentRepository,
                                         final IngestQueueService ingestQueueService,
                                         final ObjectMapper objectMapper,
                                         final SymmetricCipher cipher,
                                         final InboxService inboxService,
                                         final DataSourceHostAllowList hostAllowList,
                                         final AuditLogService auditLogService,
                                         final DataImportLogService importLogService) {
        this.jobRepository = jobRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.ingestQueueService = ingestQueueService;
        this.objectMapper = objectMapper;
        this.cipher = cipher;
        this.inboxService = inboxService;
        this.hostAllowList = hostAllowList;
        this.auditLogService = auditLogService;
        this.importLogService = importLogService;
    }

    public BackgroundJob start(final String sourceId, final String batchId, final int priority,
                               final String actorEmail) {
        final ElasticsearchDataSource source = dataSourceRepository.findById(sourceId).orElse(null);
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (source == null) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Elasticsearch data source not found.");
        }
        if (batch == null) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Batch not found.");
        }
        if (!hostAllowList.isAllowed(source.getEndpoint())) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Endpoint host is not on the data-source allow-list "
                            + "(philter-workspace.data-sources.allowed-hosts).");
        }

        final BackgroundJob job = new BackgroundJob();
        job.setId(UUID.randomUUID().toString());
        job.setType(BackgroundJob.TYPE_ELASTICSEARCH_INGEST);
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
        job.setType(BackgroundJob.TYPE_ELASTICSEARCH_INGEST);
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
     * Execute a previously-persisted PENDING job. Mirrors {@link OpenSearchIngestJobService#run}.
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

        final ElasticsearchDataSource source = dataSourceRepository.findById(job.getSourceId()).orElse(null);
        final Batch batch = batchRepository.findById(job.getBatchId()).orElse(null);
        if (source == null || batch == null) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Source or batch was deleted before the job ran.");
            return;
        }

        final String textField = source.getTextField() == null ? "" : source.getTextField();
        if (textField.isBlank()) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Data source has no Text field configured.");
            return;
        }

        final String defaultIndex = parseQueryIndex(source.getQuery());
        final String endpoint = source.getEndpoint();
        final String authHeader = basicAuthHeader(source);

        String scrollId = null;
        try {
            final JsonNode firstPage = executeInitialQuery(source, PAGE_SIZE, authHeader);
            if (firstPage == null) {
                finish(job, BackgroundJob.STATUS_FAILED, "Empty response from Elasticsearch.");
                return;
            }
            final long total = firstPage.path("hits").path("total").path("value").asLong(
                    firstPage.path("hits").path("total").asLong(-1));
            if (total >= 0) {
                job.setTotalDocuments(total);
                jobRepository.save(job);
            }

            scrollId = firstPage.path("_scroll_id").asString(null);
            JsonNode hits = firstPage.path("hits").path("hits");
            int pageNumber = 1;
            int hitsThisPage = hits.isArray() ? hits.size() : 0;
            log.info("Job {}: page {} returned {} hits (scroll={}).",
                    job.getId(), pageNumber, hitsThisPage, scrollId != null);
            processHits(hits, job, source, batch, textField, defaultIndex, endpoint);

            while (scrollId != null && hitsThisPage > 0 && pageNumber < MAX_PAGES) {
                final JsonNode page = executeScroll(endpoint, authHeader, scrollId);
                if (page == null) break;
                scrollId = page.path("_scroll_id").asString(scrollId);
                hits = page.path("hits").path("hits");
                hitsThisPage = hits.isArray() ? hits.size() : 0;
                pageNumber++;
                log.info("Job {}: page {} returned {} hits.", job.getId(), pageNumber, hitsThisPage);
                if (hitsThisPage == 0) break;
                processHits(hits, job, source, batch, textField, defaultIndex, endpoint);
            }

            if (job.getTotalDocuments() < 0) {
                job.setTotalDocuments(job.getProcessedDocuments()
                        + job.getFailedDocuments() + job.getSkippedDocuments());
                jobRepository.save(job);
            }
            finish(job, BackgroundJob.STATUS_COMPLETED, null);
        } catch (Exception e) {
            log.warn("Job {} ({} from {}) failed: {}", job.getId(), job.getType(), job.getSourceName(), ErrorClasses.of(e));
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Could not run Elasticsearch ingest: " + e.getMessage());
        } finally {
            if (scrollId != null) {
                try {
                    closeScroll(endpoint, authHeader, scrollId);
                } catch (Exception e) {
                    log.debug("Job {}: failed to close scroll context: {}", job.getId(), e.getMessage());
                }
            }
        }
    }

    private void processHits(final JsonNode hits, final BackgroundJob job,
                             final ElasticsearchDataSource source, final Batch batch,
                             final String textField, final String defaultIndex, final String sourceUrl) {
        if (!hits.isArray()) return;
        int index = 0;
        for (Iterator<JsonNode> it = hits.values().iterator(); it.hasNext(); index++) {
            final JsonNode hit = it.next();
            final JsonNode sourceNode = hit.path("_source");
            final JsonNode textNode = sourceNode.path(textField);
            final String hitId = hit.path("_id").asString("hit-" + index);
            if (textNode.isMissingNode() || textNode.isNull()) {
                final String reason = "Hit " + hitId + " has no '" + textField + "' field in _source.";
                log.warn("Job {}: {}", job.getId(), reason);
                bumpFailed(job, reason);
                importLogService.failed(job.getId(), hitId, hitId, reason);
                continue;
            }
            final String text = textNode.isString() ? textNode.asString() : textNode.toString();
            final String filename = resolveFilename(sourceNode, source.getFilenameField(), hitId);
            final String hitIndex = hit.path("_index").isMissingNode()
                    ? defaultIndex
                    : hit.path("_index").asString(defaultIndex);
            final String sourceDocIdValue = hit.path("_id").asString("");

            // Dedup: if a Document with the same (sourceIndex, sourceDocId) already exists,
            // record a SKIPPED placeholder so the import attempt is auditable but don't
            // re-enqueue the content.
            if (!sourceDocIdValue.isEmpty()
                    && documentRepository.existsBySourceIndexAndSourceDocId(hitIndex, sourceDocIdValue)) {
                recordSkipped(job, batch, filename, hitIndex, sourceDocIdValue, sourceUrl, "ELASTICSEARCH");
                importLogService.skipped(job.getId(), filename, sourceDocIdValue);
                continue;
            }

            try {
                final ai.philterd.workspace.model.Document doc =
                        ingestQueueService.enqueueText(batch, filename, text, job.getPriority());
                doc.setSourceSystem("ELASTICSEARCH");
                doc.setSourceUrl(sourceUrl);
                doc.setSourceIndex(hitIndex);
                doc.setSourceDocId(sourceDocIdValue);
                doc.setImportedAt(java.time.LocalDateTime.now());
                documentRepository.save(doc);
                auditDocumentImport(job, doc, "SUCCESS");
                bumpProcessed(job);
                importLogService.success(job.getId(), filename, sourceDocIdValue);
            } catch (Exception e) {
                final String reason = "Failed to enqueue hit " + hitId + ": " + e.getMessage();
                log.warn("Job {} ({} from {}): an item failed: {}", job.getId(), job.getType(), job.getSourceName(), ErrorClasses.of(e));
                bumpFailed(job, reason);
                importLogService.failed(job.getId(), filename, sourceDocIdValue, e.getMessage());
            }
        }
    }

    /**
     * Persist a SKIPPED placeholder Document row for a hit that's already been imported
     * (matched by source index + source doc id). Mirrors {@link OpenSearchIngestJobService}.
     */
    private void recordSkipped(final BackgroundJob job, final Batch batch, final String filename,
                               final String hitIndex, final String hitId, final String sourceUrl,
                               final String sourceSystem) {
        final ai.philterd.workspace.model.Document doc = new ai.philterd.workspace.model.Document();
        doc.setId(UUID.randomUUID().toString());
        doc.setBatchId(batch.getId());
        doc.setFilename(filename);
        doc.setOriginalText("");
        doc.setSourceSystem(sourceSystem);
        doc.setSourceUrl(sourceUrl);
        doc.setSourceIndex(hitIndex);
        doc.setSourceDocId(hitId);
        doc.setImportedAt(java.time.LocalDateTime.now());
        doc.setCreatedAt(java.time.LocalDateTime.now());
        doc.setPriority(job.getPriority());
        doc.changeStatus("SKIPPED");
        documentRepository.save(doc);
        auditDocumentImport(job, doc, "SKIPPED");
        bumpSkipped(job);
    }

    private JsonNode executeInitialQuery(final ElasticsearchDataSource source, final int pageSize,
                                         final String authHeader) throws Exception {
        final String endpoint = source.getEndpoint() == null ? "" : source.getEndpoint().trim();
        final String query = source.getQuery() == null ? "" : source.getQuery().trim();
        if (endpoint.isEmpty() || query.isEmpty()) {
            throw new IllegalStateException("Endpoint and query are both required.");
        }
        final int split = firstWhitespace(query);
        final String path = split < 0 ? query : query.substring(0, split);
        final String rawBody = split < 0 ? "{}" : query.substring(split + 1).trim();
        final String body = withSize(rawBody, pageSize);
        final String url = endpoint.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "")
                + (path.contains("?") ? "&" : "?") + "scroll=" + SCROLL_KEEPALIVE;
        return sendJson(url, body, authHeader);
    }

    private JsonNode executeScroll(final String endpoint, final String authHeader,
                                   final String scrollId) throws Exception {
        if (endpoint == null || scrollId == null || scrollId.isEmpty()) return null;
        final String url = endpoint.replaceAll("/+$", "") + "/_search/scroll";
        final java.util.Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("scroll", SCROLL_KEEPALIVE);
        body.put("scroll_id", scrollId);
        return sendJson(url, objectMapper.writeValueAsString(body), authHeader);
    }

    private void closeScroll(final String endpoint, final String authHeader,
                             final String scrollId) throws Exception {
        if (endpoint == null || scrollId == null || scrollId.isEmpty()) return;
        final String url = endpoint.replaceAll("/+$", "") + "/_search/scroll";
        final java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("scroll_id", java.util.List.of(scrollId));
        final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (authHeader != null) reqBuilder.header("Authorization", authHeader);
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.discarding());
    }

    private JsonNode sendJson(final String url, final String body, final String authHeader) throws Exception {
        final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (authHeader != null) reqBuilder.header("Authorization", authHeader);
        final HttpResponse<String> resp = httpClient.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            // Do not include resp.body() — it carries partial search hits with raw
            // originalText for the documents this job was about to ingest, and the
            // exception bubbles up to a log site. Status + length is the safe surrogate.
            throw new IllegalStateException("Elasticsearch returned HTTP " + resp.statusCode()
                    + " (body length " + (resp.body() == null ? 0 : resp.body().length()) + ")");
        }
        return objectMapper.readTree(resp.body());
    }

    private String basicAuthHeader(final ElasticsearchDataSource source) {
        final String username = source.getUsername();
        if (username == null || username.isBlank()) return null;
        final String password = source.getEncryptedPassword() == null
                || source.getEncryptedPassword().isEmpty()
                ? ""
                : cipher.decrypt(source.getEncryptedPassword());
        final String creds = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    String withSize(final String rawBody, final int pageSize) {
        try {
            final JsonNode parsed = (rawBody == null || rawBody.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(rawBody);
            if (parsed instanceof tools.jackson.databind.node.ObjectNode obj) {
                obj.put("size", pageSize);
                return objectMapper.writeValueAsString(obj);
            }
        } catch (Exception ignored) {
            // fall through to fallback
        }
        return "{\"size\":" + pageSize + "}";
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

    /**
     * Drop a one-line summary into the starting user's inbox so they don't have to keep an
     * eye on the Background Jobs page. Mirrors {@link OpenSearchIngestJobService#notifyOwner}.
     */
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
        sb.append("Elasticsearch import from \"").append(source).append("\" into batch \"").append(batch);
        if (ok) {
            sb.append("\" completed. Processed ").append(job.getProcessedDocuments());
            if (job.getTotalDocuments() >= 0) {
                sb.append(" of ").append(job.getTotalDocuments());
            }
            sb.append(" document(s)");
            if (job.getFailedDocuments() > 0) {
                sb.append(", ").append(job.getFailedDocuments()).append(" failed");
            }
            if (job.getSkippedDocuments() > 0) {
                sb.append(", ").append(job.getSkippedDocuments()).append(" skipped (already imported)");
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

    private static int firstWhitespace(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    static String resolveFilename(final JsonNode sourceNode, final String filenameField,
                                  final String hitId) {
        if (filenameField != null && !filenameField.isBlank() && sourceNode != null) {
            final JsonNode node = sourceNode.path(filenameField);
            if (!node.isMissingNode() && !node.isNull()) {
                final String value = node.isString() ? node.asString() : node.toString();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return hitId;
    }

    static String parseQueryIndex(final String query) {
        if (query == null) return "";
        final String trimmed = query.trim();
        if (trimmed.isEmpty()) return "";
        final int ws = firstWhitespace(trimmed);
        final String path = ws < 0 ? trimmed : trimmed.substring(0, ws);
        final String stripped = path.replaceAll("^/+", "");
        final int slash = stripped.indexOf('/');
        return slash < 0 ? stripped : stripped.substring(0, slash);
    }

    /** {@code DATA_IMPORT_STARTED} audit row when the dispatcher promotes the job to RUNNING. */
    private void auditJobStarted(final BackgroundJob job) {
        final java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("jobId", job.getId());
        details.put("type", BackgroundJob.TYPE_ELASTICSEARCH_INGEST);
        details.put("sourceId", job.getSourceId() == null ? "" : job.getSourceId());
        details.put("sourceName", job.getSourceName() == null ? "" : job.getSourceName());
        details.put("batchId", job.getBatchId() == null ? "" : job.getBatchId());
        details.put("batchName", job.getBatchName() == null ? "" : job.getBatchName());
        auditLogService.logForUser(actor(job), "DATA_IMPORT_STARTED",
                "BackgroundJob", job.getId(),
                AuditLogService.OUTCOME_SUCCESS, details);
    }

    /** One {@code DOCUMENT_IMPORT} row per imported document, linking back to the ES hit. */
    private void auditDocumentImport(final BackgroundJob job,
                                     final ai.philterd.workspace.model.Document doc,
                                     final String outcome) {
        final java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
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

    /** Terminal {@code DATA_IMPORT_COMPLETED} / {@code DATA_IMPORT_FAILED} audit row. */
    private void auditJobTerminal(final BackgroundJob job, final String error) {
        final boolean ok = BackgroundJob.STATUS_COMPLETED.equals(job.getStatus());
        final String action = ok ? "DATA_IMPORT_COMPLETED" : "DATA_IMPORT_FAILED";
        final java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("jobId", job.getId());
        details.put("type", BackgroundJob.TYPE_ELASTICSEARCH_INGEST);
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
}
