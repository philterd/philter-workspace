/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.api.controller;

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.service.ApprovalRuleEvaluator;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.service.BatchAccessService;
import ai.philterd.workspace.service.UserGroupsService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class TriageController {

    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final SpanRepository spanRepository;
    private final UserGroupsService userGroupsService;
    private final ApprovalRuleEvaluator approvalRuleEvaluator;
    private final BatchAccessService batchAccessService;

    private final ai.philterd.workspace.webapp.ui.BatchLabels batchLabels;
    private final ai.philterd.workspace.service.BlindDoubleReviewService blindReview;

    public TriageController(final DocumentRepository documentRepository,
                            final BatchRepository batchRepository,
                            final SpanRepository spanRepository,
                            final UserGroupsService userGroupsService,
                            final ApprovalRuleEvaluator approvalRuleEvaluator,
                            final BatchAccessService batchAccessService,
                            final ai.philterd.workspace.webapp.ui.BatchLabels batchLabels,
                            final ai.philterd.workspace.service.BlindDoubleReviewService blindReview) {
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.spanRepository = spanRepository;
        this.userGroupsService = userGroupsService;
        this.approvalRuleEvaluator = approvalRuleEvaluator;
        this.batchAccessService = batchAccessService;
        this.batchLabels = batchLabels;
        this.blindReview = blindReview;
    }

    private static final Set<String> SORTABLE_FIELDS = Set.of("riskScore", "status", "batchId", "filename", "priority");

    /**
     * Page size for the admin-side {@code GET /api/v1/batches} listing. Restricted callers
     * are scoped by group membership and read the matching rows directly, so this cap only
     * applies when an admin asks for the full list.
     */
    private static final int ADMIN_BATCH_LIST_LIMIT = 500;

    /**
     * Hard upper bound for the caller-supplied {@code size} parameter on {@code /queue}.
     * Without a cap, an authenticated user could request {@code ?size=Integer.MAX_VALUE}
     * and force Mongo to load a massive page into memory — a cheap DoS. Matches the
     * 100-row cap that {@code OpenSearchIndexService.search} enforces for the same reason.
     */
    static final int MAX_QUEUE_PAGE_SIZE = 100;

    @GetMapping("/queue")
    public Page<Map<String, Object>> getQueue(
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "10") final int size,
            @RequestParam(required = false) final String batchId,
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final String filename,
            @RequestParam(name = "myGroupsOnly", defaultValue = "false") final boolean myGroupsOnly,
            @RequestParam(name = "sort", defaultValue = "riskScore") final String sort,
            @RequestParam(name = "dir", defaultValue = "desc") final String dir,
            final Authentication authentication) {

        final String activeSort = SORTABLE_FIELDS.contains(sort) ? sort : "riskScore";
        final Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        final int safePage = Math.max(0, page);
        final int safeSize = Math.max(1, Math.min(MAX_QUEUE_PAGE_SIZE, size));
        final PageRequest pageRequest = PageRequest.of(safePage, safeSize, Sort.by(direction, activeSort));
        final boolean hasBatch = batchId != null && !batchId.isBlank();
        final boolean hasStatus = status != null && !status.isBlank();
        final String trimmedFilename = filename == null ? "" : filename.trim();
        final boolean hasFilename = !trimmedFilename.isEmpty();

        // Admins and auditors see everything; auditors are read-only but the queue
        // listing is itself a read so the same scope applies. Both roles can opt in
        // to the group-scoped view via myGroupsOnly=true.
        final boolean admin = AuthUtils.isAdminOrAuditor(authentication);
        final boolean restrict = !admin || myGroupsOnly;
        final Set<String> allowedBatchIds = restrict ? batchAccessService.allowedBatchIds(authentication) : null;

        if (restrict && allowedBatchIds.isEmpty()) {
            return new PageImpl<>(List.of(), pageRequest, 0);
        }
        if (hasBatch && restrict && !allowedBatchIds.contains(batchId)) {
            return new PageImpl<>(List.of(), pageRequest, 0);
        }

        Page<Document> documents;
        if (hasBatch && hasStatus && hasFilename) {
            documents = documentRepository.findByBatchIdAndStatusAndFilenameContainingIgnoreCase(
                    batchId, status, trimmedFilename, pageRequest);
        } else if (hasBatch && hasStatus) {
            documents = documentRepository.findByBatchIdAndStatus(batchId, status, pageRequest);
        } else if (hasBatch && hasFilename) {
            documents = documentRepository.findByBatchIdAndStatusNotInAndFilenameContainingIgnoreCase(
                    batchId, INGEST_QUEUE_STATUSES, trimmedFilename, pageRequest);
        } else if (hasBatch) {
            documents = documentRepository.findByBatchIdAndStatusNotIn(
                    batchId, INGEST_QUEUE_STATUSES, pageRequest);
        } else if (restrict && hasStatus && hasFilename) {
            documents = documentRepository.findByBatchIdInAndStatusAndFilenameContainingIgnoreCase(
                    allowedBatchIds, status, trimmedFilename, pageRequest);
        } else if (restrict && hasStatus) {
            documents = documentRepository.findByBatchIdInAndStatus(allowedBatchIds, status, pageRequest);
        } else if (restrict && hasFilename) {
            documents = documentRepository.findByBatchIdInAndStatusNotInAndFilenameContainingIgnoreCase(
                    allowedBatchIds, INGEST_QUEUE_STATUSES, trimmedFilename, pageRequest);
        } else if (restrict) {
            documents = documentRepository.findByBatchIdInAndStatusNotIn(
                    allowedBatchIds, INGEST_QUEUE_STATUSES, pageRequest);
        } else if (hasStatus && hasFilename) {
            documents = documentRepository.findByStatusAndFilenameContainingIgnoreCase(
                    status, trimmedFilename, pageRequest);
        } else if (hasStatus) {
            documents = documentRepository.findByStatus(status, pageRequest);
        } else if (hasFilename) {
            documents = documentRepository.findByStatusNotInAndFilenameContainingIgnoreCase(
                    INGEST_QUEUE_STATUSES, trimmedFilename, pageRequest);
        } else {
            documents = documentRepository.findByStatusNotIn(INGEST_QUEUE_STATUSES, pageRequest);
        }

        // Blind Double Review filter: APPROVED / REJECTED documents stay out of the
        // review queue unless they wait on the current user's blind second review, or on
        // their reconciling the two reviews (#86). Applied to the page
        // contents post-query. Pagination links keep the original totalElements so the
        // navigation footer stays consistent; the visible rows on a page may be fewer
        // than the page size when the filter takes effect, which is acceptable here.
        final List<Document> visible = documents.getContent().stream()
                .filter(doc -> isVisibleForBlindDoubleReview(doc, authentication))
                .toList();
        final Page<Document> filteredPage = new PageImpl<>(visible, pageRequest, documents.getTotalElements());

        final Set<String> batchIds = filteredPage.stream()
                .map(Document::getBatchId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        final Map<String, String> batchNames = new LinkedHashMap<>();
        final Map<String, Double> batchDocumentThresholds = new LinkedHashMap<>();
        final Map<String, Batch> batchesById = new LinkedHashMap<>();
        for (Batch b : batchRepository.findAllById(batchIds)) {
            batchNames.put(b.getId(), b.getName() == null ? b.getId() : b.getName());
            batchDocumentThresholds.put(b.getId(), b.getDocumentThreshold());
            batchesById.put(b.getId(), b);
        }

        return filteredPage.map(toRow(batchNames, batchDocumentThresholds, batchesById));
    }

    /**
     * Decide whether a document should appear on the review queue for the given user. APPROVED / REJECTED documents
     * stay hidden unless they were selected for blind double review (#86) and wait on this user: to start the blind
     * review (they have not been shown the document's spans), to finish the one they started, or to reconcile the
     * two reviews.
     */
    private boolean isVisibleForBlindDoubleReview(final Document doc, final Authentication authentication) {
        final String status = doc.getStatus();
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            return true;
        }
        if (!doc.isDoubleReview()) {
            return false;
        }
        return ai.philterd.workspace.service.BlindDoubleReviewService.isBlindReviewer(authentication, doc)
                || ai.philterd.workspace.service.BlindDoubleReviewService.mayStart(authentication, doc)
                || blindReview.mayReconcile(authentication, doc);
    }

    @GetMapping("/batches")
    public List<Map<String, String>> getBatches(
            @RequestParam(name = "myGroupsOnly", defaultValue = "false") final boolean myGroupsOnly,
            final Authentication authentication) {
        // Admins and auditors see all batches; auditors join the global view because
        // listing is a read.
        final boolean admin = AuthUtils.isAdminOrAuditor(authentication);
        final boolean restrict = !admin || myGroupsOnly;

        final List<Batch> batches;
        if (restrict) {
            // Restricted callers see only batches in their groups. The query is bounded by
            // group membership, so we don't need a row cap here.
            final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                    authentication == null ? null : authentication.getName());
            batches = myGroupIds.isEmpty() ? List.of()
                    : batchRepository.findByGroupIdIn(myGroupIds);
        } else {
            // Admin asking for everything — keep the paginated cap so the response stays
            // bounded in deployments with very many batches.
            batches = batchRepository.findAll(
                    PageRequest.of(0, ADMIN_BATCH_LIST_LIMIT, Sort.by("name"))).getContent();
        }

        // Names are unique only within a group (#83): label adds the group to a name two listed batches share.
        final Map<String, String> labels = batchLabels.of(batches);
        return batches.stream()
                .sorted(Comparator.comparing(
                        (Batch b) -> b.getName() == null ? "" : b.getName().toLowerCase()))
                .map(b -> {
                    final Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("id", b.getId());
                    entry.put("name", b.getName() == null ? b.getId() : b.getName());
                    entry.put("label", labels.get(b.getId()));
                    return entry;
                })
                .toList();
    }

    /** Statuses of documents not (yet) redacted, so their risk score was never computed. */
    private static final Set<String> UNSCORED_STATUSES = Set.of("PENDING", "PROCESSING", "FAILED", "SKIPPED");

    /**
     * Document statuses that mean "still in the ingest queue, not yet redacted." The Document
     * Queue page hides these whenever the user hasn't explicitly filtered by status — they
     * belong on the admin Ingest Queue page, not the reviewer-facing Documents view.
     */
    private static final Set<String> INGEST_QUEUE_STATUSES = Set.of("PENDING", "PROCESSING");

    private Function<Document, Map<String, Object>> toRow(final Map<String, String> batchNames,
                                                          final Map<String, Double> batchDocumentThresholds,
                                                          final Map<String, Batch> batchesById) {
        final LocalDateTime now = LocalDateTime.now();
        return doc -> {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", doc.getId());
            row.put("filename", doc.getFilename());
            row.put("status", doc.getStatus());
            row.put("riskScore", doc.getRiskScore());
            row.put("batchId", doc.getBatchId());
            row.put("batchName", batchNames.getOrDefault(doc.getBatchId(), doc.getBatchId()));
            // A risk indicator only: the batch's Document Threshold approves nothing, so the
            // status above is the document's real one. Unredacted documents have no score.
            final Double threshold = batchDocumentThresholds.get(doc.getBatchId());
            row.put("lowRisk", threshold != null && !UNSCORED_STATUSES.contains(doc.getStatus())
                    && doc.getRiskScore() <= threshold);
            row.put("documentThreshold", threshold);
            row.put("sourceAvailable", doc.getOriginalText() != null && !doc.getOriginalText().isEmpty());
            row.put("daysInQueue", doc.getCreatedAt() == null
                    ? null
                    : Math.max(0, Duration.between(doc.getCreatedAt(), now).toDays()));

            // Approval-rule status: how many approvals does this document need vs already has?
            // Only meaningful while the document is reachable for review; once APPROVED the
            // count is whatever the final tally was.
            final Batch batch = batchesById.get(doc.getBatchId());
            final int acquired = doc.getApprovedBy() == null ? 0 : doc.getApprovedBy().size();
            final int required;
            if ("APPROVED".equals(doc.getStatus())) {
                required = Math.max(1, acquired);
            } else if (batch == null) {
                required = 1;
            } else {
                final List<Span> spans = spanRepository.findByDocumentId(doc.getId());
                required = approvalRuleEvaluator.approvalsRequired(batch, doc, spans);
            }
            row.put("approvalsRequired", required);
            row.put("approvalsAcquired", acquired);

            // Pessimistic review-lock state for the padlock indicator. lockedByOther = true
            // means another user currently holds the lock; the row also surfaces who holds
            // it (lockedBy) and when the current expiry runs out (lockExpiresAt) for the
            // tooltip and break-lock affordance.
            final java.time.Instant nowInstant = java.time.Instant.now();
            final boolean lockActive = doc.isLocked(nowInstant);
            row.put("lockActive", lockActive);
            row.put("lockedBy", lockActive ? doc.getLockedBy() : null);
            row.put("lockExpiresAt", lockActive ? doc.getLockExpiresAt() : null);
            row.put("priority", doc.getPriority());
            return row;
        };
    }

}
