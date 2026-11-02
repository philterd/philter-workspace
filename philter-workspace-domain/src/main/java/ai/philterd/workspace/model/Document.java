/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.Instant;
import java.time.LocalDateTime;

@org.springframework.data.mongodb.core.mapping.Document(collection = "documents")
public class Document {

    @Id
    private String id;

    /**
     * Optimistic-locking version. Auto-managed by Spring Data Mongo on every
     * {@code repository.save(...)} — incremented after a successful write, checked on
     * the next write. A stale {@code version} (because another thread saved in between)
     * raises {@code OptimisticLockingFailureException} and the in-memory mutation is
     * refused. Combined with the pessimistic lock in {@link
     * ai.philterd.workspace.service.DocumentLockService}, this prevents the
     * lost-update race where two reviewers race {@code approve} / {@code reject} and
     * the second {@code save} clobbers the first reviewer's {@code approvedBy} entry.
     * Null on freshly-built entities — Spring sets it to 0 on the first save.
     */
    @Version
    private Long version;

    @Indexed
    private String batchId;
    private String filename;
    private String storagePath;
    private String originalText;
    /**
     * Redacted text rendered at finalize time. Persisted so the Download button on the
     * Document Queue still works after a batch's finalization policy has cleared
     * {@link #originalText} and removed the spans.
     */
    private String redactedText;
    /**
     * SHA-512 hash (lowercase hex) of the document content as ingested. For text uploads this
     * is the hash of the UTF-8 bytes; for binary uploads (e.g. PDF) it is the hash of the raw
     * bytes received. Set once at ingest time and never updated thereafter, so it can be used
     * to detect duplicates and to attest the original content.
     */
    private String contentSha512;
    /**
     * Traceability for documents pulled from an external data source. {@code sourceSystem} is
     * the type of system the document came from (e.g. {@code OPENSEARCH}); {@code sourceUrl} is
     * the cluster/server URL; {@code sourceIndex} is the index/table/path the document lived
     * in; and {@code sourceDocId} is the system-specific document id. All four are {@code null}
     * for hand-uploaded documents.
     */
    private String sourceSystem;
    private String sourceUrl;
    private String sourceIndex;
    private String sourceDocId;
    /** When the document was imported from its external source. Null for direct uploads. */
    private LocalDateTime importedAt;
    @Indexed
    private String status;
    private double riskScore;
    private String philterContextId;
    /**
     * The document ID Philter recorded this document's redaction under (its {@code X-Document-Id}),
     * which is also the ID of its chain in Philter's ledger when the context keeps one.
     */
    private String philterDocumentId;
    /** Philter document IDs from earlier redactions of this document, oldest first. */
    private java.util.List<String> previousPhilterDocumentIds = new java.util.ArrayList<>();
    /** The filing's case metadata (#16), or null. Its case number and filer are encrypted at rest. */
    private CaseMetadata caseMetadata;
    /** The policy Philter last redacted this document with, as Philter reported it, and its version. */
    private String appliedPolicyName;
    private Integer appliedPolicyVersion;
    private LocalDateTime createdAt;
    private LocalDateTime statusChangedAt;
    private String failureMessage;
    /**
     * Delivery of the finalized output to the batch's destination (#24): null when the batch has none, otherwise
     * PENDING, DELIVERED, or FAILED. The finalized output stays on the document whatever happens to the delivery.
     */
    @Indexed
    private String deliveryStatus;
    private int deliveryAttempts;
    private String deliveryError;
    /** The destination as "type: name", recorded when the delivery was queued. */
    private String deliveryDestination;
    private Instant deliveryNextAttemptAt;
    private Instant deliveredAt;
    /** When the document was finalized. A delayed finalization policy counts from here (#68). */
    private Instant finalizedAt;
    /**
     * When a finalization policy cleared the original text, spans, and index entry, or null while they are kept
     * (#68).
     */
    private Instant sourceClearedAt;
    /** Who finalized the document, or asked for the delivery to be retried. */
    private String deliveryRequestedBy;
    /**
     * Emails of reviewers who have approved this document, in order of first → last approval.
     * Used to count progress against the batch's approval-rule requirement and to enforce that
     * the same reviewer cannot approve a document twice.
     */
    private java.util.List<String> approvedBy = new java.util.ArrayList<>();

    /**
     * Stable random number in [0, 1) generated once during persistence. Used by the
     * dual-approval sampling rule so the same document keeps the same sampling decision
     * across reads. {@code null} means the document predates the sampling roll — the rule
     * conservatively treats it as not sampled in.
     */
    private Double dualApprovalSamplingRoll;

    /**
     * Pessimistic-review lock fields. {@code lockExpiresAt} sliding-expires the lock so a
     * reviewer who walks away without explicitly closing the document automatically frees
     * it. The lock is acquired/extended via atomic {@code findAndModify} updates so two
     * reviewers can't grab it at the same millisecond.
     */
    private String lockedBy;
    private Instant lockedAt;
    private Instant lockExpiresAt;

    /** 1 = Low, 2 = Normal, 3 = High. Defaults to Normal (2). */
    private int priority = 2;

    /**
     * Set at ingest time when the parent batch has Blind Double Review enabled and this document
     * was randomly drawn into the sample. Persisted so the same document keeps the same selection
     * decision across reads. Stays {@code false} for batches with the feature disabled or for
     * documents that were not selected.
     */
    private boolean doubleReview;

    /**
     * Email of the reviewer whose approval or rejection first transitioned the document out of
     * {@code REVIEW_REQUIRED} / {@code AUDIT_REQUIRED}. Set once and never overwritten so the
     * blind-double-review filter can identify which user is disqualified from the second pass.
     * {@code null} for documents that have not yet been reviewed.
     */
    private String firstReviewer;

    /**
     * Snapshot of approved PII span ranges (each entry is {@code [start, end]} in document
     * character offsets) at the moment the first reviewer completed their review. Captured for
     * blind-double-review documents so an Inter-Annotator Agreement (Cohen's Kappa) report can
     * compare each reviewer's view independently.
     */
    private java.util.List<int[]> firstReviewSpans;

    /**
     * Email of the second reviewer (the one who provided the blind double review). Distinct from
     * {@link #firstReviewer}; set the first time a different reviewer reviews a double-review-
     * flagged document.
     */
    private String secondReviewer;

    /**
     * Snapshot of approved PII span ranges captured at the moment the second reviewer completed
     * their review. Pairs with {@link #firstReviewSpans}. Set means the second review is recorded.
     */
    private java.util.List<int[]> secondReviewSpans;

    /** Philter's detections as they arrived, for the blind second review to start from (#86). */
    private java.util.List<DetectedSpan> doubleReviewDetections;

    /**
     * When {@link #secondReviewer} started the blind second review (#86). While a second reviewer is set and
     * {@link #secondReviewSpans} is not, the review is in progress and only they work on it.
     */
    private Instant secondReviewStartedAt;
    private Instant secondReviewSubmittedAt;

    /**
     * Everyone shown the document's spans before the second review was recorded (#86). None of them can do the
     * blind second review, which must not have seen the first.
     */
    private java.util.List<String> firstReviewSeenBy = new java.util.ArrayList<>();

    /** The approver or administrator who reconciled the two reviews, and when (#86). */
    private String doubleReviewReconciledBy;
    private Instant doubleReviewReconciledAt;

    public Document() {
    }

    public int getPriority() { return priority; }
    public void setPriority(final int priority) { this.priority = priority; }

    public boolean isDoubleReview() { return doubleReview; }
    public void setDoubleReview(final boolean doubleReview) { this.doubleReview = doubleReview; }

    public String getFirstReviewer() { return firstReviewer; }
    public void setFirstReviewer(final String firstReviewer) { this.firstReviewer = firstReviewer; }

    public java.util.List<int[]> getFirstReviewSpans() { return firstReviewSpans; }
    public void setFirstReviewSpans(final java.util.List<int[]> firstReviewSpans) {
        this.firstReviewSpans = firstReviewSpans;
    }

    public String getSecondReviewer() { return secondReviewer; }
    public void setSecondReviewer(final String secondReviewer) { this.secondReviewer = secondReviewer; }

    public java.util.List<int[]> getSecondReviewSpans() { return secondReviewSpans; }
    public void setSecondReviewSpans(final java.util.List<int[]> secondReviewSpans) {
        this.secondReviewSpans = secondReviewSpans;
    }

    public java.util.List<DetectedSpan> getDoubleReviewDetections() { return doubleReviewDetections; }
    public void setDoubleReviewDetections(final java.util.List<DetectedSpan> doubleReviewDetections) {
        this.doubleReviewDetections = doubleReviewDetections;
    }

    public Instant getSecondReviewStartedAt() { return secondReviewStartedAt; }
    public void setSecondReviewStartedAt(final Instant secondReviewStartedAt) {
        this.secondReviewStartedAt = secondReviewStartedAt;
    }

    public Instant getSecondReviewSubmittedAt() { return secondReviewSubmittedAt; }
    public void setSecondReviewSubmittedAt(final Instant secondReviewSubmittedAt) {
        this.secondReviewSubmittedAt = secondReviewSubmittedAt;
    }

    public java.util.List<String> getFirstReviewSeenBy() {
        if (firstReviewSeenBy == null) firstReviewSeenBy = new java.util.ArrayList<>();
        return firstReviewSeenBy;
    }
    public void setFirstReviewSeenBy(final java.util.List<String> firstReviewSeenBy) {
        this.firstReviewSeenBy = firstReviewSeenBy;
    }

    public String getDoubleReviewReconciledBy() { return doubleReviewReconciledBy; }
    public void setDoubleReviewReconciledBy(final String doubleReviewReconciledBy) {
        this.doubleReviewReconciledBy = doubleReviewReconciledBy;
    }

    public Instant getDoubleReviewReconciledAt() { return doubleReviewReconciledAt; }
    public void setDoubleReviewReconciledAt(final Instant doubleReviewReconciledAt) {
        this.doubleReviewReconciledAt = doubleReviewReconciledAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStatusChangedAt() {
        return statusChangedAt;
    }

    public void setStatusChangedAt(final LocalDateTime statusChangedAt) {
        this.statusChangedAt = statusChangedAt;
    }

    /** Set the status and its change timestamp atomically. */
    public void changeStatus(final String newStatus) {
        this.status = newStatus;
        this.statusChangedAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public Long getVersion() { return version; }
    public void setVersion(final Long version) { this.version = version; }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(final String batchId) {
        this.batchId = batchId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(final String filename) {
        this.filename = filename;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(final String storagePath) {
        this.storagePath = storagePath;
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(final String originalText) {
        this.originalText = originalText;
    }

    public String getRedactedText() {
        return redactedText;
    }

    public void setRedactedText(final String redactedText) {
        this.redactedText = redactedText;
    }

    public String getContentSha512() {
        return contentSha512;
    }

    public void setContentSha512(final String contentSha512) {
        this.contentSha512 = contentSha512;
    }

    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(final String sourceSystem) { this.sourceSystem = sourceSystem; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(final String sourceUrl) { this.sourceUrl = sourceUrl; }

    public String getSourceIndex() { return sourceIndex; }
    public void setSourceIndex(final String sourceIndex) { this.sourceIndex = sourceIndex; }

    public String getSourceDocId() { return sourceDocId; }
    public void setSourceDocId(final String sourceDocId) { this.sourceDocId = sourceDocId; }

    public LocalDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(final LocalDateTime importedAt) { this.importedAt = importedAt; }

    public String getStatus() {
        return status;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    public double getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(final double riskScore) {
        this.riskScore = riskScore;
    }

    public String getPhilterContextId() {
        return philterContextId;
    }

    public void setPhilterContextId(final String philterContextId) {
        this.philterContextId = philterContextId;
    }

    public String getPhilterDocumentId() {
        return philterDocumentId;
    }

    public void setPhilterDocumentId(final String philterDocumentId) {
        this.philterDocumentId = philterDocumentId;
    }

    public java.util.List<String> getPreviousPhilterDocumentIds() {
        return previousPhilterDocumentIds;
    }

    public void setPreviousPhilterDocumentIds(final java.util.List<String> previousPhilterDocumentIds) {
        this.previousPhilterDocumentIds = previousPhilterDocumentIds;
    }

    /** Record the ID of a new redaction by Philter, keeping the previous one. */
    public void recordPhilterDocumentId(final String documentId) {
        if (documentId == null || documentId.isBlank() || documentId.equals(philterDocumentId)) {
            return;
        }
        if (philterDocumentId != null) {
            if (previousPhilterDocumentIds == null) {
                previousPhilterDocumentIds = new java.util.ArrayList<>();
            }
            previousPhilterDocumentIds.add(philterDocumentId);
        }
        philterDocumentId = documentId;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(final String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public java.util.List<String> getApprovedBy() {
        if (approvedBy == null) approvedBy = new java.util.ArrayList<>();
        return approvedBy;
    }

    public void setApprovedBy(final java.util.List<String> approvedBy) {
        this.approvedBy = approvedBy == null ? new java.util.ArrayList<>() : approvedBy;
    }

    public Double getDualApprovalSamplingRoll() { return dualApprovalSamplingRoll; }
    public void setDualApprovalSamplingRoll(final Double dualApprovalSamplingRoll) {
        this.dualApprovalSamplingRoll = dualApprovalSamplingRoll;
    }

    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(final String lockedBy) { this.lockedBy = lockedBy; }

    public Instant getLockedAt() { return lockedAt; }
    public void setLockedAt(final Instant lockedAt) { this.lockedAt = lockedAt; }

    public Instant getLockExpiresAt() { return lockExpiresAt; }
    public void setLockExpiresAt(final Instant lockExpiresAt) { this.lockExpiresAt = lockExpiresAt; }

    /** A lock held in the past or unset means no active lock. */
    public boolean isLocked(final Instant now) {
        return lockExpiresAt != null && lockedBy != null && lockExpiresAt.isAfter(now);
    }

    public CaseMetadata getCaseMetadata() { return caseMetadata; }
    public void setCaseMetadata(final CaseMetadata caseMetadata) { this.caseMetadata = caseMetadata; }

    public String getAppliedPolicyName() { return appliedPolicyName; }
    public void setAppliedPolicyName(final String appliedPolicyName) { this.appliedPolicyName = appliedPolicyName; }

    public Integer getAppliedPolicyVersion() { return appliedPolicyVersion; }
    public void setAppliedPolicyVersion(final Integer appliedPolicyVersion) { this.appliedPolicyVersion = appliedPolicyVersion; }

    public String getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(final String deliveryStatus) { this.deliveryStatus = deliveryStatus; }

    public int getDeliveryAttempts() { return deliveryAttempts; }
    public void setDeliveryAttempts(final int deliveryAttempts) { this.deliveryAttempts = deliveryAttempts; }

    public String getDeliveryError() { return deliveryError; }
    public void setDeliveryError(final String deliveryError) { this.deliveryError = deliveryError; }

    public String getDeliveryDestination() { return deliveryDestination; }
    public void setDeliveryDestination(final String deliveryDestination) { this.deliveryDestination = deliveryDestination; }

    public Instant getDeliveryNextAttemptAt() { return deliveryNextAttemptAt; }
    public void setDeliveryNextAttemptAt(final Instant deliveryNextAttemptAt) { this.deliveryNextAttemptAt = deliveryNextAttemptAt; }

    public Instant getFinalizedAt() { return finalizedAt; }
    public void setFinalizedAt(final Instant finalizedAt) { this.finalizedAt = finalizedAt; }

    public Instant getSourceClearedAt() { return sourceClearedAt; }
    public void setSourceClearedAt(final Instant sourceClearedAt) { this.sourceClearedAt = sourceClearedAt; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(final Instant deliveredAt) { this.deliveredAt = deliveredAt; }

    public String getDeliveryRequestedBy() { return deliveryRequestedBy; }
    public void setDeliveryRequestedBy(final String deliveryRequestedBy) { this.deliveryRequestedBy = deliveryRequestedBy; }
}
