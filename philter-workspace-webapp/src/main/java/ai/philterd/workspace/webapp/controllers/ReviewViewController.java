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
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.LlmJudgeDefaults;
import ai.philterd.workspace.model.PiiTypes;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.model.OllamaInstance;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.ComplianceProfileRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.OllamaInstanceRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.model.User;
import ai.philterd.workspace.model.UserSettings;
import ai.philterd.workspace.service.ApprovalRuleEvaluator;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.service.DocumentAccessService;
import ai.philterd.workspace.service.LlmJudgeDefaultsService;
import ai.philterd.workspace.service.DocumentSearchService;
import ai.philterd.workspace.service.UserGroupsService;
import ai.philterd.workspace.service.UserSettingsService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.Set;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
public class ReviewViewController {

    /**
     * Pick the comparator that orders Previous/Next sibling navigation on the Review page.
     * Driven by the reviewer's UserSettings.reviewSortBy. Highest priority/risk first to
     * surface the most-actionable docs; alphabetical for filename. All comparators tie-break
     * on document id so order is stable across page loads.
     */
    private static Comparator<Document> batchOrder(final UserSettings settings) {
        final String sortBy = settings == null ? null : settings.getReviewSortBy();
        final Comparator<Document> primary;
        if (UserSettings.SORT_PRIORITY.equals(sortBy)) {
            primary = (a, b) -> Integer.compare(b.getPriority(), a.getPriority());
        } else if (UserSettings.SORT_FILENAME.equals(sortBy)) {
            primary = Comparator.comparing(d -> d.getFilename() == null ? "" : d.getFilename().toLowerCase());
        } else {
            primary = (a, b) -> Double.compare(b.getRiskScore(), a.getRiskScore());
        }
        return primary.thenComparing(d -> d.getId() == null ? "" : d.getId());
    }

    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final BatchRepository batchRepository;
    private final ComplianceProfileRepository complianceProfileRepository;
    private final UserGroupsService userGroupsService;
    private final DocumentAccessService documentAccessService;
    private final AuditLogService auditLogService;
    private final OllamaInstanceRepository ollamaInstanceRepository;
    private final LlmJudgeDefaultsService llmJudgeDefaultsService;
    private final UserSettingsService userSettingsService;
    private final UserRepository userRepository;
    private final ApprovalRuleEvaluator approvalRuleEvaluator;
    private final DocumentSearchService searchService;
    private final ai.philterd.workspace.service.DocumentLockService documentLockService;
    private final ai.philterd.workspace.service.GeneralSettingsService generalSettingsService;
    private final ai.philterd.workspace.service.DocumentDeliveryService deliveryService;
    private final ai.philterd.workspace.service.DocumentChangeGuard changeGuard;
    private final ai.philterd.workspace.service.DocumentFinalizationService finalizationService;

    public ReviewViewController(final DocumentRepository documentRepository,
                                final SpanRepository spanRepository,
                                final BatchRepository batchRepository,
                                final ComplianceProfileRepository complianceProfileRepository,
                                final UserGroupsService userGroupsService,
                                final DocumentAccessService documentAccessService,
                                final AuditLogService auditLogService,
                                final OllamaInstanceRepository ollamaInstanceRepository,
                                final LlmJudgeDefaultsService llmJudgeDefaultsService,
                                final UserSettingsService userSettingsService,
                                final UserRepository userRepository,
                                final ApprovalRuleEvaluator approvalRuleEvaluator,
                                final DocumentSearchService searchService,
                                final ai.philterd.workspace.service.DocumentLockService documentLockService,
                                final ai.philterd.workspace.service.GeneralSettingsService generalSettingsService,
                                final ai.philterd.workspace.service.DocumentDeliveryService deliveryService,
                                final ai.philterd.workspace.service.DocumentChangeGuard changeGuard,
                                final ai.philterd.workspace.service.DocumentFinalizationService finalizationService) {
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.batchRepository = batchRepository;
        this.complianceProfileRepository = complianceProfileRepository;
        this.userGroupsService = userGroupsService;
        this.documentAccessService = documentAccessService;
        this.auditLogService = auditLogService;
        this.ollamaInstanceRepository = ollamaInstanceRepository;
        this.llmJudgeDefaultsService = llmJudgeDefaultsService;
        this.userSettingsService = userSettingsService;
        this.userRepository = userRepository;
        this.approvalRuleEvaluator = approvalRuleEvaluator;
        this.searchService = searchService;
        this.documentLockService = documentLockService;
        this.generalSettingsService = generalSettingsService;
        this.deliveryService = deliveryService;
        this.changeGuard = changeGuard;
        this.finalizationService = finalizationService;
    }


    @GetMapping("/review/{documentId}")
    public String review(@PathVariable final String documentId, final Authentication authentication, final Model model) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        if (document.getOriginalText() == null || document.getOriginalText().isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Document source has been deleted by the batch's finalization policy "
                            + "and is no longer available for review.");
        }

        // Pessimistic lock acquisition. Atomic findAndModify guarantees only one reviewer
        // gets a fresh lock; other openers see read-only mode with a banner naming the
        // current holder. Admins can still open in read-only and choose to break the lock.
        final String email = authentication == null ? null : authentication.getName();
        // Auditors only read (#60): they take no review lock, which they could neither renew nor release, so a
        // reviewer is never locked out by an auditor looking at the document.
        final boolean readOnly = AuthUtils.isAuditor(authentication);
        // Nor does anyone lock an approved, rejected, or finalized document (#71, #67): its spans cannot change until
        // it is reopened, and a lock taken only by viewing it would hold up Finalize, Unapprove, and Unreject.
        final boolean settled = Set.of("APPROVED", "REJECTED", "FINALIZED").contains(document.getStatus());
        final Document acquired = email == null || readOnly || settled ? null
                : documentLockService.acquire(documentId, email);
        boolean lockedByOther = false;
        String lockHolder = null;
        java.time.Instant lockExpiresAt = null;
        if (acquired != null) {
            document = acquired;
            lockHolder = acquired.getLockedBy();
            lockExpiresAt = acquired.getLockExpiresAt();
        } else {
            // Re-fetch to read who currently holds the lock.
            document = documentRepository.findById(documentId).orElse(document);
            if (document.isLocked(java.time.Instant.now())
                    && (email == null || !email.equalsIgnoreCase(document.getLockedBy()))) {
                lockedByOther = true;
                lockHolder = document.getLockedBy();
                lockExpiresAt = document.getLockExpiresAt();
            }
        }

        final String originalText = document.getOriginalText() == null ? "" : document.getOriginalText();

        final List<Span> spans = spanRepository.findByDocumentId(documentId);
        spans.sort(Comparator.comparingInt(s -> s.getLocation().characterStart()));

        final StringBuilder redactedBuilder = new StringBuilder();
        final List<Map<String, Object>> originalRedactions = new ArrayList<>();
        final List<Map<String, Object>> redactedRedactions = new ArrayList<>();

        int cursor = 0;
        for (Span span : spans) {
            final int start = span.getLocation().characterStart();
            final int end = span.getLocation().characterEnd();
            if (start < cursor || end > originalText.length() || start > end) {
                continue;
            }
            redactedBuilder.append(originalText, cursor, start);

            final String replacement = "<<" + span.getType().toUpperCase() + ">>";
            final int newStart = redactedBuilder.length();
            redactedBuilder.append(replacement);
            final int newEnd = redactedBuilder.length();

            originalRedactions.add(redactionEntry(span, start, end));
            redactedRedactions.add(redactionEntry(span, newStart, newEnd));

            cursor = end;
        }
        redactedBuilder.append(originalText, cursor, originalText.length());

        final List<Map<String, String>> piiTypes = new ArrayList<>();
        PiiTypes.labels().forEach((value, label) -> {
            final Map<String, String> entry = new LinkedHashMap<>();
            entry.put("value", value);
            entry.put("label", label);
            piiTypes.add(entry);
        });

        final LlmJudgeDefaults defaults = llmJudgeDefaultsService.load();
        String defaultExplainInstanceId = defaults.getExplainInstanceId();
        String defaultExplainModel = defaults.getExplainModel();
        final boolean secondOpinionConfigured = defaults.getSecondOpinionInstanceId() != null;

        final List<Map<String, Object>> ollamaInstances = new ArrayList<>();
        final List<OllamaInstance> allInstances = ollamaInstanceRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        boolean explainInstanceStillExists = false;
        for (OllamaInstance i : allInstances) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", i.getId());
            entry.put("name", i.getName());
            ollamaInstances.add(entry);
            if (defaultExplainInstanceId != null && defaultExplainInstanceId.equals(i.getId())) {
                explainInstanceStillExists = true;
            }
        }
        if (!explainInstanceStillExists) {
            defaultExplainInstanceId = null;
            defaultExplainModel = null;
        }

        final String currentEmail = authentication == null ? null : authentication.getName();
        final UserSettings settings = userSettingsService.loadForEmail(currentEmail);
        final String prevDocumentId = findSiblingId(document, settings, -1, currentEmail);
        final String nextDocumentId = findSiblingId(document, settings, 1, currentEmail);

        // "Document X of Y" counter: Y = pending (not approved/rejected) docs in the batch,
        // X = 1-based position of the current doc within that sorted pending set.
        final List<Document> batchDocs = document.getBatchId() == null
                ? List.of() : documentRepository.findByBatchId(document.getBatchId());
        batchDocs.sort(batchOrder(settings));
        final List<Document> pendingDocs = batchDocs.stream()
                .filter(d -> !isAcceptedOrRejected(d.getStatus()))
                .collect(java.util.stream.Collectors.toList());
        int docPosition = 0;
        for (int i = 0; i < pendingDocs.size(); i++) {
            if (document.getId().equals(pendingDocs.get(i).getId())) {
                docPosition = i + 1;
                break;
            }
        }
        model.addAttribute("docPosition", docPosition);
        model.addAttribute("pendingCount", pendingDocs.size());

        model.addAttribute("document", document);
        model.addAttribute("originalText", originalText);
        model.addAttribute("redactedText", redactedBuilder.toString());
        model.addAttribute("originalRedactions", originalRedactions);
        model.addAttribute("redactedRedactions", redactedRedactions);
        model.addAttribute("piiTypes", piiTypes);
        model.addAttribute("ollamaInstances", ollamaInstances);
        model.addAttribute("defaultExplainInstanceId", defaultExplainInstanceId);
        model.addAttribute("defaultExplainModel", defaultExplainModel);
        model.addAttribute("secondOpinionConfigured", secondOpinionConfigured);
        model.addAttribute("prevDocumentId", prevDocumentId);
        model.addAttribute("nextDocumentId", nextDocumentId);
        model.addAttribute("currentUserEmail", authentication == null ? "" : authentication.getName());
        // Drives the Find similar documents button on the review page. When full-text
        // search is disabled the button (and its modal wiring) are not rendered.
        model.addAttribute("fullTextSearchEnabled", isFullTextSearchEnabled());

        // Worst-case approvals required (queue-display semantics): doesn't depend on
        // who the eventual reviewer will be, so reviewers see the same number whether
        // they're inexperienced or not.
        final Batch approvalBatch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        final int approvalsRequired = approvalRuleEvaluator.approvalsRequired(approvalBatch, document, spans);
        final int approvalsGiven = document.getApprovedBy().size();
        model.addAttribute("approvalsRequired", approvalsRequired);
        model.addAttribute("approverRequired", approvalsRequired > 1
                && approvalRuleEvaluator.approverPossiblyRequired(approvalBatch, document, spans));
        model.addAttribute("approvalsGiven", approvalsGiven);
        // As the approve action checks it: a reviewer cannot approve the same document twice.
        model.addAttribute("approvedByCurrentUser", authentication != null
                && document.getApprovedBy().contains(authentication.getName()));
        model.addAttribute("lockedByOther", lockedByOther);
        model.addAttribute("lockHolder", lockHolder == null ? "" : lockHolder);
        model.addAttribute("lockExpiresAt", lockExpiresAt);
        model.addAttribute("readOnly", readOnly);
        // An approver of the document's group (#5) adjudicates spans and releases a document another reviewer holds.
        final boolean approver = documentAccessService.canApproveDocument(authentication, document);
        model.addAttribute("isApprover", approver);
        // A failed delivery (#24) can be retried by an administrator or the batch's team lead.
        model.addAttribute("canRetryDelivery", !readOnly && documentAccessService.canLeadDocument(authentication, document));
        model.addAttribute("canReleaseLock", approver);

        final ai.philterd.workspace.model.ComplianceProfile complianceProfile =
                (approvalBatch == null || approvalBatch.getComplianceProfileId() == null) ? null
                : complianceProfileRepository.findById(approvalBatch.getComplianceProfileId()).orElse(null);
        final List<String> exemptionCodes = complianceProfile != null && complianceProfile.getExemptionCodes() != null
                ? complianceProfile.getExemptionCodes().stream()
                        .map(ai.philterd.workspace.model.ExemptionCode::getCode)
                        .collect(java.util.stream.Collectors.toList())
                : List.of();
        final String complianceProfileName = complianceProfile != null ? complianceProfile.getName() : null;
        // The exemption-code prompt fires only when the batch requires it AND the compliance
        // profile actually defines codes. The batch flag defaults to true on existing rows
        // (the field's Java default), so prior batches keep their original behaviour.
        final boolean exemptionCodeRequired = approvalBatch != null
                && approvalBatch.isExemptionCodeRequired()
                && !exemptionCodes.isEmpty();
        model.addAttribute("exemptionCodes", exemptionCodes);
        model.addAttribute("exemptionCodeRequired", exemptionCodeRequired);
        model.addAttribute("complianceProfileName", complianceProfileName);
        model.addAttribute("batchName", approvalBatch == null ? "" : (approvalBatch.getName() == null ? "" : approvalBatch.getName()));
        model.addAttribute("batchDescription", approvalBatch == null ? "" : (approvalBatch.getDescription() == null ? "" : approvalBatch.getDescription()));

        return "review";
    }

    @PostMapping(value = "/api/v1/review/{documentId}/pulse",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> pulse(@PathVariable final String documentId,
                                     final Authentication authentication) {
        final String email = authentication == null ? null : authentication.getName();
        final Document doc = email == null ? null : documentLockService.pulse(documentId, email);
        final Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (doc == null) {
            // Lock was lost (expired, broken, or held by someone else). The client should
            // refresh the review page so the user sees read-only mode.
            out.put("ok", false);
            out.put("reason", "LOCK_LOST");
            return out;
        }
        out.put("ok", true);
        out.put("lockExpiresAt", doc.getLockExpiresAt());
        return out;
    }

    @GetMapping("/api/v1/review/{documentId}/similar")
    @org.springframework.web.bind.annotation.ResponseBody
    public List<Map<String, Object>> findSimilar(@PathVariable final String documentId,
                                                  final Authentication authentication) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        // Verify the caller can access the source document's batch.
        documentAccessService.requireDocumentAccess(authentication, document);

        final DocumentSearchService.SearchResults results =
                searchService.findSimilar(documentId, document.getBatchId(), 10);

        // Filter every returned hit against the caller's accessible batches. Admins see
        // all results; non-admins only see hits whose batchId is in one of their groups.
        // This is defense-in-depth: the OpenSearch query already scopes to the source
        // document's batchId (which requireDocumentAccess already validated), but explicit
        // per-hit filtering ensures the check holds even if the search logic changes.
        // Auditors join the cross-group view here — the similar-document widget is
        // a read, just like admin's similar view.
        final boolean admin = AuthUtils.isAdminOrAuditor(authentication);
        final Set<String> allowedBatchIds = admin ? null : similarAllowedBatchIds(authentication);

        final java.util.Set<String> docIds = new java.util.HashSet<>();
        for (DocumentSearchService.SearchHit h : results.hits()) {
            if (h.id() != null && !h.id().isBlank()) docIds.add(h.id());
        }
        final Map<String, Document> liveDocs = new java.util.HashMap<>();
        for (Document d : documentRepository.findAllById(docIds)) {
            liveDocs.put(d.getId(), d);
        }

        final List<Map<String, Object>> out = new ArrayList<>();
        for (DocumentSearchService.SearchHit h : results.hits()) {
            if (!admin && (h.batchId() == null || !allowedBatchIds.contains(h.batchId()))) {
                continue;
            }
            final Document live = liveDocs.get(h.id());
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", h.id());
            row.put("filename", h.filename());
            row.put("status", live != null && live.getStatus() != null ? live.getStatus() : h.status());
            row.put("batchId", h.batchId());
            out.add(row);
        }
        return out;
    }

    private Set<String> similarAllowedBatchIds(final Authentication auth) {
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        final Set<String> ids = new java.util.HashSet<>();
        for (Batch b : batchRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()) {
            if (b.getGroupId() != null && myGroupIds.contains(b.getGroupId())) {
                ids.add(b.getId());
            }
        }
        return ids;
    }

    @PostMapping("/review/{documentId}/finalize")
    public String finalizeDocument(@PathVariable final String documentId,
                                   final Authentication authentication,
                                   final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        final Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            redirectAttributes.addFlashAttribute("error", "Document not found.");
            return "redirect:/queue";
        }
        documentAccessService.requireDocumentAccess(authentication, document);
        // The same finalization as the API's (#61): the lock and status checks, the certificate, the audit entry,
        // the finalization policy, and delivery.
        try {
            finalizationService.finalizeDocument(document, authentication == null ? null : authentication.getName(),
                    "review");
        } catch (ai.philterd.workspace.service.DocumentFinalizationService.FinalizationRefused e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/review/" + documentId;
        }
        return "redirect:/queue";
    }

    /** Queues a failed delivery again (#24). For administrators and the batch's team leads. */
    @PostMapping("/review/{documentId}/delivery/retry")
    public String retryDelivery(@PathVariable final String documentId,
                                final Authentication authentication,
                                final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        if (!documentAccessService.canLeadDocument(authentication, document)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "Only an administrator or the batch's team lead can retry a delivery.");
        }
        final String error = deliveryService.retry(document, authentication.getName());
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
        } else {
            redirectAttributes.addFlashAttribute("success", "Delivery queued again.");
        }
        return "redirect:/review/" + documentId;
    }

    @GetMapping("/review/{documentId}/download")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(
            @PathVariable final String documentId,
            final Authentication authentication) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        if (!"FINALIZED".equals(document.getStatus())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Only finalized documents can be downloaded.");
        }

        final String redacted = document.getRedactedText() != null
                ? document.getRedactedText()
                : ai.philterd.workspace.service.DocumentFinalizationService.render(document.getOriginalText(),
                        spanRepository.findByDocumentId(documentId));
        final byte[] body = redacted.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final String filename = redactedFilename(document.getFilename());
        final String email = authentication == null ? null : authentication.getName();
        // Filename hashed (R2-F7) — filenames carry PII; audit log is cross-group-readable.
        auditLogService.log("DOCUMENT_DOWNLOAD", "Document", documentId,
                Map.of("actor", email == null ? "" : email,
                        "filenameHash", auditLogService.hashForAudit(filename),
                        "filenameLength", filename == null ? 0 : filename.length(),
                        "bytes", body.length));
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename.replace("\"", "") + "\"")
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .body(new org.springframework.core.io.ByteArrayResource(body));
    }

    private static String redactedFilename(final String original) {
        if (original == null || original.isBlank()) return "redacted.txt";
        final int dot = original.lastIndexOf('.');
        if (dot <= 0) return original + "_redacted";
        return original.substring(0, dot) + "_redacted" + original.substring(dot);
    }

    @PostMapping("/review/{documentId}/release")
    public String release(@PathVariable final String documentId,
                          final Authentication authentication) {
        final String email = authentication == null ? null : authentication.getName();
        if (email != null) {
            documentLockService.release(documentId, email);
        }
        return "redirect:/queue";
    }

    /**
     * JSON-friendly release endpoint, for {@code navigator.sendBeacon} on page hide. Lives
     * under {@code /api/**} so it bypasses CSRF (sendBeacon can't easily attach tokens).
     */
    @PostMapping(value = "/api/v1/review/{documentId}/release",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> releaseApi(@PathVariable final String documentId,
                                          final Authentication authentication) {
        final String email = authentication == null ? null : authentication.getName();
        if (email != null) {
            documentLockService.release(documentId, email);
        }
        return Map.of("ok", true);
    }

    @PostMapping("/review/{documentId}/break-lock")
    public String breakLock(@PathVariable final String documentId,
                            final Authentication authentication,
                            final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        final Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            redirectAttributes.addFlashAttribute("error", "Document not found.");
            return "redirect:/";
        }
        // Administrators, and approvers of the document's group (#5), who release a document another reviewer
        // holds so someone else in the group can take it.
        documentAccessService.requireDocumentAccess(authentication, doc);
        if (!documentAccessService.canApproveDocument(authentication, doc)) {
            redirectAttributes.addFlashAttribute("error",
                    "Only an administrator or an approver of this document's group can release it.");
            return "redirect:/review/" + documentId;
        }
        final String previousHolder = doc.getLockedBy();
        documentLockService.breakLock(documentId);
        auditLogService.log("DOCUMENT_LOCK_BROKEN", "Document", documentId,
                Map.of("actor", authentication == null ? "" : authentication.getName(),
                        "previousHolder", previousHolder == null ? "" : previousHolder,
                        "asApprover", !AuthUtils.isAdmin(authentication)));
        redirectAttributes.addFlashAttribute("success",
                previousHolder == null
                        ? "Lock cleared."
                        : "Lock previously held by " + previousHolder + " has been cleared.");
        return "redirect:/review/" + documentId;
    }

    @PostMapping("/review/{documentId}/approve")
    public String approve(@PathVariable final String documentId,
                          final Authentication authentication,
                          final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        final Document preLoad = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, preLoad);

        final String email = authentication == null ? null : authentication.getName();

        // Pessimistic lock — claim exclusive access for this reviewer before mutating.
        // Without this, two reviewers in the same group could race: each load the doc,
        // each append to approvedBy, and the second save clobbers the first. Combined
        // with the @Version field on Document, the lost-update race is closed at both
        // layers (UI-lock to refuse the second mutation, optimistic-lock to catch any
        // race the pessimistic check might miss across lock-expiry boundaries).
        final Document document = email == null ? null
                : documentLockService.acquire(documentId, email);
        if (document == null) {
            redirectAttributes.addFlashAttribute("error",
                    "Another reviewer is currently editing this document. Refresh the page and try again.");
            return "redirect:/review/" + documentId;
        }

        final Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        final List<Span> spans = spanRepository.findByDocumentId(documentId);
        final User reviewer = userRepository.findByEmail(email).orElse(null);

        // Disallow the same user from approving twice — a second approval must come from
        // a different reviewer. This guard runs even when the doc currently only requires
        // a single approval, so a user can't pre-emptively double-approve.
        if (document.getApprovedBy().contains(email)) {
            redirectAttributes.addFlashAttribute("error",
                    "You have already approved this document. A different reviewer must give the next approval.");
            return "redirect:/review/" + documentId;
        }

        // Every span must be either APPROVED or REJECTED before the document can be
        // approved. Spans awaiting a second opinion get a tailored message; any other
        // non-terminal status (PENDING, missing) is rolled up into a generic prompt.
        final long awaitingSecondOpinion = spans.stream()
                .filter(s -> Span.STATUS_NEEDS_SECOND_OPINION.equals(s.getStatus()))
                .count();
        final long undecided = spans.stream()
                .filter(s -> !"APPROVED".equals(s.getStatus())
                        && !"REJECTED".equals(s.getStatus())
                        && !Span.STATUS_NEEDS_SECOND_OPINION.equals(s.getStatus()))
                .count();
        if (awaitingSecondOpinion > 0 || undecided > 0) {
            final StringBuilder error = new StringBuilder();
            if (awaitingSecondOpinion > 0) {
                error.append(awaitingSecondOpinion).append(" span")
                        .append(awaitingSecondOpinion == 1 ? " is" : "s are")
                        .append(" awaiting a second opinion");
            }
            if (undecided > 0) {
                if (error.length() > 0) error.append("; ");
                error.append(undecided).append(" span")
                        .append(undecided == 1 ? " has" : "s have")
                        .append(" not yet been approved or refused");
            }
            error.append(". Every span must be approved or refused before the document can be approved.");
            redirectAttributes.addFlashAttribute("error", error.toString());
            return "redirect:/review/" + documentId;
        }

        final int required = approvalRuleEvaluator.dualApprovalRequired(batch, document, spans, reviewer)
                ? 2 : 1;
        // An approval rule set can reserve the approval that completes the document for an approver of the batch's
        // group (#5). The first approval may come from anyone in the group.
        final boolean approverRequired = required > 1
                && approvalRuleEvaluator.approverRequired(batch, document, spans, reviewer);
        final boolean completes = document.getApprovedBy().size() + 1 >= required;
        if (approverRequired && completes && !documentAccessService.canApproveDocument(authentication, document)) {
            redirectAttributes.addFlashAttribute("error",
                    "This document's second approval must come from an approver of its group.");
            return "redirect:/review/" + documentId;
        }

        // Record this approval.
        document.getApprovedBy().add(email);
        // Stamp the first-ever reviewer so the blind-double-review filter can identify
        // who is disqualified from the second pass. Set once and never overwritten.
        // Capture each reviewer's approved-span snapshot so the IAA report can compute
        // Cohen's Kappa between the first and second reviewer's labels.
        if (document.getFirstReviewer() == null) {
            document.setFirstReviewer(email);
            document.setFirstReviewSpans(approvedSpanRanges(spans));
        } else if (document.isDoubleReview()
                && document.getFirstReviewer() != null
                && !email.equalsIgnoreCase(document.getFirstReviewer())
                && document.getSecondReviewer() == null) {
            document.setSecondReviewer(email);
            document.setSecondReviewSpans(approvedSpanRanges(spans));
        }
        final int acquired = document.getApprovedBy().size();

        final String previous = document.getStatus();
        if (acquired >= required) {
            document.changeStatus("APPROVED");
        } else {
            // Stay in REVIEW_REQUIRED so the doc remains visible to other reviewers.
            document.changeStatus("REVIEW_REQUIRED");
        }
        try {
            documentRepository.save(document);
        } catch (OptimisticLockingFailureException e) {
            // Stale-version write — another reviewer's save sneaked in between our
            // lock acquisition and our own save (possible across a lock-expiry boundary).
            // Refuse the mutation rather than silently dropping their work.
            redirectAttributes.addFlashAttribute("error",
                    "Another reviewer's change was saved while you were reviewing. Refresh the page and try again.");
            return "redirect:/review/" + documentId;
        }
        incrementReviewCount(reviewer);
        documentLockService.release(documentId, email);
        auditLogService.log("DOCUMENT_APPROVAL", "Document", documentId,
                Map.of(
                        "previous", previous == null ? "" : previous,
                        "current", document.getStatus() == null ? "" : document.getStatus(),
                        "approvedBy", email == null ? "" : email,
                        "acquired", acquired,
                        "required", required,
                        "approverRequired", approverRequired));

        if (acquired < required) {
            redirectAttributes.addFlashAttribute("success",
                    "Approval recorded (" + acquired + " of " + required
                            + "). " + (required - acquired) + " more approval"
                            + (required - acquired == 1 ? "" : "s")
                            + " needed from a different reviewer.");
            return "redirect:/";
        }

        final UserSettings settings = userSettingsService.loadForEmail(email);
        if (settings.isAdvanceToNextOnApprove()) {
            final String nextId = findSiblingId(document, settings, 1, email);
            if (nextId != null) return "redirect:/review/" + nextId;
        }
        return "redirect:/";
    }

    @PostMapping("/review/{documentId}/reject")
    public String reject(@PathVariable final String documentId,
                         final Authentication authentication,
                         final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        final String email = authentication == null ? null : authentication.getName();
        final Document preReject = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, preReject);

        // Same pessimistic-lock + optimistic-lock contract as approve(). If the lock
        // is held by another reviewer we refuse the mutation rather than racing.
        final Document document = email == null ? null
                : documentLockService.acquire(documentId, email);
        if (document == null) {
            redirectAttributes.addFlashAttribute("error",
                    "Another reviewer is currently editing this document. Refresh the page and try again.");
            return "redirect:/review/" + documentId;
        }

        final String previous = document.getStatus();
        // Stamp the first-ever reviewer (set once) so the blind-double-review filter
        // can identify who is disqualified from the second pass. Capture each reviewer's
        // approved-span snapshot so the IAA report can compute Cohen's Kappa.
        if (document.getFirstReviewer() == null) {
            document.setFirstReviewer(email);
            document.setFirstReviewSpans(approvedSpanRanges(spanRepository.findByDocumentId(documentId)));
        } else if (document.isDoubleReview()
                && !email.equalsIgnoreCase(document.getFirstReviewer())
                && document.getSecondReviewer() == null) {
            document.setSecondReviewer(email);
            document.setSecondReviewSpans(approvedSpanRanges(spanRepository.findByDocumentId(documentId)));
        }
        document.changeStatus("REJECTED");
        try {
            documentRepository.save(document);
        } catch (OptimisticLockingFailureException e) {
            redirectAttributes.addFlashAttribute("error",
                    "Another reviewer's change was saved while you were reviewing. Refresh the page and try again.");
            return "redirect:/review/" + documentId;
        }
        auditLogService.log("DOCUMENT_STATUS_CHANGE", "Document", documentId,
                Map.of("previous", previous == null ? "" : previous,
                        "current", "REJECTED",
                        "actor", email));
        auditLogService.log("DOCUMENT_REJECT", "Document", documentId,
                Map.of("previous", previous == null ? "" : previous,
                        "rejectedBy", email));
        incrementReviewCountByEmail(email);
        documentLockService.release(documentId, email);
        final UserSettings settings = userSettingsService.loadForEmail(email);
        if (settings.isAdvanceToNextOnApprove()) {
            final String nextId = findSiblingId(document, settings, 1, email);
            if (nextId != null) return "redirect:/review/" + nextId;
        }
        return "redirect:/queue";
    }

    private void incrementReviewCountByEmail(final String email) {
        if (email == null || email.isBlank()) return;
        incrementReviewCount(userRepository.findByEmail(email).orElse(null));
    }

    private void incrementReviewCount(final User reviewer) {
        if (reviewer == null) return;
        reviewer.setReviewCount(reviewer.getReviewCount() + 1);
        userRepository.save(reviewer);
    }

    @PostMapping("/review/{documentId}/unapprove")
    public String unapprove(@PathVariable final String documentId, final Authentication authentication,
                            final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        if ("FINALIZED".equals(document.getStatus())) {
            redirectAttributes.addFlashAttribute("error",
                    "Document is FINALIZED. Finalized documents cannot be reopened.");
            return "redirect:/review/" + documentId;
        }
        // No one reopens a document another person is reviewing (#71).
        final String holder = changeGuard.heldByOther(document,
                authentication == null ? null : authentication.getName());
        if (holder != null) {
            redirectAttributes.addFlashAttribute("error",
                    ai.philterd.workspace.service.DocumentChangeGuard.lockedMessage(holder));
            return "redirect:/review/" + documentId;
        }
        clearApprovals(document);
        updateStatus(documentId, "REVIEW_REQUIRED", authentication);
        auditLogService.log("DOCUMENT_UNAPPROVE", "Document", documentId,
                Map.of("actor", authentication == null ? "" : authentication.getName()));
        return "redirect:/review/" + documentId;
    }

    @PostMapping("/review/{documentId}/unreject")
    public String unreject(@PathVariable final String documentId, final Authentication authentication,
                           final org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        if ("FINALIZED".equals(document.getStatus())) {
            redirectAttributes.addFlashAttribute("error",
                    "Document is FINALIZED. Finalized documents cannot be reopened.");
            return "redirect:/review/" + documentId;
        }
        // No one reopens a document another person is reviewing (#71).
        final String holder = changeGuard.heldByOther(document,
                authentication == null ? null : authentication.getName());
        if (holder != null) {
            redirectAttributes.addFlashAttribute("error",
                    ai.philterd.workspace.service.DocumentChangeGuard.lockedMessage(holder));
            return "redirect:/review/" + documentId;
        }
        clearApprovals(document);
        updateStatus(documentId, "REVIEW_REQUIRED", authentication);
        auditLogService.log("DOCUMENT_UNREJECT", "Document", documentId,
                Map.of("actor", authentication == null ? "" : authentication.getName()));
        return "redirect:/review/" + documentId;
    }

    /**
     * Drop any recorded approvals so the badge resets to {@code 0 of N} when the document
     * is sent back to {@code REVIEW_REQUIRED}. Caller must have already loaded the document
     * and verified access — this method writes unconditionally.
     */
    private void clearApprovals(final Document document) {
        if (document.getApprovedBy().isEmpty()) return;
        document.getApprovedBy().clear();
        documentRepository.save(document);
    }

    private String findSiblingId(final Document document, final UserSettings settings, final int direction,
                                 final String currentEmail) {
        if (document.getBatchId() == null) return null;
        final boolean skipCompleted = settings != null && settings.isSkipCompletedInReview();
        final List<Document> siblings = documentRepository.findByBatchId(document.getBatchId());
        siblings.sort(batchOrder(settings));
        int index = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (document.getId().equals(siblings.get(i).getId())) {
                index = i;
                break;
            }
        }
        if (index < 0) return null;
        for (int i = index + direction; i >= 0 && i < siblings.size(); i += direction) {
            final Document candidate = siblings.get(i);
            if (skipCompleted
                    && isAcceptedOrRejected(candidate.getStatus())
                    && !awaitingBlindSecondReviewBy(candidate, currentEmail)) {
                continue;
            }
            // Blind double review: skip documents the current user already first-reviewed.
            // The second pass must come from a different reviewer.
            if (candidate.isDoubleReview()
                    && candidate.getFirstReviewer() != null
                    && currentEmail != null
                    && candidate.getFirstReviewer().equalsIgnoreCase(currentEmail)) {
                continue;
            }
            return candidate.getId();
        }
        return null;
    }

    /**
     * True when a terminally-statused candidate is still awaiting a blind second review
     * by the current user. Used to override {@link UserSettings#isSkipCompletedInReview()}
     * so a second reviewer is paged to the documents that need their attention even
     * though the document is technically APPROVED or REJECTED from the first pass.
     */
    private static boolean awaitingBlindSecondReviewBy(final Document candidate, final String currentEmail) {
        if (!candidate.isDoubleReview()) return false;
        if (candidate.getSecondReviewer() != null && !candidate.getSecondReviewer().isBlank()) return false;
        final String firstReviewer = candidate.getFirstReviewer();
        if (firstReviewer == null || firstReviewer.isBlank()) return false;
        return currentEmail != null && !firstReviewer.equalsIgnoreCase(currentEmail);
    }

    /**
     * Reads the master full-text search flag from settings, defaulting to {@code false}
     * defensively if the settings load fails. Used to suppress UI affordances that depend
     * on OpenSearch when the feature is off so reviewers don't get a button that produces
     * empty results.
     */
    private boolean isFullTextSearchEnabled() {
        try {
            return generalSettingsService.load().isFullTextSearchEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAcceptedOrRejected(final String status) {
        return "APPROVED".equals(status) || "AUTO_APPROVED".equals(status) || "REJECTED".equals(status);
    }

    /**
     * Project the APPROVED spans on a document into {@code [start, end]} character ranges.
     * Used to snapshot a reviewer's effective PII labels at the moment they completed their
     * review so the Inter-Annotator Agreement (Cohen's Kappa) report can compare each reviewer.
     */
    private static java.util.List<int[]> approvedSpanRanges(final List<Span> spans) {
        if (spans == null || spans.isEmpty()) return new java.util.ArrayList<>();
        final java.util.List<int[]> ranges = new java.util.ArrayList<>();
        for (Span s : spans) {
            if (!"APPROVED".equals(s.getStatus())) continue;
            if (s.getLocation() == null) continue;
            ranges.add(new int[]{s.getLocation().characterStart(), s.getLocation().characterEnd()});
        }
        return ranges;
    }

    private void updateStatus(final String documentId, final String status, final Authentication authentication) {
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        final String previous = document.getStatus();
        document.changeStatus(status);
        documentRepository.save(document);
        auditLogService.log("DOCUMENT_STATUS_CHANGE", "Document", documentId,
                Map.of("previous", previous == null ? "" : previous, "current", status));
    }

    private static Map<String, Object> redactionEntry(final Span span, final int start, final int end) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", span.getId());
        entry.put("text", span.getText());
        entry.put("type", span.getType());
        entry.put("confidence", span.getConfidence());
        entry.put("status", span.getStatus());
        entry.put("start", start);
        entry.put("end", end);
        entry.put("manuallyCreated", span.isManuallyCreated());
        entry.put("statusChangedBy", span.getStatusChangedBy());
        entry.put("adjudicatedBy", span.getAdjudicatedBy());
        entry.put("adjusted", span.isAdjusted());
        return entry;
    }
}
