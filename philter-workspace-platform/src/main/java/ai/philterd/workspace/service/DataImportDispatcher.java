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
import ai.philterd.workspace.repository.BackgroundJobRepository;
import ai.philterd.workspace.store.BackgroundJobStore;
import ai.philterd.workspace.service.GeneralSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Promotes PENDING data-import jobs to RUNNING one batch at a time. Multiple PENDING
 * jobs may sit in the queue for the same batch — they execute in oldest-first order,
 * each waiting for the prior to finish.
 *
 * <p>The dispatcher runs on a periodic poll. For each PENDING data-import job
 * (oldest first) it attempts an atomic PENDING to RUNNING claim through
 * {@link BackgroundJobStore}. The {@code uniq_running_data_import_per_batch_v2} partial
 * unique index on {@link BackgroundJob} makes the store refuse the claim
 * when another RUNNING job already exists for that batch — the job simply remains
 * PENDING and the dispatcher tries again on the next tick. This pattern is
 * multi-replica safe: any number of replicas can run the dispatcher and the index
 * guarantees only one of them ever holds the running slot for a batch.
 */
@Service
public class DataImportDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DataImportDispatcher.class);

    private static final List<String> DATA_IMPORT_TYPES = List.of(
            BackgroundJob.TYPE_OPENSEARCH_INGEST,
            BackgroundJob.TYPE_ELASTICSEARCH_INGEST,
            BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST,
            BackgroundJob.TYPE_S3_INGEST,
            BackgroundJob.TYPE_RDB_INGEST);

    private final BackgroundJobRepository jobRepository;
    private final BackgroundJobStore jobStore;
    private final OpenSearchIngestJobService osService;
    private final ElasticsearchIngestJobService esService;
    private final LocalDirectoryIngestJobService localService;
    private final S3IngestJobService s3Service;
    private final RdbIngestJobService rdbService;
    private final GeneralSettingsService generalSettingsService;
    /**
     * Pool that runs claimed jobs. Sized to the upper bound of the admin-configurable
     * concurrency limit — the actual ceiling each tick is read from
     * {@link GeneralSettingsService#load()}.
     */
    private final ExecutorService workerPool;

    public DataImportDispatcher(final BackgroundJobRepository jobRepository,
                                final BackgroundJobStore jobStore,
                                final OpenSearchIngestJobService osService,
                                final ElasticsearchIngestJobService esService,
                                final LocalDirectoryIngestJobService localService,
                                final S3IngestJobService s3Service,
                                final RdbIngestJobService rdbService,
                                final GeneralSettingsService generalSettingsService) {
        this.jobRepository = jobRepository;
        this.jobStore = jobStore;
        this.osService = osService;
        this.esService = esService;
        this.localService = localService;
        this.s3Service = s3Service;
        this.rdbService = rdbService;
        this.generalSettingsService = generalSettingsService;
        this.workerPool = Executors.newFixedThreadPool(
                GeneralSettingsService.MAX_CONCURRENT_DATA_IMPORTS, r -> {
                    final Thread t = new Thread(r, "data-import-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    @Scheduled(fixedDelayString = "${philter-workspace.data-import.poll-millis:2000}",
            initialDelayString = "${philter-workspace.data-import.initial-delay-millis:5000}")
    public void dispatch() {
        final List<BackgroundJob> pending = jobRepository
                .findByStatusAndTypeInOrderByCreatedAtAsc(
                        BackgroundJob.STATUS_PENDING, DATA_IMPORT_TYPES);
        if (pending.isEmpty()) return;

        // The admin-controlled global ceiling on concurrent data-import jobs. Any
        // RUNNING jobs already in flight count against the budget. Counting in the store
        // (rather than an in-memory tally) keeps this multi-replica safe.
        final int limit = generalSettingsService.load().getMaxConcurrentDataImports();
        final long currentRunning = jobStore.countRunning(DATA_IMPORT_TYPES);
        int slotsRemaining = (int) Math.max(0L, limit - currentRunning);
        if (slotsRemaining == 0) return;

        // Track which batches we've claimed in this tick. Without this we'd retry
        // every PENDING for the same batch (each retry losing on the unique index).
        // Walking oldest-first means the first PENDING per batch wins this tick;
        // subsequent PENDINGs for that batch wait for the next tick after the
        // current run finishes.
        final Set<String> batchesClaimedThisTick = new HashSet<>();
        for (BackgroundJob candidate : pending) {
            if (slotsRemaining == 0) break;
            if (candidate.getBatchId() == null
                    || batchesClaimedThisTick.contains(candidate.getBatchId())) {
                continue;
            }
            final BackgroundJob claimed = tryClaim(candidate.getId());
            if (claimed != null) {
                batchesClaimedThisTick.add(claimed.getBatchId());
                slotsRemaining--;
                workerPool.submit(() -> runClaimed(claimed));
            }
        }
    }

    /**
     * Atomic PENDING→RUNNING flip. Returns the updated job on success, null when the
     * job has already been claimed by someone else or when the partial unique index
     * rejects the transition because another job is already RUNNING for the batch.
     */
    private BackgroundJob tryClaim(final String jobId) {
        final BackgroundJob claimed = jobStore.claimPending(jobId);
        if (claimed == null) {
            log.debug("Job {} was not claimed: already taken, or another import is RUNNING for the batch.", jobId);
        }
        return claimed;
    }

    private void runClaimed(final BackgroundJob job) {
        try {
            switch (job.getType() == null ? "" : job.getType()) {
                case BackgroundJob.TYPE_OPENSEARCH_INGEST -> osService.run(job.getId());
                case BackgroundJob.TYPE_ELASTICSEARCH_INGEST -> esService.run(job.getId());
                case BackgroundJob.TYPE_LOCAL_DIRECTORY_INGEST -> localService.run(job.getId());
                case BackgroundJob.TYPE_S3_INGEST -> s3Service.run(job.getId());
                case BackgroundJob.TYPE_RDB_INGEST -> rdbService.run(job.getId());
                default -> log.warn("DataImportDispatcher: unknown job type {} for job {}",
                        job.getType(), job.getId());
            }
        } catch (Exception e) {
            // Classes only: the exception can quote values from the source (#77).
            log.error("DataImportDispatcher: job {} ({} from {}) threw an unexpected exception: {}",
                    job.getId(), job.getType(), job.getSourceName(), ErrorClasses.of(e));
        }
    }
}
