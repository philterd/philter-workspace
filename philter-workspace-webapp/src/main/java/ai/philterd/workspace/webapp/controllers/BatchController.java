/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.ComplianceProfile;
import ai.philterd.workspace.model.Domains;
import ai.philterd.workspace.model.Group;
import ai.philterd.workspace.model.PiiTypes;
import ai.philterd.workspace.model.PiiWeights;
import ai.philterd.workspace.model.WeightSet;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.ComplianceProfileRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.GroupRepository;
import ai.philterd.workspace.repository.WeightSetRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.service.BatchAccessService;
import ai.philterd.workspace.service.UserGroupsService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


@Controller
@RequestMapping("/batches")
public class BatchController {

    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final GroupRepository groupRepository;
    private final UserGroupsService userGroupsService;
    private final BatchAccessService batchAccessService;
    private final AuditLogService auditLogService;
    private final WeightSetRepository weightSetRepository;
    private final ai.philterd.workspace.repository.FinalizationPolicyRepository finalizationPolicyRepository;
    private final ComplianceProfileRepository complianceProfileRepository;
    private final ai.philterd.workspace.repository.LocalDirectoryDestinationRepository localDirectoryDestinationRepository;
    private final ai.philterd.workspace.repository.S3DestinationRepository s3DestinationRepository;
    private final ai.philterd.workspace.service.BatchExportService batchExportService;
    private final ai.philterd.workspace.service.BatchDefaults batchDefaults;

    public BatchController(final BatchRepository batchRepository,
                           final DocumentRepository documentRepository,
                           final GroupRepository groupRepository,
                           final UserGroupsService userGroupsService,
                           final BatchAccessService batchAccessService,
                           final AuditLogService auditLogService,
                           final WeightSetRepository weightSetRepository,
                           final ai.philterd.workspace.repository.FinalizationPolicyRepository finalizationPolicyRepository,
                           final ComplianceProfileRepository complianceProfileRepository,
                           final ai.philterd.workspace.repository.LocalDirectoryDestinationRepository localDirectoryDestinationRepository,
                           final ai.philterd.workspace.repository.S3DestinationRepository s3DestinationRepository,
                           final ai.philterd.workspace.service.BatchExportService batchExportService,
                           final ai.philterd.workspace.service.BatchDefaults batchDefaults) {
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.groupRepository = groupRepository;
        this.userGroupsService = userGroupsService;
        this.batchAccessService = batchAccessService;
        this.auditLogService = auditLogService;
        this.weightSetRepository = weightSetRepository;
        this.finalizationPolicyRepository = finalizationPolicyRepository;
        this.complianceProfileRepository = complianceProfileRepository;
        this.localDirectoryDestinationRepository = localDirectoryDestinationRepository;
        this.s3DestinationRepository = s3DestinationRepository;
        this.batchExportService = batchExportService;
        this.batchDefaults = batchDefaults;
    }

    private static final Set<String> SORTABLE_FIELDS = Set.of("name", "createdAt", "documentCount");
    private static final Set<String> TERMINAL_STATUSES = Set.of("APPROVED", "REJECTED", "FAILED", "FINALIZED");
    private static final Set<String> CLOSEABLE_STATUSES = Set.of("REJECTED", "FINALIZED");
    private static final Set<String> REVIEWABLE_STATUSES = Set.of("REVIEW_REQUIRED", "AUDIT_REQUIRED");

    @GetMapping
    public String list(@RequestParam(name = "sort", defaultValue = "createdAt") final String sort,
                       @RequestParam(name = "dir", defaultValue = "desc") final String dir,
                       final Authentication authentication,
                       final Model model) {
        // Admins and auditors see every batch; this is a read-only listing. Write
        // actions on individual batches still require ADMIN further down.
        final boolean admin = AuthUtils.isAdminOrAuditor(authentication);
        final boolean restrict = !admin;
        final Set<String> myGroupIds = restrict
                ? userGroupsService.groupIdsForEmail(authentication == null ? null : authentication.getName())
                : Set.of();

        final List<Batch> batches = new ArrayList<>(batchRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent());
        if (restrict) {
            batches.removeIf(b -> b.getGroupId() == null || !myGroupIds.contains(b.getGroupId()));
        }

        final List<Group> allGroups = groupRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        final Map<String, String> groupNamesById = new LinkedHashMap<>();
        for (Group g : allGroups) {
            groupNamesById.put(g.getId(), g.getName());
        }

        final List<Group> assignableGroups = new ArrayList<>(allGroups);
        if (!admin) {
            assignableGroups.removeIf(g -> !myGroupIds.contains(g.getId()));
        }
        assignableGroups.sort(Comparator.comparing(
                (Group g) -> g.getName() == null ? "" : g.getName().toLowerCase()));

        final Map<String, String> finalizationPolicyNamesById = new LinkedHashMap<>();
        for (ai.philterd.workspace.model.FinalizationPolicy p : finalizationPolicyRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()) {
            finalizationPolicyNamesById.put(p.getId(), p.getName());
        }

        final Map<String, String> complianceProfileNamesById = new LinkedHashMap<>();
        for (ComplianceProfile cp : complianceProfileRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()) {
            complianceProfileNamesById.put(cp.getId(), cp.getName());
        }

        final PageRequest firstByFilename = PageRequest.of(0, 1, Sort.by("filename", "id"));
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (Batch batch : batches) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", batch.getId());
            row.put("name", batch.getName());
            row.put("description", batch.getDescription());
            row.put("createdAt", batch.getCreatedAt());
            row.put("confidenceThreshold", batch.getConfidenceThreshold());
            row.put("documentThreshold", batch.getDocumentThreshold());
            row.put("auditSamplingRate", batch.getAuditSamplingRate());
            final long total = documentRepository.countByBatchId(batch.getId());
            final long terminal = documentRepository.countByBatchIdAndStatusIn(batch.getId(), TERMINAL_STATUSES);
            row.put("documentCount", total);
            row.put("queuedCount", total - terminal);
            row.put("groupId", batch.getGroupId());
            row.put("groupName", batch.getGroupId() == null ? null : groupNamesById.get(batch.getGroupId()));
            row.put("policyName", batch.getPolicyName());
            row.put("policyByCaseType", batch.isPolicyByCaseType());
            row.put("domain", batch.getDomain());
            row.put("context", batch.getContext());
            row.put("finalizationPolicyId", batch.getFinalizationPolicyId());
            row.put("finalizationPolicyName", batch.getFinalizationPolicyId() == null
                    ? null : finalizationPolicyNamesById.get(batch.getFinalizationPolicyId()));
            row.put("complianceProfileId", batch.getComplianceProfileId());
            row.put("complianceProfileName", batch.getComplianceProfileId() == null
                    ? null : complianceProfileNamesById.get(batch.getComplianceProfileId()));
            row.put("blindDoubleReviewEnabled", batch.isBlindDoubleReviewEnabled());
            row.put("blindDoubleReviewPercentage", batch.getBlindDoubleReviewPercentage());
            row.put("destinationValue", batch.getDestinationType() == null || batch.getDestinationId() == null
                    ? "" : batch.getDestinationType() + ":" + batch.getDestinationId());
            row.put("closed", batch.isClosed());
            row.put("closedAt", batch.getClosedAt());
            String firstUnreviewedId = null;
            if (!batch.isClosed()) {
                final List<Document> unreviewed = documentRepository
                        .findByBatchIdAndStatusIn(batch.getId(), REVIEWABLE_STATUSES, firstByFilename)
                        .getContent();
                if (!unreviewed.isEmpty()) {
                    firstUnreviewedId = unreviewed.get(0).getId();
                }
            }
            row.put("firstUnreviewedDocumentId", firstUnreviewedId);
            rows.add(row);
        }

        final String activeSort = SORTABLE_FIELDS.contains(sort) ? sort : "createdAt";
        final boolean ascending = "asc".equalsIgnoreCase(dir);
        rows.sort(comparatorFor(activeSort, ascending));

        // Build the finalization-policy dropdown — id + name, sorted by name. Required
        // for new batches; legacy batches without one display a "—" until edited.
        final List<ai.philterd.workspace.model.FinalizationPolicy> finalizationPolicies =
                finalizationPolicyRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();

        final List<ComplianceProfile> complianceProfiles = complianceProfileRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()
                .stream().filter(cp -> !cp.isArchived()).collect(java.util.stream.Collectors.toList());

        // Destination dropdowns for the Export modal. Each option carries
        // {kind, id, label} so the modal can show one combined select grouped
        // by destination type without separate fetches.
        final List<Map<String, String>> exportDestinations = new ArrayList<>();
        for (ai.philterd.workspace.model.LocalDirectoryDestination d : localDirectoryDestinationRepository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()) {
            exportDestinations.add(Map.of("kind", "LOCAL", "id", d.getId(),
                    "label", d.getName() + " (Local Directory)"));
        }
        for (ai.philterd.workspace.model.S3Destination d : s3DestinationRepository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()) {
            exportDestinations.add(Map.of("kind", "S3", "id", d.getId(),
                    "label", d.getName() + " (Amazon S3)"));
        }

        // The destination each batch delivers finalized documents to (#24), labelled like the Export modal's options.
        final Map<String, String> destinationLabels = new java.util.HashMap<>();
        for (Map<String, String> d : exportDestinations) {
            destinationLabels.put(d.get("kind") + ":" + d.get("id"), d.get("label"));
        }
        for (Map<String, Object> row : rows) {
            final String value = (String) row.get("destinationValue");
            row.put("destinationLabel", value.isEmpty() ? null
                    : destinationLabels.getOrDefault(value, "(removed destination)"));
        }

        model.addAttribute("batches", rows);
        model.addAttribute("groups", assignableGroups);
        model.addAttribute("domains", Domains.VALUES);
        model.addAttribute("finalizationPolicies", finalizationPolicies);
        model.addAttribute("complianceProfiles", complianceProfiles);
        model.addAttribute("exportDestinations", exportDestinations);
        model.addAttribute("batchDefaults", batchDefaults);
        model.addAttribute("isAdmin", admin);
        model.addAttribute("currentSort", activeSort);
        model.addAttribute("currentDir", ascending ? "asc" : "desc");
        return "batches";
    }

    private static Comparator<Map<String, Object>> comparatorFor(final String field, final boolean ascending) {
        final Comparator<Map<String, Object>> base = switch (field) {
            case "name" -> Comparator.comparing(
                    r -> r.get("name") == null ? "" : ((String) r.get("name")).toLowerCase(),
                    Comparator.naturalOrder());
            case "documentCount" -> Comparator.comparingLong(
                    r -> {
                        final Object v = r.get("documentCount");
                        return v instanceof Number ? ((Number) v).longValue() : 0L;
                    });
            default -> Comparator.comparing(
                    r -> r.get("createdAt") == null ? LocalDateTime.MIN : (LocalDateTime) r.get("createdAt"));
        };
        return ascending ? base : base.reversed();
    }

    @PostMapping
    public String create(@RequestParam("name") String name,
                         @RequestParam(value = "description", required = false) String description,
                         @RequestParam(value = "confidenceThreshold", required = false) Double confidenceThreshold,
                         @RequestParam(value = "documentThreshold", required = false) Double documentThreshold,
                         @RequestParam(value = "auditSamplingRate", required = false) Double auditSamplingRate,
                         @RequestParam("groupId") String groupId,
                         @RequestParam(value = "policyName", required = false) String policyName,
                         @RequestParam(value = "policyByCaseType", defaultValue = "false") boolean policyByCaseType,
                         @RequestParam(value = "domain", required = false) String domain,
                         @RequestParam(value = "context", required = false, defaultValue = "") String context,
                         @RequestParam(value = "finalizationPolicyId", required = false) String finalizationPolicyId,
                         @RequestParam(value = "complianceProfileId", required = false) String complianceProfileId,
                         @RequestParam(value = "exemptionCodeRequired", required = false) Boolean exemptionCodeRequired,
                         @RequestParam(value = "blindDoubleReviewEnabled", required = false) Boolean blindDoubleReviewEnabled,
                         @RequestParam(value = "blindDoubleReviewPercentage", required = false) Integer blindDoubleReviewPercentage,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        // Admins can create in any group; team leads can only create in groups they
        // lead. We check after the groupId is parsed below since the lead check is
        // per-group.
        final String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Batch name is required.");
            return "redirect:/batches";
        }
        final Double normalizedPii = normalizeThreshold(confidenceThreshold);
        if (confidenceThreshold != null && normalizedPii == null) {
            redirectAttributes.addFlashAttribute("error", "PII threshold must be between 0 and 1.");
            return "redirect:/batches";
        }
        final Double normalizedDoc = normalizeThreshold(documentThreshold);
        if (documentThreshold != null && normalizedDoc == null) {
            redirectAttributes.addFlashAttribute("error", "Document threshold must be between 0 and 1.");
            return "redirect:/batches";
        }
        final Double normalizedRate = normalizeThreshold(auditSamplingRate);
        if (auditSamplingRate != null && normalizedRate == null) {
            redirectAttributes.addFlashAttribute("error", "Audit sampling rate must be between 0 and 1.");
            return "redirect:/batches";
        }
        if (groupId == null || groupId.isBlank() || !groupRepository.existsById(groupId)) {
            redirectAttributes.addFlashAttribute("error", "A valid group must be selected.");
            return "redirect:/batches";
        }
        // Per-group lead check: a team lead can only create batches in a group they lead.
        // Admins pass implicitly. Auditors are filtered out by AuditorWriteRejectFilter
        // before reaching this controller.
        if (!batchAccessService.canLeadGroup(authentication, groupId)) {
            redirectAttributes.addFlashAttribute("error",
                    "You can only create batches in groups you lead. Ask an administrator "
                            + "to make you a team lead of this group, or pick a different group.");
            return "redirect:/batches";
        }
        // After the access checks, so only a refusal of someone allowed to create here is audited.
        if (normalizedRate != null && batchDefaults.belowMinimum(normalizedRate)) {
            return refuseBelowMinimum(null, trimmed, normalizedRate, redirectAttributes);
        }
        final String trimmedDomain = domain == null ? "" : domain.trim();
        if (trimmedDomain.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Domain is required.");
            return "redirect:/batches";
        }
        if (!Domains.isValid(trimmedDomain)) {
            redirectAttributes.addFlashAttribute("error", "Domain \"" + trimmedDomain + "\" is not a valid choice.");
            return "redirect:/batches";
        }
        // Names are unique within a group, so a name another group uses is never refused, and a team lead learns
        // nothing about a group they cannot see (#83).
        if (batchRepository.existsByGroupIdAndName(groupId, trimmed)) {
            redirectAttributes.addFlashAttribute("error",
                    "This group already has a batch named \"" + trimmed + "\".");
            return "redirect:/batches";
        }
        final String trimmedFinalization = finalizationPolicyId == null ? "" : finalizationPolicyId.trim();
        if (trimmedFinalization.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "A finalization policy must be selected.");
            return "redirect:/batches";
        }
        if (!finalizationPolicyRepository.existsById(trimmedFinalization)) {
            redirectAttributes.addFlashAttribute("error",
                    "Selected finalization policy no longer exists.");
            return "redirect:/batches";
        }
        final String trimmedComplianceId = complianceProfileId == null ? "" : complianceProfileId.trim();
        if (trimmedComplianceId.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "A compliance profile must be selected.");
            return "redirect:/batches";
        }
        if (!complianceProfileRepository.existsById(trimmedComplianceId)) {
            redirectAttributes.addFlashAttribute("error", "Selected compliance profile no longer exists.");
            return "redirect:/batches";
        }
        final Batch batch = batchDefaults.apply(new Batch());
        batch.setId(UUID.randomUUID().toString());
        batch.setName(trimmed);
        batch.setCreatedAt(LocalDateTime.now());
        batch.setOwnerId(authentication == null ? "unknown" : authentication.getName());
        batch.setGroupId(groupId);
        final String trimmedPolicy = policyName == null ? "" : policyName.trim();
        batch.setPolicyName(trimmedPolicy.isEmpty() ? null : trimmedPolicy);
        batch.setPolicyByCaseType(policyByCaseType);
        batch.setDomain(trimmedDomain);
        batch.setDescription(description == null ? "" : description.trim());
        batch.setContext(context == null ? "" : context);
        batch.setFinalizationPolicyId(trimmedFinalization);
        batch.setComplianceProfileId(trimmedComplianceId);
        // Form checkboxes only submit a value when checked; an unchecked box arrives as null,
        // which we interpret as "exemption code not required". Default for missing field is true.
        batch.setExemptionCodeRequired(exemptionCodeRequired != null && exemptionCodeRequired);
        // Blind Double Review: write-once at create time. Percentage is clamped to 1..100;
        // when the feature is disabled the stored percentage is the model default (10).
        final boolean blindEnabled = blindDoubleReviewEnabled != null && blindDoubleReviewEnabled;
        batch.setBlindDoubleReviewEnabled(blindEnabled);
        if (blindEnabled) {
            final int pct = blindDoubleReviewPercentage == null ? 10 : blindDoubleReviewPercentage;
            if (pct < 1 || pct > 100) {
                redirectAttributes.addFlashAttribute("error",
                        "Blind Double Review percentage must be between 1 and 100.");
                return "redirect:/batches";
            }
            batch.setBlindDoubleReviewPercentage(pct);
        }
        if (normalizedPii != null) {
            batch.setConfidenceThreshold(normalizedPii);
        }
        if (normalizedDoc != null) {
            batch.setDocumentThreshold(normalizedDoc);
        }
        if (normalizedRate != null) {
            batch.setAuditSamplingRate(normalizedRate);
        }
        try {
            batchRepository.save(batch);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Race against someone creating the same name in the same group concurrently.
            redirectAttributes.addFlashAttribute("error",
                    "This group already has a batch named \"" + trimmed + "\".");
            return "redirect:/batches";
        }
        final Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("name", trimmed);
        auditDetails.put("groupId", groupId);
        auditDetails.put("policyName", trimmedPolicy);
        auditDetails.put("policyByCaseType", policyByCaseType);
        auditDetails.put("domain", trimmedDomain);
        auditDetails.put("contextLength", batch.getContext().length());
        auditDetails.put("confidenceThreshold", batch.getConfidenceThreshold());
        auditDetails.put("documentThreshold", batch.getDocumentThreshold());
        auditDetails.put("auditSamplingRate", batch.getAuditSamplingRate());
        auditDetails.put("complianceProfileId", trimmedComplianceId);
        auditDetails.put("exemptionCodeRequired", batch.isExemptionCodeRequired());
        auditDetails.put("blindDoubleReviewEnabled", batch.isBlindDoubleReviewEnabled());
        auditDetails.put("blindDoubleReviewPercentage", batch.getBlindDoubleReviewPercentage());
        auditLogService.log("BATCH_CREATE", "Batch", batch.getId(), auditDetails);
        redirectAttributes.addFlashAttribute("success", "Batch \"" + trimmed + "\" created.");
        return "redirect:/batches";
    }

    @PostMapping("/{batchId}/policy")
    public String changePolicy(@PathVariable final String batchId,
                               @RequestParam(value = "policyName", required = false) final String policyName,
                               @RequestParam(value = "policyByCaseType", defaultValue = "false") final boolean policyByCaseType,
                               final Authentication authentication,
                               final RedirectAttributes redirectAttributes) {
        // Collapse lookup-miss and access-denied into a single response shape so a
        // non-admin/non-lead can't enumerate batch ids by reading the error message.
        // The audit log still records who tried to act on which id; the user-facing
        // body is intentionally uniform.
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !batchAccessService.canLeadBatch(authentication, batch)) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        final String trimmedPolicy = policyName == null ? "" : policyName.trim();

        final String previousPolicy = batch.getPolicyName();
        final boolean previousByCaseType = batch.isPolicyByCaseType();
        batch.setPolicyName(trimmedPolicy.isEmpty() ? null : trimmedPolicy);
        batch.setPolicyByCaseType(policyByCaseType);
        batchRepository.save(batch);
        auditLogService.log("BATCH_POLICY_CHANGE", "Batch", batch.getId(),
                Map.of("previousPolicyName", previousPolicy == null ? "" : previousPolicy,
                        "newPolicyName", trimmedPolicy,
                        "previousPolicyByCaseType", previousByCaseType,
                        "policyByCaseType", policyByCaseType));
        final String policyLabel = policyByCaseType ? "the policy mapped to each document's case type"
                : trimmedPolicy.isEmpty() ? "Philter's default policy" : "policy \"" + trimmedPolicy + "\"";
        redirectAttributes.addFlashAttribute("success",
                "Batch \"" + batch.getName() + "\" now uses " + policyLabel + ".");
        return "redirect:/batches";
    }

    @PostMapping("/{batchId}/group")
    public String changeGroup(@PathVariable final String batchId,
                              @RequestParam("groupId") final String groupId,
                              final Authentication authentication,
                              final RedirectAttributes redirectAttributes) {
        // Reassigning a batch to a different group is admin-only — a team lead could
        // otherwise transfer batches into or out of their own scope, sidestepping the
        // per-group authority boundary.
        if (!AuthUtils.isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error",
                    "Only administrators can reassign a batch to a different group.");
            return "redirect:/batches";
        }
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        if (groupId == null || groupId.isBlank() || !groupRepository.existsById(groupId)) {
            redirectAttributes.addFlashAttribute("error", "A valid group must be selected.");
            return "redirect:/batches";
        }
        final String previousGroupId = batch.getGroupId();
        final String groupName = groupRepository.findById(groupId).map(Group::getName).orElse(groupId);
        // Names are unique within a group (#83). Only administrators reassign, and they see every group.
        final String taken = "Group \"" + groupName + "\" already has a batch named \"" + batch.getName() + "\".";
        if (!groupId.equals(previousGroupId) && batchRepository.existsByGroupIdAndName(groupId, batch.getName())) {
            redirectAttributes.addFlashAttribute("error", taken);
            return "redirect:/batches";
        }
        batch.setGroupId(groupId);
        try {
            batchRepository.save(batch);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error", taken);
            return "redirect:/batches";
        }
        auditLogService.log("BATCH_GROUP_CHANGE", "Batch", batch.getId(),
                Map.of("previousGroupId", previousGroupId == null ? "" : previousGroupId,
                        "newGroupId", groupId));
        redirectAttributes.addFlashAttribute(
                "success",
                "Batch \"" + batch.getName() + "\" assigned to group \"" + groupName + "\".");
        return "redirect:/batches";
    }

    /**
     * Chooses where finalized documents in the batch are delivered (#24): {@code LOCAL:<id>}, {@code S3:<id>}, or
     * empty for none. Administrators only, since it decides where documents leave Philter Workspace.
     */
    @PostMapping("/{batchId}/destination")
    public String changeDestination(@PathVariable final String batchId,
                                    @RequestParam(value = "destination", required = false) final String destination,
                                    final Authentication authentication,
                                    final RedirectAttributes redirectAttributes) {
        if (!AuthUtils.isAdmin(authentication)) {
            redirectAttributes.addFlashAttribute("error", "Only administrators can choose a batch's destination.");
            return "redirect:/batches";
        }
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        final String value = destination == null ? "" : destination.trim();
        String type = null;
        String id = null;
        String label = null;
        if (!value.isEmpty()) {
            final int colon = value.indexOf(':');
            type = colon < 0 ? "" : value.substring(0, colon);
            id = colon < 0 ? "" : value.substring(colon + 1);
            if ("LOCAL".equals(type)) {
                label = localDirectoryDestinationRepository.findById(id)
                        .map(d -> d.getName() + " (Local Directory)").orElse(null);
            } else if ("S3".equals(type)) {
                label = s3DestinationRepository.findById(id).map(d -> d.getName() + " (Amazon S3)").orElse(null);
            }
            if (label == null) {
                redirectAttributes.addFlashAttribute("error", "Selected destination no longer exists.");
                return "redirect:/batches";
            }
        }
        final String previousType = batch.getDestinationType();
        final String previousId = batch.getDestinationId();
        batch.setDestinationType(type);
        batch.setDestinationId(id);
        batchRepository.save(batch);
        auditLogService.log("BATCH_DESTINATION_CHANGE", "Batch", batch.getId(),
                Map.of("previousDestinationType", previousType == null ? "" : previousType,
                        "previousDestinationId", previousId == null ? "" : previousId,
                        "destinationType", type == null ? "" : type,
                        "destinationId", id == null ? "" : id));
        redirectAttributes.addFlashAttribute("success", label == null
                ? "Batch \"" + batch.getName() + "\" no longer delivers finalized documents."
                : "Batch \"" + batch.getName() + "\" delivers finalized documents to " + label + ".");
        return "redirect:/batches";
    }

    @PostMapping("/{batchId}/domain")
    public String changeDomain(@PathVariable final String batchId,
                               @RequestParam("domain") final String domain,
                               final Authentication authentication,
                               final RedirectAttributes redirectAttributes) {
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !batchAccessService.canLeadBatch(authentication, batch)) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        final String trimmed = domain == null ? "" : domain.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Domain is required.");
            return "redirect:/batches";
        }
        if (!Domains.isValid(trimmed)) {
            redirectAttributes.addFlashAttribute("error",
                    "Domain \"" + trimmed + "\" is not a valid choice.");
            return "redirect:/batches";
        }
        final String previous = batch.getDomain();
        batch.setDomain(trimmed);
        batchRepository.save(batch);
        auditLogService.log("BATCH_DOMAIN_CHANGE", "Batch", batch.getId(),
                Map.of("previousDomain", previous == null ? "" : previous,
                        "newDomain", trimmed));
        redirectAttributes.addFlashAttribute("success",
                "Batch \"" + batch.getName() + "\" domain set to \"" + trimmed + "\".");
        return "redirect:/batches";
    }

    @GetMapping("/{batchId}/weights")
    public String weights(@PathVariable final String batchId,
                          final Authentication authentication,
                          final Model model,
                          final RedirectAttributes redirectAttributes) {
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !batchAccessService.canAccessBatch(authentication, batch)) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        final Map<String, Integer> effective = PiiWeights.effective(batch.getPiiTypeWeights());
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : effective.entrySet()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", entry.getKey());
            row.put("label", PiiTypes.labelFor(entry.getKey()));
            row.put("weight", entry.getValue());
            row.put("defaultWeight", PiiWeights.weightFor(entry.getKey(), null));
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> ((String) r.get("label")).toLowerCase()));

        final List<WeightSet> weightSets = weightSetRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        final List<Map<String, Object>> weightSetData = new ArrayList<>();
        for (WeightSet ws : weightSets) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", ws.getId());
            entry.put("name", ws.getName());
            entry.put("weights", ws.getWeights() == null ? Map.of() : ws.getWeights());
            weightSetData.add(entry);
        }

        model.addAttribute("batch", batch);
        model.addAttribute("weightRows", rows);
        model.addAttribute("weightSets", weightSetData);
        model.addAttribute("currentWeightSetId", batch.getWeightSetId());
        return "batch-weights";
    }

    @PostMapping("/{batchId}/weights")
    public String saveWeights(@PathVariable final String batchId,
                              @RequestParam(value = "type", required = false) final List<String> types,
                              @RequestParam(value = "weight", required = false) final List<Integer> weights,
                              @RequestParam(value = "reset", required = false) final String reset,
                              @RequestParam(value = "weightSetId", required = false) final String weightSetId,
                              final Authentication authentication,
                              final RedirectAttributes redirectAttributes) {
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !batchAccessService.canLeadBatch(authentication, batch)) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }

        if (reset != null) {
            batch.setPiiTypeWeights(null);
            batch.setWeightSetId(null);
            batchRepository.save(batch);
            auditLogService.log("BATCH_WEIGHTS_RESET", "Batch", batch.getId(), null);
            redirectAttributes.addFlashAttribute("success",
                    "Weights for \"" + batch.getName() + "\" reset to defaults.");
            return "redirect:/batches/" + batchId + "/weights";
        }

        if (types == null || weights == null || types.size() != weights.size()) {
            redirectAttributes.addFlashAttribute("error", "Invalid weights submission.");
            return "redirect:/batches/" + batchId + "/weights";
        }

        final Map<String, Integer> overrides = new LinkedHashMap<>();
        for (int i = 0; i < types.size(); i++) {
            final String type = types.get(i);
            final Integer weight = weights.get(i);
            if (type == null || weight == null) continue;
            final String key = type.trim().toLowerCase();
            if (!PiiTypes.isValid(key)) continue;
            if (weight < 0) {
                redirectAttributes.addFlashAttribute("error",
                        "Weight for " + PiiTypes.labelFor(key) + " must be 0 or greater.");
                return "redirect:/batches/" + batchId + "/weights";
            }
            final int defaultWeight = PiiWeights.weightFor(key, null);
            if (weight != defaultWeight) {
                overrides.put(key, weight);
            }
        }

        batch.setPiiTypeWeights(overrides.isEmpty() ? null : overrides);
        // Update the binding to whichever preset was last loaded (or null if none).
        final String trimmedSetId = weightSetId == null ? null : weightSetId.trim();
        if (trimmedSetId == null || trimmedSetId.isEmpty()) {
            batch.setWeightSetId(null);
        } else if (weightSetRepository.existsById(trimmedSetId)) {
            batch.setWeightSetId(trimmedSetId);
        } else {
            batch.setWeightSetId(null);
        }
        batchRepository.save(batch);
        auditLogService.log("BATCH_WEIGHTS_CHANGE", "Batch", batch.getId(),
                Map.of("overrideCount", overrides.size(), "overrides", overrides,
                        "weightSetId", batch.getWeightSetId() == null ? "" : batch.getWeightSetId()));
        redirectAttributes.addFlashAttribute("success",
                "Weights for \"" + batch.getName() + "\" updated.");
        return "redirect:/batches/" + batchId + "/weights";
    }

    @PostMapping("/{batchId}/thresholds")
    public String editThresholds(@PathVariable final String batchId,
                                 @RequestParam("confidenceThreshold") final double confidenceThreshold,
                                 @RequestParam("documentThreshold") final double documentThreshold,
                                 @RequestParam(value = "auditSamplingRate", required = false) final Double auditSamplingRate,
                                 @RequestParam(value = "context", required = false) final String context,
                                 @RequestParam(value = "domain", required = false) final String domain,
                                 @RequestParam(value = "description", required = false) final String description,
                                 final Authentication authentication,
                                 final RedirectAttributes redirectAttributes) {
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !batchAccessService.canLeadBatch(authentication, batch)) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        final Double normalizedPii = normalizeThreshold(confidenceThreshold);
        if (normalizedPii == null) {
            redirectAttributes.addFlashAttribute("error", "PII threshold must be between 0 and 1.");
            return "redirect:/batches";
        }
        final Double normalizedDoc = normalizeThreshold(documentThreshold);
        if (normalizedDoc == null) {
            redirectAttributes.addFlashAttribute("error", "Document threshold must be between 0 and 1.");
            return "redirect:/batches";
        }
        final Double normalizedRate = auditSamplingRate == null ? batch.getAuditSamplingRate() : normalizeThreshold(auditSamplingRate);
        if (normalizedRate == null) {
            redirectAttributes.addFlashAttribute("error", "Audit sampling rate must be between 0 and 1.");
            return "redirect:/batches";
        }
        if (batchDefaults.belowMinimum(normalizedRate)) {
            return refuseBelowMinimum(batch.getId(), batch.getName(), normalizedRate, redirectAttributes);
        }
        // Domain is optional on this endpoint — only validated/applied when sent.
        if (domain != null) {
            final String trimmedDomain = domain.trim();
            if (trimmedDomain.isEmpty() || !Domains.isValid(trimmedDomain)) {
                redirectAttributes.addFlashAttribute("error",
                        "Domain \"" + trimmedDomain + "\" is not a valid choice.");
                return "redirect:/batches";
            }
        }
        final double previousPii = batch.getConfidenceThreshold();
        final double previousDoc = batch.getDocumentThreshold();
        final double previousRate = batch.getAuditSamplingRate();
        final String previousContext = batch.getContext();
        final String previousDomain = batch.getDomain();
        batch.setConfidenceThreshold(normalizedPii);
        batch.setDocumentThreshold(normalizedDoc);
        batch.setAuditSamplingRate(normalizedRate);
        // context, domain, and description are optional in the form — only update when explicitly sent.
        if (context != null) {
            batch.setContext(context);
        }
        if (domain != null) {
            batch.setDomain(domain.trim());
        }
        if (description != null) {
            batch.setDescription(description.trim());
        }
        batchRepository.save(batch);
        final boolean contextChanged = context != null && !batch.getContext().equals(previousContext);
        final boolean domainChanged = domain != null
                && !java.util.Objects.equals(batch.getDomain(), previousDomain);
        auditLogService.log("BATCH_THRESHOLDS_CHANGE", "Batch", batch.getId(),
                Map.of(
                        "previousPii", previousPii, "currentPii", normalizedPii,
                        "previousDocument", previousDoc, "currentDocument", normalizedDoc,
                        "previousAuditSamplingRate", previousRate, "currentAuditSamplingRate", normalizedRate,
                        "contextChanged", contextChanged,
                        "domainChanged", domainChanged));
        redirectAttributes.addFlashAttribute(
                "success",
                "Settings for \"" + batch.getName() + "\" updated.");
        return "redirect:/batches";
    }

    /**
     * Refuses an audit sampling rate below the deployment's minimum
     * ({@code philter-workspace.batch.minimum-audit-sampling-rate}) and records the attempt.
     */
    private String refuseBelowMinimum(final String batchId, final String batchName, final double requested,
                                      final RedirectAttributes redirectAttributes) {
        final double minimum = batchDefaults.minimumAuditSamplingRate();
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", batchName == null ? "" : batchName);
        details.put("requestedAuditSamplingRate", requested);
        details.put("minimumAuditSamplingRate", minimum);
        auditLogService.log("BATCH_AUDIT_SAMPLING_RATE_REFUSED", "Batch", batchId, details);
        redirectAttributes.addFlashAttribute("error", "The audit sampling rate cannot be below " + minimum
                + " in this deployment.");
        return "redirect:/batches";
    }

    private static Double normalizeThreshold(Double value) {
        if (value == null || Double.isNaN(value) || value < 0.0 || value > 1.0) {
            return null;
        }
        return value;
    }

    @PostMapping("/{batchId}/close")
    public String close(@PathVariable final String batchId,
                        final Authentication authentication,
                        final RedirectAttributes redirectAttributes) {
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !batchAccessService.canLeadBatch(authentication, batch)) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        if (batch.isClosed()) {
            redirectAttributes.addFlashAttribute("error", "Batch is already closed.");
            return "redirect:/batches";
        }
        final long total = documentRepository.countByBatchId(batchId);
        final long ready = documentRepository.countByBatchIdAndStatusIn(batchId, CLOSEABLE_STATUSES);
        if (total != ready) {
            final long pending = total - ready;
            redirectAttributes.addFlashAttribute("error",
                    "Batch \"" + batch.getName() + "\" cannot be closed: " + pending
                            + (pending == 1 ? " document is" : " documents are")
                            + " not yet rejected or finalized.");
            return "redirect:/batches";
        }
        batch.setClosed(true);
        batch.setClosedAt(LocalDateTime.now());
        batch.setClosedBy(authentication == null ? "unknown" : authentication.getName());
        batchRepository.save(batch);
        auditLogService.log("BATCH_CLOSE", "Batch", batchId,
                Map.of("name", batch.getName() == null ? "" : batch.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Batch \"" + batch.getName() + "\" closed.");
        return "redirect:/batches";
    }

    /**
     * Export the APPROVED documents in a batch to a configured destination as a
     * single file in the chosen data format. Currently only JSONL is supported;
     * the {@code format} parameter is validated server-side so a tampered form
     * value can't request an unimplemented format. Restricted to admin or the
     * batch's team lead — exporting redacted source data is the same blast
     * radius as changing the batch's destination wiring.
     */
    @PostMapping("/{batchId}/export")
    public String export(@PathVariable final String batchId,
                         @RequestParam("destination") final String destinationCombined,
                         @RequestParam(value = "format", defaultValue = "JSONL") final String formatRaw,
                         @RequestParam(value = "thresholdAccepted", defaultValue = "exclude") final String thresholdAccepted,
                         final Authentication authentication,
                         final RedirectAttributes redirectAttributes) {
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !batchAccessService.canLeadBatch(authentication, batch)) {
            redirectAttributes.addFlashAttribute("error", "Batch not found.");
            return "redirect:/batches";
        }
        // The destination select carries values like "LOCAL:abcd123" so a single
        // <select> can offer all destination types side by side. Split here.
        if (destinationCombined == null || !destinationCombined.contains(":")) {
            redirectAttributes.addFlashAttribute("error", "Pick a destination.");
            return "redirect:/batches";
        }
        final int colon = destinationCombined.indexOf(':');
        final String kindRaw = destinationCombined.substring(0, colon).trim().toUpperCase();
        final String destinationId = destinationCombined.substring(colon + 1).trim();
        final ai.philterd.workspace.service.BatchExportService.DestinationKind kind;
        try {
            kind = ai.philterd.workspace.service.BatchExportService.DestinationKind.valueOf(kindRaw);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", "Unknown destination kind: " + kindRaw);
            return "redirect:/batches";
        }
        final ai.philterd.workspace.service.BatchExportService.Format format;
        try {
            format = ai.philterd.workspace.service.BatchExportService.Format.valueOf(
                    formatRaw == null ? "JSONL" : formatRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", "Unknown data format: " + formatRaw);
            return "redirect:/batches";
        }

        final String actorEmail = authentication == null ? null : authentication.getName();
        final boolean excludeThresholdAccepted = !"include".equalsIgnoreCase(thresholdAccepted.trim());
        final ai.philterd.workspace.service.BatchExportService.Result result =
                batchExportService.enqueueExport(batchId, format, kind, destinationId, excludeThresholdAccepted,
                        actorEmail);
        if (!result.isOk()) {
            redirectAttributes.addFlashAttribute("error",
                    "Export could not be queued: " + result.getError());
            return "redirect:/batches";
        }
        redirectAttributes.addFlashAttribute("success",
                "Export queued for batch \"" + batch.getName() + "\" — "
                        + result.getApprovedCount()
                        + (result.getApprovedCount() == 1 ? " approved document" : " approved documents")
                        + (excludeThresholdAccepted
                                ? ", leaving out any with redactions no person decided" : "")
                        + ". See the Background Jobs page for progress.");
        return "redirect:/batches";
    }

    private Set<String> userGroupIds(final Authentication auth) {
        return userGroupsService.groupIdsForEmail(auth == null ? null : auth.getName());
    }
}
