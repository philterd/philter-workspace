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
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * A batch's name is unique within its group, so a team lead is never told about another group's batch by a name
 * being taken (#83). It was unique across all groups, through the index {@code name}, which is retired. The index
 * covers only batches with both a group and a name, so the two stores agree on a batch without a group: SQLite never
 * counts a missing value, and MongoDB would count it as null.
 */
@Document(collection = "batches")
@CompoundIndex(name = "group_name", def = "{'groupId': 1, 'name': 1}", unique = true,
        partialFilter = "{'groupId': {$exists: true}, 'name': {$exists: true}}")
@RetiredIndexes("name")
public class Batch {

    @Id
    private String id;

    private String name;
    private String description;
    private LocalDateTime createdAt;
    private String ownerId;
    private String groupId;
    private Map<String, Object> stats;
    private double confidenceThreshold = 0.8;
    private double documentThreshold = 0.25;
    private double auditSamplingRate = 0.10;
    /**
     * Approval rule sets configured on this batch. Rules within a set are AND-ed; the sets
     * themselves are OR-ed (any rule set whose rules all hold triggers dual approval).
     */
    private java.util.List<ApprovalRuleSet> approvalRuleSets = new java.util.ArrayList<>();

    // Legacy single-rule-set fields preserved for read-side migration of existing documents.
    // Newly saved batches write only to {@code approvalRuleSets}; on first read these fields
    // are folded into a synthetic rule set if the new field is empty.
    @Deprecated private java.util.Set<String> approvalRuleNames = new java.util.LinkedHashSet<>();
    @Deprecated private double riskScoreRuleThreshold = 0.9;
    @Deprecated private double rejectedConfidenceRuleThreshold = 0.95;
    @Deprecated private long experiencedReviewerRuleThreshold = 100;
    private Map<String, Integer> piiTypeWeights;
    private String weightSetId;
    private String policyName;
    /**
     * Whether each document's policy follows from its case type (#16), through the case-type mapping, instead of
     * {@link #policyName}. A document with no case type, or one the mapping does not name, is then not redacted.
     */
    private boolean policyByCaseType;
    private String domain;
    private String context = "";
    /**
     * Reference to a {@code FinalizationPolicy} that controls what happens to the
     * original document files when documents in this batch are finalized. Required
     * for new batches; pre-existing batches may be {@code null} until edited.
     */
    private String finalizationPolicyId;
    private String complianceProfileId;
    /**
     * Where finalized documents in this batch are delivered (#24): {@code LOCAL} or {@code S3} with that
     * destination's id, or both null for none.
     */
    private String destinationType;
    private String destinationId;
    /**
     * When {@code true}, accepting (APPROVING) a redaction in this batch requires the
     * reviewer to pick a Compliance Profile exemption code. When {@code false}, the
     * exemption-code prompt is suppressed even if the compliance profile defines codes.
     * Defaults to {@code true} so existing behaviour is preserved.
     */
    private boolean exemptionCodeRequired = true;
    /**
     * When {@code true}, a random sample of documents in this batch are flagged for a blind
     * second review. The sampled fraction is {@link #blindDoubleReviewPercentage} (0..100).
     * Both fields are write-once: they may be set when the batch is created and are never
     * mutable afterwards.
     */
    private boolean blindDoubleReviewEnabled;
    private int blindDoubleReviewPercentage = 10;
    private boolean closed;
    private LocalDateTime closedAt;
    private String closedBy;

    public Batch() {
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(final String ownerId) {
        this.ownerId = ownerId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(final String groupId) {
        this.groupId = groupId;
    }

    public Map<String, Object> getStats() {
        return stats;
    }

    public void setStats(final Map<String, Object> stats) {
        this.stats = stats;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(final double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public double getDocumentThreshold() {
        return documentThreshold;
    }

    public void setDocumentThreshold(final double documentThreshold) {
        this.documentThreshold = documentThreshold;
    }

    public double getAuditSamplingRate() {
        return auditSamplingRate;
    }

    public void setAuditSamplingRate(final double auditSamplingRate) {
        this.auditSamplingRate = auditSamplingRate;
    }

    public java.util.List<ApprovalRuleSet> getApprovalRuleSets() {
        if (approvalRuleSets == null) approvalRuleSets = new java.util.ArrayList<>();
        return approvalRuleSets;
    }

    public void setApprovalRuleSets(final java.util.List<ApprovalRuleSet> approvalRuleSets) {
        this.approvalRuleSets = approvalRuleSets == null
                ? new java.util.ArrayList<>() : approvalRuleSets;
    }

    /**
     * Effective rule sets at read time: returns {@link #getApprovalRuleSets()} when non-empty,
     * otherwise synthesizes a single rule set from the legacy single-set fields so old documents
     * keep evaluating identically until they're saved through the new UI.
     */
    public java.util.List<ApprovalRuleSet> effectiveRuleSets() {
        final java.util.List<ApprovalRuleSet> sets = getApprovalRuleSets();
        if (!sets.isEmpty()) return sets;
        if (approvalRuleNames == null || approvalRuleNames.isEmpty()) {
            return java.util.List.of();
        }
        final ApprovalRuleSet legacy = new ApprovalRuleSet();
        legacy.setId(id == null ? "legacy" : id + "-legacy");
        legacy.setRules(new java.util.LinkedHashSet<>(approvalRuleNames));
        legacy.setRiskScoreThreshold(riskScoreRuleThreshold);
        legacy.setRejectedConfidenceThreshold(rejectedConfidenceRuleThreshold);
        legacy.setExperiencedReviewerThreshold(experiencedReviewerRuleThreshold);
        return java.util.List.of(legacy);
    }

    @Deprecated
    public java.util.Set<String> getApprovalRuleNames() {
        if (approvalRuleNames == null) approvalRuleNames = new java.util.LinkedHashSet<>();
        return approvalRuleNames;
    }

    @Deprecated
    public void setApprovalRuleNames(final java.util.Set<String> approvalRuleNames) {
        this.approvalRuleNames = approvalRuleNames == null
                ? new java.util.LinkedHashSet<>() : approvalRuleNames;
    }

    @Deprecated public double getRiskScoreRuleThreshold() { return riskScoreRuleThreshold; }
    @Deprecated public void setRiskScoreRuleThreshold(final double v) { this.riskScoreRuleThreshold = v; }

    @Deprecated public double getRejectedConfidenceRuleThreshold() { return rejectedConfidenceRuleThreshold; }
    @Deprecated public void setRejectedConfidenceRuleThreshold(final double v) { this.rejectedConfidenceRuleThreshold = v; }

    @Deprecated public long getExperiencedReviewerRuleThreshold() { return experiencedReviewerRuleThreshold; }
    @Deprecated public void setExperiencedReviewerRuleThreshold(final long v) { this.experiencedReviewerRuleThreshold = v; }

    public Map<String, Integer> getPiiTypeWeights() {
        return piiTypeWeights;
    }

    public void setPiiTypeWeights(final Map<String, Integer> piiTypeWeights) {
        this.piiTypeWeights = piiTypeWeights;
    }

    public String getWeightSetId() {
        return weightSetId;
    }

    public void setWeightSetId(final String weightSetId) {
        this.weightSetId = weightSetId;
    }

    public String getPolicyName() {
        return policyName;
    }

    public String getContext() {
        return context == null ? "" : context;
    }

    public void setContext(final String context) {
        this.context = context == null ? "" : context;
    }

    public void setPolicyName(final String policyName) {
        this.policyName = policyName;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(final String domain) {
        this.domain = domain;
    }

    public String getFinalizationPolicyId() {
        return finalizationPolicyId;
    }

    public void setFinalizationPolicyId(final String finalizationPolicyId) {
        this.finalizationPolicyId = finalizationPolicyId;
    }

    public String getComplianceProfileId() { return complianceProfileId; }
    public void setComplianceProfileId(final String complianceProfileId) { this.complianceProfileId = complianceProfileId; }

    public boolean isExemptionCodeRequired() { return exemptionCodeRequired; }
    public void setExemptionCodeRequired(final boolean exemptionCodeRequired) {
        this.exemptionCodeRequired = exemptionCodeRequired;
    }

    public boolean isBlindDoubleReviewEnabled() {
        return blindDoubleReviewEnabled;
    }

    public void setBlindDoubleReviewEnabled(final boolean blindDoubleReviewEnabled) {
        this.blindDoubleReviewEnabled = blindDoubleReviewEnabled;
    }

    public int getBlindDoubleReviewPercentage() {
        return blindDoubleReviewPercentage;
    }

    public void setBlindDoubleReviewPercentage(final int blindDoubleReviewPercentage) {
        this.blindDoubleReviewPercentage = blindDoubleReviewPercentage;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(final boolean closed) {
        this.closed = closed;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(final LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(final String closedBy) {
        this.closedBy = closedBy;
    }

    public boolean isPolicyByCaseType() { return policyByCaseType; }
    public void setPolicyByCaseType(final boolean policyByCaseType) { this.policyByCaseType = policyByCaseType; }

    public String getDestinationType() { return destinationType; }
    public void setDestinationType(final String destinationType) { this.destinationType = destinationType; }

    public String getDestinationId() { return destinationId; }
    public void setDestinationId(final String destinationId) { this.destinationId = destinationId; }
}
