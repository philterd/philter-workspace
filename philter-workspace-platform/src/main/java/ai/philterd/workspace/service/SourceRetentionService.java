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

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.FinalizationPolicy;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.FinalizationPolicyRepository;
import ai.philterd.workspace.repository.PendingUploadRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Clears a finalized document's source as its batch's finalization policy says (#68): its original text, storage path,
 * spans (which hold the detected values), full-text index entry, and any copy kept for retrying redaction. The document
 * record, its redacted text (so Download still works), and its certificate of redaction are kept.
 *
 * <p>Delete immediately clears at finalization ({@link #clearSource}). Delete after X days and Delete after 48 hours
 * are cleared by {@link #clearDue}, a scheduled job that reads each batch's policy when it runs, so a batch moved to
 * Legal hold is not cleared, and that counts from when each document was finalized, so documents finalized while it
 * was not running are caught up.
 */
@Service
public class SourceRetentionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceRetentionService.class);

    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final PendingUploadRepository pendingUploadRepository;
    private final BatchRepository batchRepository;
    private final FinalizationPolicyRepository finalizationPolicyRepository;
    private final DocumentSearchService searchService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @Autowired
    public SourceRetentionService(final DocumentRepository documentRepository,
                                  final SpanRepository spanRepository,
                                  final PendingUploadRepository pendingUploadRepository,
                                  final BatchRepository batchRepository,
                                  final FinalizationPolicyRepository finalizationPolicyRepository,
                                  final DocumentSearchService searchService,
                                  final AuditLogService auditLogService) {
        this(documentRepository, spanRepository, pendingUploadRepository, batchRepository,
                finalizationPolicyRepository, searchService, auditLogService, Clock.systemUTC());
    }

    SourceRetentionService(final DocumentRepository documentRepository,
                           final SpanRepository spanRepository,
                           final PendingUploadRepository pendingUploadRepository,
                           final BatchRepository batchRepository,
                           final FinalizationPolicyRepository finalizationPolicyRepository,
                           final DocumentSearchService searchService,
                           final AuditLogService auditLogService,
                           final Clock clock) {
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.pendingUploadRepository = pendingUploadRepository;
        this.batchRepository = batchRepository;
        this.finalizationPolicyRepository = finalizationPolicyRepository;
        this.searchService = searchService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * How long after finalization {@code policy} keeps a document's source, or null when it never clears it (Legal
     * hold, or an option this does not know). Delete immediately is zero.
     */
    public static Duration retention(final FinalizationPolicy policy) {
        if (policy == null || policy.getOption() == null) return null;
        return switch (policy.getOption()) {
            case FinalizationPolicy.OPTION_DELETE_IMMEDIATELY -> Duration.ZERO;
            case FinalizationPolicy.OPTION_DELETE_AFTER_48H -> Duration.ofHours(48);
            case FinalizationPolicy.OPTION_DELETE_AFTER_X_DAYS -> days(policy.getDeleteAfterDays());
            default -> null;
        };
    }

    /** A positive number of days as a duration; null for none, or for more days than a duration holds. */
    private static Duration days(final long days) {
        if (days <= 0) return null;
        try {
            return Duration.ofDays(days);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * Clears {@code document}'s source under {@code policy} and audits it as {@code FINALIZATION_POLICY_APPLIED} with
     * {@code SOURCE_CLEARED}. The spans, pending upload, and index entry are deleted before the document is saved as
     * cleared, so a run that fails partway leaves the document uncleared, and the next run tries again. When two runs
     * reach the same document, deleting twice is harmless, and the second one's save fails on the document's version,
     * so it is audited once.
     *
     * @throws OptimisticLockingFailureException when the document changed since it was read
     */
    public void clearSource(final Document document, final FinalizationPolicy policy, final String actor) {
        final long spansDeleted = spanRepository.deleteByDocumentId(document.getId());
        // A copy kept for retrying redaction has the document's id; usually it is gone once redaction succeeded.
        pendingUploadRepository.deleteById(document.getId());
        // The full-text index holds the original text too.
        final boolean indexCleared = searchService.removeDocument(document.getId());
        document.setOriginalText(null);
        document.setStoragePath(null);
        document.setSourceClearedAt(clock.instant());
        documentRepository.save(document);
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyId", policy.getId() == null ? "" : policy.getId());
        details.put("policyName", policy.getName() == null ? "" : policy.getName());
        details.put("option", policy.getOption() == null ? "" : policy.getOption());
        details.put("action", "SOURCE_CLEARED");
        details.put("spansDeleted", spansDeleted);
        details.put("indexCleared", indexCleared);
        details.put("actor", actor == null ? "" : actor);
        auditLogService.log("FINALIZATION_POLICY_APPLIED", "Document", document.getId(), details);
    }

    /** Clears every finalized document whose batch's policy says its source is now due to go. Returns how many. */
    @Scheduled(fixedDelayString = "${philter-workspace.finalization.retention-poll-millis:900000}",
            initialDelayString = "${philter-workspace.finalization.retention-poll-millis:900000}")
    public int clearDue() {
        int cleared = 0;
        final Instant now = clock.instant();
        for (FinalizationPolicy policy : finalizationPolicyRepository.findAll()) {
            final Duration retention = retention(policy);
            // Immediately is cleared at finalization; Legal hold and unknown options never are.
            if (retention == null || retention.isZero()) continue;
            for (Batch batch : batchRepository.findByFinalizationPolicyId(policy.getId())) {
                for (Document document : documentRepository.findByBatchIdAndStatusAndSourceClearedAtIsNull(
                        batch.getId(), "FINALIZED")) {
                    final Instant finalized = finalizedAt(document);
                    if (finalized == null || finalized.plus(retention).isAfter(now)) continue;
                    try {
                        if (document.getOriginalText() == null && document.getStoragePath() == null) {
                            // Cleared by Delete immediately before sourceClearedAt was recorded: marked, and not
                            // audited a second time.
                            document.setSourceClearedAt(document.getFinalizedAt() != null
                                    ? document.getFinalizedAt() : now);
                            documentRepository.save(document);
                            continue;
                        }
                        clearSource(document, policy, "");
                        cleared++;
                    } catch (OptimisticLockingFailureException e) {
                        // Changed or cleared by someone else since it was read; the next run looks again.
                        LOGGER.debug("Document {} changed while its source was being cleared.", document.getId());
                    } catch (RuntimeException e) {
                        LOGGER.warn("Could not clear the source of document {}: {}", document.getId(),
                                ErrorClasses.of(e));
                    }
                }
            }
        }
        if (cleared > 0) {
            LOGGER.info("Cleared the source of {} finalized document(s) under their finalization policy.", cleared);
        }
        return cleared;
    }

    /**
     * When {@code document} was finalized: {@link Document#getFinalizedAt()}, or for a document finalized before that
     * was recorded, when its status last changed, which was to FINALIZED.
     */
    private static Instant finalizedAt(final Document document) {
        if (document.getFinalizedAt() != null) return document.getFinalizedAt();
        return document.getStatusChangedAt() == null ? null
                : document.getStatusChangedAt().atZone(ZoneId.systemDefault()).toInstant();
    }
}
