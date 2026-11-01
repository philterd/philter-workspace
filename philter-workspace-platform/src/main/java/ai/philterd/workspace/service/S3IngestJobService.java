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
import ai.philterd.workspace.model.S3DataSource;
import ai.philterd.workspace.repository.BackgroundJobRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.S3DataSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Runs S3 / S3-compatible ingest jobs on a background thread. The user picks a configured
 * {@link S3DataSource} on the Add Documents page; we record a {@link BackgroundJob}, return
 * immediately, and the dispatcher walks the bucket under the configured prefix, applies the
 * data source's filename glob, downloads each matching object, and pushes its content onto
 * the Philter Workspace ingest queue.
 *
 * <p>Mirrors {@link OpenSearchIngestJobService} and {@link LocalDirectoryIngestJobService}
 * — same {@code start} → PENDING, dispatcher-claimed {@code run}, per-object
 * processed/failed/skipped counters, audit events, and inbox notification on completion.
 *
 * <p>Source attribution stored on each imported {@code Document}:
 * <ul>
 *   <li>{@code sourceSystem = "S3"}</li>
 *   <li>{@code sourceUrl =} the S3 service endpoint, or {@code s3://<bucket>} for AWS</li>
 *   <li>{@code sourceIndex = bucket}</li>
 *   <li>{@code sourceDocId =} object key (full key including any prefix)</li>
 * </ul>
 * The {@code (sourceIndex, sourceDocId)} pair is what dedupe is keyed on, so re-running an
 * ingest on the same bucket only enqueues new objects since the last run.
 */
@Service
public class S3IngestJobService {

    private static final Logger log = LoggerFactory.getLogger(S3IngestJobService.class);

    /** Hard ceiling on objects walked per run, defends against an accidentally root-prefix. */
    private static final int MAX_OBJECTS_PER_RUN = 100_000;

    /** Default region used when the operator hasn't pinned one — same as the destination writer. */
    private static final Region DEFAULT_REGION = Region.US_EAST_1;

    static final String SOURCE_SYSTEM = "S3";

    private final BackgroundJobRepository jobRepository;
    private final S3DataSourceRepository dataSourceRepository;
    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final IngestQueueService ingestQueueService;
    private final SymmetricCipher cipher;
    private final InboxService inboxService;
    private final DataSourceHostAllowList hostAllowList;
    private final AuditLogService auditLogService;
    private final DataImportLogService importLogService;
    /**
     * Indirection so tests can inject a fake S3 client without wiring up a real one.
     * Production callers use {@link DefaultS3ClientFactory#build}, which builds a fresh
     * SDK client per job from the data source's stored credentials and endpoint.
     */
    private final S3ClientFactory s3ClientFactory;

    @Autowired
    public S3IngestJobService(final BackgroundJobRepository jobRepository,
                              final S3DataSourceRepository dataSourceRepository,
                              final BatchRepository batchRepository,
                              final DocumentRepository documentRepository,
                              final IngestQueueService ingestQueueService,
                              final SymmetricCipher cipher,
                              final InboxService inboxService,
                              final DataSourceHostAllowList hostAllowList,
                              final AuditLogService auditLogService,
                              final DataImportLogService importLogService) {
        this(jobRepository, dataSourceRepository, batchRepository, documentRepository,
                ingestQueueService, cipher, inboxService, hostAllowList, auditLogService,
                importLogService, new DefaultS3ClientFactory());
    }

    /** Test-only constructor that lets a unit test inject a stub {@link S3ClientFactory}. */
    S3IngestJobService(final BackgroundJobRepository jobRepository,
                       final S3DataSourceRepository dataSourceRepository,
                       final BatchRepository batchRepository,
                       final DocumentRepository documentRepository,
                       final IngestQueueService ingestQueueService,
                       final SymmetricCipher cipher,
                       final InboxService inboxService,
                       final DataSourceHostAllowList hostAllowList,
                       final AuditLogService auditLogService,
                       final DataImportLogService importLogService,
                       final S3ClientFactory s3ClientFactory) {
        this.jobRepository = jobRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.ingestQueueService = ingestQueueService;
        this.cipher = cipher;
        this.inboxService = inboxService;
        this.hostAllowList = hostAllowList;
        this.auditLogService = auditLogService;
        this.importLogService = importLogService;
        this.s3ClientFactory = s3ClientFactory;
    }

    /**
     * Persist a PENDING job. Returns the saved job so the caller can redirect to the
     * Background Jobs page. Validation failures (missing source, missing batch, allow-list
     * violation) produce a FAILED row with an error message right here so the user gets
     * immediate feedback.
     */
    public BackgroundJob start(final String sourceId, final String batchId, final int priority,
                               final String actorEmail) {
        final S3DataSource source = dataSourceRepository.findById(sourceId).orElse(null);
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (source == null) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "S3 data source not found.");
        }
        if (batch == null) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Batch not found.");
        }
        // Endpoint is optional (null/blank means use AWS native). When set, gate it
        // through the same allow-list as OpenSearch / Elasticsearch / Philter / Ollama
        // so an operator can't accidentally point at internal infrastructure.
        if (source.getEndpoint() != null && !source.getEndpoint().isBlank()
                && !hostAllowList.isAllowed(source.getEndpoint())) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "S3 endpoint host is not on the data-source allow-list "
                            + "(philter-workspace.data-sources.allowed-hosts).");
        }
        if (source.getBucketName() == null || source.getBucketName().isBlank()) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Data source has no bucket name configured.");
        }
        if (compileGlob(source.getFilenameGlob()) == null) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Filename glob is missing or unparseable: \""
                            + (source.getFilenameGlob() == null ? "" : source.getFilenameGlob()) + "\".");
        }

        final BackgroundJob job = new BackgroundJob();
        job.setId(UUID.randomUUID().toString());
        job.setType(BackgroundJob.TYPE_S3_INGEST);
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
        job.setType(BackgroundJob.TYPE_S3_INGEST);
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
     * Execute a previously-persisted PENDING job. Invoked by {@code DataImportDispatcher}
     * after it has atomically claimed the job.
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

        final S3DataSource source = dataSourceRepository.findById(job.getSourceId()).orElse(null);
        final Batch batch = batchRepository.findById(job.getBatchId()).orElse(null);
        if (source == null || batch == null) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Source or batch was deleted before the job ran.");
            return;
        }

        final PathMatcher matcher = compileGlob(source.getFilenameGlob());
        if (matcher == null) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Filename glob is missing or unparseable: \""
                            + (source.getFilenameGlob() == null ? "" : source.getFilenameGlob()) + "\".");
            return;
        }
        if (source.getBucketName() == null || source.getBucketName().isBlank()) {
            finish(job, BackgroundJob.STATUS_FAILED, "Data source has no bucket name configured.");
            return;
        }

        final String bucket = source.getBucketName();
        final String prefix = source.getBucketKey() == null ? "" : source.getBucketKey();
        final String sourceUrl = sourceUrlFor(source);

        try (S3Client s3 = s3ClientFactory.build(source, cipher)) {
            // Walk the bucket once to find candidates. Materializing into a list lets the
            // total count be known before any download starts, so the Background Jobs page
            // shows "X of Y" from the very first save.
            final java.util.Set<String> sidecarKeys = new java.util.HashSet<>();
            final List<S3Object> candidates = collectCandidates(s3, bucket, prefix, matcher, sidecarKeys);
            job.setTotalDocuments(candidates.size());
            jobRepository.save(job);

            for (S3Object obj : candidates) {
                final String key = obj.key();
                final String filename = filenameOf(key);
                if (documentRepository.existsBySourceIndexAndSourceDocId(bucket, key)) {
                    recordSkipped(job, batch, filename, bucket, key, sourceUrl);
                    importLogService.skipped(job.getId(), filename, key);
                    continue;
                }
                try {
                    // The object's case metadata (#16), from its sidecar object when it has one.
                    final String sidecarKey = CaseMetadataSidecar.sidecarFor(key);
                    final ai.philterd.workspace.model.CaseMetadata caseMetadata = sidecarKeys.contains(sidecarKey)
                            ? CaseMetadataSidecar.parse(downloadObject(s3, bucket, sidecarKey)) : null;
                    final byte[] bytes = downloadObject(s3, bucket, key);
                    final boolean isPdf = isPdf(filename);
                    final ai.philterd.workspace.model.Document doc;
                    if (isPdf) {
                        doc = ingestQueueService.enqueueFile(batch, filename, bytes,
                                "application/pdf", job.getPriority(), caseMetadata);
                    } else {
                        doc = ingestQueueService.enqueueText(batch, filename,
                                new String(bytes, StandardCharsets.UTF_8), job.getPriority(), caseMetadata);
                    }
                    doc.setSourceSystem(SOURCE_SYSTEM);
                    doc.setSourceUrl(sourceUrl);
                    doc.setSourceIndex(bucket);
                    doc.setSourceDocId(key);
                    doc.setImportedAt(java.time.LocalDateTime.now());
                    documentRepository.save(doc);
                    auditDocumentImport(job, doc, "SUCCESS");
                    bumpProcessed(job);
                    importLogService.success(job.getId(), filename, key);
                } catch (Exception e) {
                    final String reason = "Failed to download or enqueue \"" + key + "\": "
                            + e.getMessage();
                    log.warn("Job {} ({} from {}): an item failed: {}", job.getId(), job.getType(), job.getSourceName(), ErrorClasses.of(e));
                    bumpFailed(job, reason);
                    importLogService.failed(job.getId(), filename, key, e.getMessage());
                }
            }
            finish(job, BackgroundJob.STATUS_COMPLETED, null);
        } catch (Exception e) {
            log.warn("Job {} ({} from {}) failed: {}", job.getId(), job.getType(), job.getSourceName(), ErrorClasses.of(e));
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Could not run S3 ingest: " + e.getMessage());
        }
    }

    /**
     * List every object under {@code prefix} in {@code bucket}, paginating with
     * ListObjectsV2's continuation token, and keep only the ones whose key (relative to the
     * prefix) matches the user-supplied glob. Capped at {@link #MAX_OBJECTS_PER_RUN}.
     */
    private List<S3Object> collectCandidates(final S3Client s3, final String bucket,
                                             final String prefix, final PathMatcher matcher,
                                             final java.util.Set<String> sidecarKeys) {
        final List<S3Object> matches = new ArrayList<>();
        String continuationToken = null;
        do {
            final ListObjectsV2Request.Builder b = ListObjectsV2Request.builder()
                    .bucket(bucket);
            if (prefix != null && !prefix.isEmpty()) b.prefix(prefix);
            if (continuationToken != null) b.continuationToken(continuationToken);
            final ListObjectsV2Response resp = s3.listObjectsV2(b.build());
            for (S3Object obj : resp.contents()) {
                final String key = obj.key();
                if (key == null || key.endsWith("/")) continue;
                // A case sidecar describes the object beside it and is never a document itself.
                if (CaseMetadataSidecar.isSidecar(key)) {
                    sidecarKeys.add(key);
                    continue;
                }
                if (matches.size() < MAX_OBJECTS_PER_RUN && matchesGlob(matcher, prefix, key)) {
                    matches.add(obj);
                }
            }
            // At the limit, the rest of this page is still read for sidecars: S3 lists "x.pdf" just before
            // "x.pdf.case.json", so stopping mid-page could lose the last document's metadata.
            continuationToken = matches.size() < MAX_OBJECTS_PER_RUN && Boolean.TRUE.equals(resp.isTruncated())
                    ? resp.nextContinuationToken() : null;
        } while (continuationToken != null);
        return matches;
    }

    /**
     * Match the supplied glob against the object key's relative path under the prefix —
     * mirroring the way {@link LocalDirectoryIngestJobService} matches against the file's
     * path relative to the configured directory. {@code "*.pdf"} matches only top-level
     * keys; {@code "**\/*.pdf"} matches keys at any depth under the prefix.
     */
    static boolean matchesGlob(final PathMatcher matcher, final String prefix, final String key) {
        String relative = key;
        if (prefix != null && !prefix.isEmpty()) {
            String p = prefix;
            if (relative.startsWith(p)) {
                relative = relative.substring(p.length());
            }
            if (relative.startsWith("/")) relative = relative.substring(1);
        }
        if (relative.isEmpty()) return false;
        try {
            return matcher.matches(Paths.get(relative));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] downloadObject(final S3Client s3, final String bucket, final String key) {
        final ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return bytes.asByteArray();
    }

    private static String filenameOf(final String key) {
        if (key == null) return "";
        final int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    /** Compose a stable, human-readable URL for source attribution on imported documents. */
    private static String sourceUrlFor(final S3DataSource source) {
        if (source.getEndpoint() != null && !source.getEndpoint().isBlank()) {
            return source.getEndpoint();
        }
        return "s3://" + (source.getBucketName() == null ? "" : source.getBucketName());
    }

    /** Compile the operator-supplied glob using the JDK matcher, like LocalDirectoryIngestJobService. */
    static PathMatcher compileGlob(final String glob) {
        if (glob == null || glob.isBlank()) return null;
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isPdf(final String filename) {
        if (filename == null) return false;
        return filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /**
     * Persist a SKIPPED placeholder Document row for an object that's already been
     * imported (matched by {@code (sourceIndex=bucket, sourceDocId=key)}). The row carries
     * the source attribution so the audit trail points back to the object, but no content.
     */
    private void recordSkipped(final BackgroundJob job, final Batch batch, final String filename,
                               final String bucket, final String key, final String sourceUrl) {
        final ai.philterd.workspace.model.Document doc = new ai.philterd.workspace.model.Document();
        doc.setId(UUID.randomUUID().toString());
        doc.setBatchId(batch.getId());
        doc.setFilename(filename);
        doc.setOriginalText("");
        doc.setSourceSystem(SOURCE_SYSTEM);
        doc.setSourceUrl(sourceUrl);
        doc.setSourceIndex(bucket);
        doc.setSourceDocId(key);
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
        details.put("type", BackgroundJob.TYPE_S3_INGEST);
        details.put("sourceId", job.getSourceId() == null ? "" : job.getSourceId());
        details.put("sourceName", job.getSourceName() == null ? "" : job.getSourceName());
        details.put("batchId", job.getBatchId() == null ? "" : job.getBatchId());
        details.put("batchName", job.getBatchName() == null ? "" : job.getBatchName());
        auditLogService.logForUser(actor(job), "DATA_IMPORT_STARTED",
                "BackgroundJob", job.getId(),
                AuditLogService.OUTCOME_SUCCESS, details);
    }

    private void auditDocumentImport(final BackgroundJob job,
                                     final ai.philterd.workspace.model.Document doc,
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
        details.put("type", BackgroundJob.TYPE_S3_INGEST);
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
        sb.append("S3 import from \"").append(source).append("\" into batch \"").append(batch);
        if (ok) {
            sb.append("\" completed. Processed ").append(job.getProcessedDocuments());
            if (job.getTotalDocuments() >= 0) {
                sb.append(" of ").append(job.getTotalDocuments());
            }
            sb.append(" object(s)");
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

    /**
     * Builds an {@link S3Client} for a given data source. Production: build from the
     * stored credentials and (optional) endpoint. Test: an injected double can return a
     * fake client backed by an in-memory store.
     */
    interface S3ClientFactory {
        S3Client build(S3DataSource source, SymmetricCipher cipher);
    }

    /** Default factory used by Spring. */
    static final class DefaultS3ClientFactory implements S3ClientFactory {
        @Override
        public S3Client build(final S3DataSource source, final SymmetricCipher cipher) {
            final String access = decryptOrEmpty(cipher, source.getEncryptedAccessKey());
            final String secret = decryptOrEmpty(cipher, source.getEncryptedSecretKey());
            final boolean hasEndpointOverride = source.getEndpoint() != null
                    && !source.getEndpoint().isBlank();
            final S3ClientBuilder b = S3Client.builder()
                    .region(DEFAULT_REGION)
                    .credentialsProvider(credentialsFor(access, secret))
                    // Cross-region access only makes sense against real AWS. When the
                    // operator has pinned an endpoint (MinIO / Cloudflare R2 /
                    // Backblaze B2), enabling it triggers a region-discovery HeadBucket
                    // probe whose response shape these stores don't fully match. In
                    // practice the side effect was that ListObjectsV2 returned an empty
                    // result set instead of the bucket's actual contents — exactly the
                    // "imported 0 documents" symptom against demo MinIO. Disable for
                    // endpoint-overridden builds; keep it on for native AWS.
                    .crossRegionAccessEnabled(!hasEndpointOverride);
            // S3-compatible storage (MinIO, Cloudflare R2, Backblaze B2, …) needs an
            // explicit endpoint override and path-style addressing — virtual-hosted
            // bucket URLs typically don't resolve against arbitrary hosts.
            if (hasEndpointOverride) {
                b.endpointOverride(URI.create(source.getEndpoint()))
                        .forcePathStyle(true);
            }
            return b.build();
        }

        private static String decryptOrEmpty(final SymmetricCipher cipher, final String ciphertext) {
            if (ciphertext == null || ciphertext.isEmpty()) return "";
            try {
                return cipher.decrypt(ciphertext);
            } catch (RuntimeException e) {
                // Fall back to ambient AWS credentials rather than crashing on a bad
                // ciphertext — matches the behavior of DestinationWriter.
                return "";
            }
        }

        private static AwsCredentialsProvider credentialsFor(final String accessKey, final String secretKey) {
            if (accessKey != null && !accessKey.isEmpty()
                    && secretKey != null && !secretKey.isEmpty()) {
                return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
            }
            return DefaultCredentialsProvider.create();
        }
    }
}
