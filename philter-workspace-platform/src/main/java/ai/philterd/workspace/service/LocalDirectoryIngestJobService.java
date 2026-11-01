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
import ai.philterd.workspace.model.LocalDirectoryDataSource;
import ai.philterd.workspace.repository.BackgroundJobRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.LocalDirectoryDataSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Runs local-directory ingest jobs on a background thread. The user picks a configured
 * {@link LocalDirectoryDataSource} on the Add Documents page; we record a
 * {@link BackgroundJob}, return immediately, and the dispatcher walks the directory,
 * pushing each matching file's content onto the Philter Workspace ingest queue.
 *
 * <p>Mirrors the {@link OpenSearchIngestJobService} shape — same {@code start} → PENDING,
 * dispatcher-claimed {@code run}, per-file processed/failed/skipped counters, and inbox
 * notification on completion.
 *
 * <p>Source attribution: {@code sourceSystem = "LOCAL_DIRECTORY"}, {@code sourceUrl =}
 * absolute directory path, {@code sourceIndex =} normalized directory path,
 * {@code sourceDocId =} relative path of the file within the directory. The
 * {@code (sourceIndex, sourceDocId)} pair is what dedupe is keyed on, so re-running an
 * ingest on the same directory imports only files that weren't already pulled in.
 */
@Service
public class LocalDirectoryIngestJobService {

    private static final Logger log = LoggerFactory.getLogger(LocalDirectoryIngestJobService.class);

    /** Hard ceiling on file walk to defend against accidentally pointing at "/". */
    private static final int MAX_FILES_PER_RUN = 100_000;

    static final String SOURCE_SYSTEM = "LOCAL_DIRECTORY";

    private final BackgroundJobRepository jobRepository;
    private final LocalDirectoryDataSourceRepository dataSourceRepository;
    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final IngestQueueService ingestQueueService;
    private final InboxService inboxService;
    private final AuditLogService auditLogService;
    private final DataImportLogService importLogService;

    public LocalDirectoryIngestJobService(final BackgroundJobRepository jobRepository,
                                          final LocalDirectoryDataSourceRepository dataSourceRepository,
                                          final BatchRepository batchRepository,
                                          final DocumentRepository documentRepository,
                                          final IngestQueueService ingestQueueService,
                                          final InboxService inboxService,
                                          final AuditLogService auditLogService,
                                          final DataImportLogService importLogService) {
        this.jobRepository = jobRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.ingestQueueService = ingestQueueService;
        this.inboxService = inboxService;
        this.auditLogService = auditLogService;
        this.importLogService = importLogService;
    }

    /**
     * Persist a PENDING job. Returns the saved job so the caller can redirect to the
     * Background Jobs page or include the id in a flash message.
     *
     * <p>The actual file walk is dispatched separately by {@code DataImportDispatcher},
     * which polls for PENDING data-import jobs and atomically promotes one per batch
     * to RUNNING.
     */
    public BackgroundJob start(final String sourceId, final String batchId, final int priority,
                               final String actorEmail) {
        final LocalDirectoryDataSource source = dataSourceRepository.findById(sourceId).orElse(null);
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (source == null) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Local directory data source not found.");
        }
        if (batch == null) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Batch not found.");
        }
        // Up-front directory check so the user gets immediate feedback rather than a
        // PENDING row that fails on the next dispatcher tick. The same check runs again
        // in run() because the directory could disappear between start and dispatch.
        final String pathError = validateDirectory(source.getDirectoryPath());
        if (pathError != null) {
            return failImmediate(sourceId, batchId, priority, actorEmail, pathError);
        }

        final BackgroundJob job = new BackgroundJob();
        job.setId(UUID.randomUUID().toString());
        job.setType(BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST);
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
        job.setType(BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST);
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

        final LocalDirectoryDataSource source = dataSourceRepository.findById(job.getSourceId()).orElse(null);
        final Batch batch = batchRepository.findById(job.getBatchId()).orElse(null);
        if (source == null || batch == null) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Source or batch was deleted before the job ran.");
            return;
        }

        final String pathError = validateDirectory(source.getDirectoryPath());
        if (pathError != null) {
            finish(job, BackgroundJob.STATUS_FAILED, pathError);
            return;
        }

        final Path root = Paths.get(source.getDirectoryPath()).toAbsolutePath().normalize();
        final String sourceIndex = root.toString();
        final PathMatcher matcher = compileGlob(source.getFilenameGlob());
        if (matcher == null) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Filename glob is missing or unparseable: \""
                            + (source.getFilenameGlob() == null ? "" : source.getFilenameGlob()) + "\".");
            return;
        }

        // Walk the directory once to find candidates. We materialize into a list so the
        // total count is known before any per-file work begins — that lets the Background
        // Jobs page show "X of Y" from the very first save.
        final List<Path> candidates;
        try {
            candidates = collectCandidates(root, matcher);
        } catch (IOException e) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Could not walk directory \"" + sourceIndex + "\": " + e.getMessage());
            return;
        }
        job.setTotalDocuments(candidates.size());
        jobRepository.save(job);

        try {
            for (Path file : candidates) {
                final String relative = root.relativize(file).toString();
                final String filename = file.getFileName() == null ? relative
                        : file.getFileName().toString();

                if (documentRepository.existsBySourceIndexAndSourceDocId(sourceIndex, relative)) {
                    recordSkipped(job, batch, filename, sourceIndex, relative);
                    importLogService.skipped(job.getId(), filename, relative);
                    continue;
                }

                try {
                    // The file's case metadata (#16), from its sidecar when it has one.
                    final Path sidecar = file.resolveSibling(CaseMetadataSidecar.sidecarFor(filename));
                    final ai.philterd.workspace.model.CaseMetadata caseMetadata = Files.isRegularFile(sidecar)
                            ? CaseMetadataSidecar.parse(Files.readAllBytes(sidecar)) : null;
                    final byte[] bytes = Files.readAllBytes(file);
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
                    doc.setSourceUrl(sourceIndex);
                    doc.setSourceIndex(sourceIndex);
                    doc.setSourceDocId(relative);
                    doc.setImportedAt(java.time.LocalDateTime.now());
                    documentRepository.save(doc);
                    auditDocumentImport(job, doc, "SUCCESS");
                    bumpProcessed(job);
                    importLogService.success(job.getId(), filename, relative);
                } catch (Exception e) {
                    final String reason = "Failed to read or enqueue \"" + relative + "\": "
                            + e.getMessage();
                    log.warn("Job {} ({} from {}): an item failed: {}", job.getId(), job.getType(), job.getSourceName(), ErrorClasses.of(e));
                    bumpFailed(job, reason);
                    importLogService.failed(job.getId(), filename, relative, e.getMessage());
                }
            }
            finish(job, BackgroundJob.STATUS_COMPLETED, null);
        } catch (Exception e) {
            log.warn("Job {} ({} from {}) failed: {}", job.getId(), job.getType(), job.getSourceName(), ErrorClasses.of(e));
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Could not run local-directory ingest: " + e.getMessage());
        }
    }

    /**
     * Returns {@code null} when the configured path is acceptable; otherwise a user-facing
     * error message describing exactly what's wrong (missing config, doesn't exist, isn't
     * a directory, isn't readable). Surfaced both at start time and on the dispatcher run
     * so a directory that disappears between the two still reports a clean error.
     */
    private static String validateDirectory(final String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return "Data source has no directory path configured.";
        }
        final Path path;
        try {
            path = Paths.get(configuredPath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return "Directory path is invalid: " + e.getMessage();
        }
        if (!Files.exists(path)) {
            return "Directory does not exist: " + path;
        }
        if (!Files.isDirectory(path)) {
            return "Configured path is not a directory: " + path;
        }
        if (!Files.isReadable(path)) {
            return "Directory is not readable by the application user: " + path;
        }
        return null;
    }

    /**
     * Compile the user-supplied glob. Returns {@code null} when the input is missing or
     * unparseable so the caller can surface a clear error rather than crash on
     * {@code FileSystem.getPathMatcher}.
     */
    static PathMatcher compileGlob(final String glob) {
        if (glob == null || glob.isBlank()) return null;
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Path> collectCandidates(final Path root, final PathMatcher matcher)
            throws IOException {
        final List<Path> matches = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            final var iterator = walk.iterator();
            while (iterator.hasNext() && matches.size() < MAX_FILES_PER_RUN) {
                final Path candidate = iterator.next();
                if (!Files.isRegularFile(candidate)) continue;
                // A case sidecar describes the file beside it and is never a document itself.
                if (candidate.getFileName() != null
                        && CaseMetadataSidecar.isSidecar(candidate.getFileName().toString())) continue;
                final Path relative = root.relativize(candidate);
                // Match against the relative path so "**/*.pdf" works for nested files
                // and "*.pdf" only matches files at the top level.
                if (matcher.matches(relative)) {
                    matches.add(candidate);
                }
            }
        }
        return matches;
    }

    private static boolean isPdf(final String filename) {
        if (filename == null) return false;
        return filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /**
     * Persist a SKIPPED placeholder Document row for a file that's already been imported
     * (matched by {@code (sourceIndex, sourceDocId)}). The row carries the source
     * attribution so the audit trail points back to the file, but no content — the
     * existing Document holds it.
     */
    private void recordSkipped(final BackgroundJob job, final Batch batch, final String filename,
                               final String sourceIndex, final String relative) {
        final ai.philterd.workspace.model.Document doc = new ai.philterd.workspace.model.Document();
        doc.setId(UUID.randomUUID().toString());
        doc.setBatchId(batch.getId());
        doc.setFilename(filename);
        doc.setOriginalText("");
        doc.setSourceSystem(SOURCE_SYSTEM);
        doc.setSourceUrl(sourceIndex);
        doc.setSourceIndex(sourceIndex);
        doc.setSourceDocId(relative);
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

    /**
     * Records a {@code DATA_IMPORT_STARTED} audit event when the dispatcher
     * promotes the job to RUNNING. Attribution goes to the user who queued
     * the job ({@link BackgroundJob#getCreatedBy()}); job-runner threads have
     * no SecurityContext so {@code logForUser} is required rather than
     * {@code log}.
     */
    private void auditJobStarted(final BackgroundJob job) {
        final java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("jobId", job.getId());
        details.put("type", BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST);
        details.put("sourceId", job.getSourceId() == null ? "" : job.getSourceId());
        details.put("sourceName", job.getSourceName() == null ? "" : job.getSourceName());
        details.put("batchId", job.getBatchId() == null ? "" : job.getBatchId());
        details.put("batchName", job.getBatchName() == null ? "" : job.getBatchName());
        auditLogService.logForUser(actor(job), "DATA_IMPORT_STARTED",
                "BackgroundJob", job.getId(),
                AuditLogService.OUTCOME_SUCCESS, details);
    }

    /**
     * Records a single {@code DOCUMENT_IMPORT} audit event per imported
     * document, including the import job ID and a link back to the source
     * (system + URL + index + source doc id) so an investigator can trace
     * any document on the queue back to the file it came from.
     */
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

    /**
     * Records the terminal state of the import job. Outcome reflects the
     * stored job status — SUCCESS for COMPLETED, FAILURE for FAILED — so a
     * single audit-log filter on {@code DATA_IMPORT_*} surfaces both the
     * happy path and the failures.
     */
    private void auditJobTerminal(final BackgroundJob job, final String error) {
        final boolean ok = BackgroundJob.STATUS_COMPLETED.equals(job.getStatus());
        final String action = ok ? "DATA_IMPORT_COMPLETED" : "DATA_IMPORT_FAILED";
        final java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("jobId", job.getId());
        details.put("type", BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST);
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
        sb.append("Local-directory import from \"").append(source).append("\" into batch \"").append(batch);
        if (ok) {
            sb.append("\" completed. Processed ").append(job.getProcessedDocuments());
            if (job.getTotalDocuments() >= 0) {
                sb.append(" of ").append(job.getTotalDocuments());
            }
            sb.append(" file(s)");
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
}
