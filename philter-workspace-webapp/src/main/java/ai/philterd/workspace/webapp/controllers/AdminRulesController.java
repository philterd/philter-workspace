/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.ApprovalRule;
import ai.philterd.workspace.model.ApprovalRuleSet;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.service.AuditLogService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/admin/rules")
public class AdminRulesController {

    private final BatchRepository batchRepository;
    private final AuditLogService auditLogService;
    private final ai.philterd.workspace.webapp.ui.BatchLabels batchLabels;

    public AdminRulesController(final BatchRepository batchRepository,
                                final AuditLogService auditLogService,
                                final ai.philterd.workspace.webapp.ui.BatchLabels batchLabels) {
        this.batchRepository = batchRepository;
        this.auditLogService = auditLogService;
        this.batchLabels = batchLabels;
    }

    @GetMapping
    public String view(@org.springframework.web.bind.annotation.RequestParam(name = "dir", defaultValue = "asc") final String dir,
                       final Model model) {
        final boolean ascending = !"desc".equalsIgnoreCase(dir);
        final List<Batch> batches = batchRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();

        // Names are unique only within a group (#83), so a name shared by two groups' batches shows the group.
        final Map<String, String> batchNames = batchLabels.of(batches);

        // Available batches: any batch can have a new rule set added (zero or more allowed now).
        final List<Map<String, Object>> available = new ArrayList<>();
        for (Batch b : batches) {
            if (b.isClosed()) continue;
            available.add(Map.of(
                    "id", b.getId(),
                    "name", batchNames.get(b.getId())));
        }

        // One row per (batch, rule set). Rows sort by batch name first to keep the table grouped.
        final Map<String, String> labels = ApprovalRule.labels();
        final List<Map<String, Object>> existing = new ArrayList<>();
        for (Batch b : batches) {
            for (ApprovalRuleSet set : b.effectiveRuleSets()) {
                final List<String> ruleLabelList = new ArrayList<>();
                for (Map.Entry<String, String> entry : labels.entrySet()) {
                    if (set.getRules().contains(entry.getKey())) ruleLabelList.add(entry.getValue());
                }
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("batchId", b.getId());
                row.put("batchName", batchNames.get(b.getId()));
                row.put("batchClosed", b.isClosed());
                row.put("ruleSetId", set.getId());
                row.put("rules", set.getRules());
                row.put("ruleLabels", ruleLabelList);
                row.put("riskScoreThreshold", set.getRiskScoreThreshold());
                row.put("rejectedConfidenceThreshold", set.getRejectedConfidenceThreshold());
                row.put("experiencedReviewerThreshold", set.getExperiencedReviewerThreshold());
                row.put("manualSpansThreshold", set.getManualSpansThreshold());
                row.put("classifiedKeywords", String.join(", ", set.getClassifiedKeywords()));
                row.put("dualApprovalSamplingRate", set.getDualApprovalSamplingRate());
                row.put("requireApprover", set.isRequireApprover());
                existing.add(row);
            }
        }

        // Sort by batch name in the requested direction; ties retain rule-set insertion order
        // since the loop above appends rule sets per batch in their stored order.
        if (!ascending) {
            existing.sort(Comparator.comparing(
                    (Map<String, Object> r) -> ((String) r.get("batchName")) == null
                            ? "" : ((String) r.get("batchName")).toLowerCase())
                    .reversed());
        }
        model.addAttribute("existing", existing);
        model.addAttribute("currentDir", ascending ? "asc" : "desc");
        model.addAttribute("available", available);
        model.addAttribute("ruleLabels", labels);
        model.addAttribute("highRiskScoreRule", ApprovalRule.HIGH_RISK_SCORE);
        model.addAttribute("rejectedHighConfidenceRule", ApprovalRule.REJECTED_HIGH_CONFIDENCE);
        model.addAttribute("inexperiencedReviewerRule", ApprovalRule.INEXPERIENCED_REVIEWER);
        model.addAttribute("manualSpansThresholdRule", ApprovalRule.MANUAL_SPANS_THRESHOLD);
        model.addAttribute("classifiedKeywordsRule", ApprovalRule.CLASSIFIED_KEYWORDS);
        model.addAttribute("dualApprovalSamplingRateRule", ApprovalRule.DUAL_APPROVAL_SAMPLING_RATE);
        model.addAttribute("defaultRiskScoreThreshold", ApprovalRule.HIGH_RISK_THRESHOLD);
        model.addAttribute("defaultRejectedConfidenceThreshold", ApprovalRule.HIGH_CONFIDENCE_THRESHOLD);
        model.addAttribute("defaultExperiencedReviewerThreshold", ApprovalRule.EXPERIENCED_REVIEWER_REVIEWS);
        model.addAttribute("defaultManualSpansThreshold", ApprovalRule.DEFAULT_MANUAL_SPANS_THRESHOLD);
        model.addAttribute("defaultClassifiedKeywords",
                String.join(", ", ApprovalRule.DEFAULT_CLASSIFIED_KEYWORDS));
        model.addAttribute("defaultDualApprovalSamplingRate", ApprovalRule.DEFAULT_DUAL_APPROVAL_SAMPLING_RATE);
        return "admin-rules";
    }

    @PostMapping
    public String create(@RequestParam("batchId") final String batchId,
                         @RequestParam(value = "rule", required = false) final List<String> selected,
                         @RequestParam(value = "riskScoreThreshold", required = false) final Double riskScoreThreshold,
                         @RequestParam(value = "rejectedConfidenceThreshold", required = false) final Double rejectedConfidenceThreshold,
                         @RequestParam(value = "experiencedReviewerThreshold", required = false) final Long experiencedReviewerThreshold,
                         @RequestParam(value = "manualSpansThreshold", required = false) final Long manualSpansThreshold,
                         @RequestParam(value = "classifiedKeywords", required = false) final String classifiedKeywords,
                         @RequestParam(value = "dualApprovalSamplingRate", required = false) final Double dualApprovalSamplingRate,
                         @RequestParam(value = "requireApprover", defaultValue = "false") final boolean requireApprover,
                         final Authentication authentication,
                         final RedirectAttributes redirectAttributes) {
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/admin/rules";
        }

        final ApprovalRuleSet set;
        try {
            set = buildRuleSet(null, selected, riskScoreThreshold,
                    rejectedConfidenceThreshold, experiencedReviewerThreshold,
                    manualSpansThreshold, classifiedKeywords, dualApprovalSamplingRate);
            set.setRequireApprover(requireApprover);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/rules";
        }

        // Migrate any legacy single-rule-set into the list before appending so we never lose data.
        final List<ApprovalRuleSet> sets = new ArrayList<>(batch.effectiveRuleSets());
        sets.add(set);
        batch.setApprovalRuleSets(sets);
        // Clear legacy fields now that the data is canonical in approvalRuleSets.
        batch.setApprovalRuleNames(new LinkedHashSet<>());
        batchRepository.save(batch);

        auditLogService.log("BATCH_RULES_ADD", "Batch", batch.getId(),
                Map.of(
                        "name", batch.getName() == null ? "" : batch.getName(),
                        "ruleSetId", set.getId(),
                        "rules", set.getRules(),
                        "riskScoreThreshold", set.getRiskScoreThreshold(),
                        "rejectedConfidenceThreshold", set.getRejectedConfidenceThreshold(),
                        "experiencedReviewerThreshold", set.getExperiencedReviewerThreshold(),
                        "requireApprover", set.isRequireApprover(),
                        "addedBy", authentication == null ? "unknown" : authentication.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Rule set added to \"" + batch.getName() + "\".");
        return "redirect:/admin/rules";
    }

    @PostMapping("/{batchId}/{ruleSetId}")
    public String update(@PathVariable final String batchId,
                         @PathVariable final String ruleSetId,
                         @RequestParam(value = "rule", required = false) final List<String> selected,
                         @RequestParam(value = "riskScoreThreshold", required = false) final Double riskScoreThreshold,
                         @RequestParam(value = "rejectedConfidenceThreshold", required = false) final Double rejectedConfidenceThreshold,
                         @RequestParam(value = "experiencedReviewerThreshold", required = false) final Long experiencedReviewerThreshold,
                         @RequestParam(value = "manualSpansThreshold", required = false) final Long manualSpansThreshold,
                         @RequestParam(value = "classifiedKeywords", required = false) final String classifiedKeywords,
                         @RequestParam(value = "dualApprovalSamplingRate", required = false) final Double dualApprovalSamplingRate,
                         @RequestParam(value = "requireApprover", defaultValue = "false") final boolean requireApprover,
                         final Authentication authentication,
                         final RedirectAttributes redirectAttributes) {
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/admin/rules";
        }
        final List<ApprovalRuleSet> sets = new ArrayList<>(batch.effectiveRuleSets());
        final int idx = indexOfRuleSet(sets, ruleSetId);
        if (idx < 0) {
            redirectAttributes.addFlashAttribute("error", "Rule set not found.");
            return "redirect:/admin/rules";
        }

        final ApprovalRuleSet updated;
        try {
            updated = buildRuleSet(ruleSetId, selected, riskScoreThreshold,
                    rejectedConfidenceThreshold, experiencedReviewerThreshold,
                    manualSpansThreshold, classifiedKeywords, dualApprovalSamplingRate);
            updated.setRequireApprover(requireApprover);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/rules";
        }
        sets.set(idx, updated);
        batch.setApprovalRuleSets(sets);
        batch.setApprovalRuleNames(new LinkedHashSet<>());
        batchRepository.save(batch);

        auditLogService.log("BATCH_RULES_UPDATE", "Batch", batch.getId(),
                Map.of(
                        "name", batch.getName() == null ? "" : batch.getName(),
                        "ruleSetId", updated.getId(),
                        "rules", updated.getRules(),
                        "riskScoreThreshold", updated.getRiskScoreThreshold(),
                        "rejectedConfidenceThreshold", updated.getRejectedConfidenceThreshold(),
                        "experiencedReviewerThreshold", updated.getExperiencedReviewerThreshold(),
                        "requireApprover", updated.isRequireApprover(),
                        "changedBy", authentication == null ? "unknown" : authentication.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Rule set updated for \"" + batch.getName() + "\".");
        return "redirect:/admin/rules";
    }

    @PostMapping("/{batchId}/{ruleSetId}/delete")
    public String delete(@PathVariable final String batchId,
                         @PathVariable final String ruleSetId,
                         final Authentication authentication,
                         final RedirectAttributes redirectAttributes) {
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/admin/rules";
        }
        final List<ApprovalRuleSet> sets = new ArrayList<>(batch.effectiveRuleSets());
        final int idx = indexOfRuleSet(sets, ruleSetId);
        if (idx < 0) {
            redirectAttributes.addFlashAttribute("error", "Rule set not found.");
            return "redirect:/admin/rules";
        }
        final ApprovalRuleSet removed = sets.remove(idx);
        batch.setApprovalRuleSets(sets);
        batch.setApprovalRuleNames(new LinkedHashSet<>());
        batchRepository.save(batch);

        auditLogService.log("BATCH_RULES_REMOVE", "Batch", batch.getId(),
                Map.of(
                        "name", batch.getName() == null ? "" : batch.getName(),
                        "ruleSetId", removed.getId() == null ? "" : removed.getId(),
                        "rules", removed.getRules(),
                        "removedBy", authentication == null ? "unknown" : authentication.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Rule set removed from \"" + batch.getName() + "\".");
        return "redirect:/admin/rules";
    }

    private static int indexOfRuleSet(final List<ApprovalRuleSet> sets, final String id) {
        if (id == null) return -1;
        for (int i = 0; i < sets.size(); i++) {
            if (id.equals(sets.get(i).getId())) return i;
        }
        return -1;
    }

    /** Validate the form input and produce a fully-populated rule set or throw with a user message. */
    private static ApprovalRuleSet buildRuleSet(final String existingId,
                                                final List<String> selected,
                                                final Double riskScoreThreshold,
                                                final Double rejectedConfidenceThreshold,
                                                final Long experiencedReviewerThreshold,
                                                final Long manualSpansThreshold,
                                                final String classifiedKeywords,
                                                final Double dualApprovalSamplingRate) {
        final Set<String> normalized = new LinkedHashSet<>();
        if (selected != null) {
            for (String name : selected) {
                if (name == null) continue;
                final String trimmed = name.trim();
                if (ApprovalRule.isValid(trimmed)) normalized.add(trimmed);
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Pick at least one condition. Use Remove to delete a rule set.");
        }
        final ApprovalRuleSet set = new ApprovalRuleSet();
        set.setId(existingId == null ? UUID.randomUUID().toString() : existingId);
        set.setRules(normalized);

        if (normalized.contains(ApprovalRule.HIGH_RISK_SCORE)) {
            if (riskScoreThreshold == null
                    || Double.isNaN(riskScoreThreshold)
                    || riskScoreThreshold < 0.0 || riskScoreThreshold > 1.0) {
                throw new IllegalArgumentException(
                        "Risk-score threshold is required and must be between 0 and 1.");
            }
            set.setRiskScoreThreshold(riskScoreThreshold);
        }
        if (normalized.contains(ApprovalRule.REJECTED_HIGH_CONFIDENCE)) {
            if (rejectedConfidenceThreshold == null
                    || Double.isNaN(rejectedConfidenceThreshold)
                    || rejectedConfidenceThreshold < 0.0 || rejectedConfidenceThreshold > 1.0) {
                throw new IllegalArgumentException(
                        "Confidence threshold is required and must be between 0 and 1.");
            }
            set.setRejectedConfidenceThreshold(rejectedConfidenceThreshold);
        }
        if (normalized.contains(ApprovalRule.INEXPERIENCED_REVIEWER)) {
            if (experiencedReviewerThreshold == null || experiencedReviewerThreshold < 0) {
                throw new IllegalArgumentException(
                        "Reviewer-experience threshold is required and must be 0 or greater.");
            }
            set.setExperiencedReviewerThreshold(experiencedReviewerThreshold);
        }
        if (normalized.contains(ApprovalRule.MANUAL_SPANS_THRESHOLD)) {
            if (manualSpansThreshold == null || manualSpansThreshold < 0) {
                throw new IllegalArgumentException(
                        "Manual-redactions threshold is required and must be 0 or greater.");
            }
            set.setManualSpansThreshold(manualSpansThreshold);
        }
        if (normalized.contains(ApprovalRule.CLASSIFIED_KEYWORDS)) {
            final java.util.List<String> keywords = new java.util.ArrayList<>();
            if (classifiedKeywords != null) {
                for (String token : classifiedKeywords.split(",")) {
                    final String trimmed = token == null ? "" : token.trim();
                    if (!trimmed.isEmpty()) keywords.add(trimmed);
                }
            }
            if (keywords.isEmpty()) {
                throw new IllegalArgumentException(
                        "At least one classified keyword is required when the keyword rule is selected.");
            }
            set.setClassifiedKeywords(keywords);
        }
        if (normalized.contains(ApprovalRule.DUAL_APPROVAL_SAMPLING_RATE)) {
            if (dualApprovalSamplingRate == null
                    || Double.isNaN(dualApprovalSamplingRate)
                    || dualApprovalSamplingRate < 0.0 || dualApprovalSamplingRate > 1.0) {
                throw new IllegalArgumentException(
                        "Dual-approval sampling rate is required and must be between 0 and 1.");
            }
            set.setDualApprovalSamplingRate(dualApprovalSamplingRate);
        }
        return set;
    }
}
