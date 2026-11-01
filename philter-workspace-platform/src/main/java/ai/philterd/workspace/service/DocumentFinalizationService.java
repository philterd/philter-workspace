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
import ai.philterd.workspace.model.RedactionCertificate;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.FinalizationPolicyRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finalizes an approved document (#61). The review page and {@code POST /api/v1/documents/{id}/finalize} both
 * finalize through this service, so a document ends in the same state whichever is used: its redacted text rendered
 * and kept, a certificate of redaction issued, {@code DOCUMENT_FINALIZE} audited, the batch's finalization policy
 * applied, and delivery to the batch's destination queued.
 */
@Service
public class DocumentFinalizationService {

    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final BatchRepository batchRepository;
    private final FinalizationPolicyRepository finalizationPolicyRepository;
    private final RedactionCertificateService certificateService;
    private final DocumentLockService lockService;
    private final DocumentChangeGuard changeGuard;
    private final DocumentSearchService searchService;
    private final DocumentDeliveryService deliveryService;
    private final AuditLogService auditLogService;

    public DocumentFinalizationService(final DocumentRepository documentRepository,
                                       final SpanRepository spanRepository,
                                       final BatchRepository batchRepository,
                                       final FinalizationPolicyRepository finalizationPolicyRepository,
                                       final RedactionCertificateService certificateService,
                                       final DocumentLockService lockService,
                                       final DocumentChangeGuard changeGuard,
                                       final DocumentSearchService searchService,
                                       final DocumentDeliveryService deliveryService,
                                       final AuditLogService auditLogService) {
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.batchRepository = batchRepository;
        this.finalizationPolicyRepository = finalizationPolicyRepository;
        this.certificateService = certificateService;
        this.lockService = lockService;
        this.changeGuard = changeGuard;
        this.searchService = searchService;
        this.deliveryService = deliveryService;
        this.auditLogService = auditLogService;
    }

    /** Why a document cannot be finalized, with the HTTP status the API answers with. */
    public static final class FinalizationRefused extends RuntimeException {
        private final HttpStatus status;

        public FinalizationRefused(final HttpStatus status, final String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    /**
     * Finalizes {@code document}, which the caller has already checked {@code actor} may access, and returns its
     * redacted text. {@code via} says which path finalized it ({@code review} or {@code api}) for the audit log.
     *
     * @throws FinalizationRefused when another person holds the review lock, the document is not APPROVED, or its
     *                             source text is gone
     */
    public String finalizeDocument(final Document document, final String actor, final String via) {
        final String holder = changeGuard.heldByOther(document, actor);
        if (holder != null) {
            throw new FinalizationRefused(HttpStatus.CONFLICT, DocumentChangeGuard.lockedMessage(holder));
        }
        if (!"APPROVED".equals(document.getStatus())) {
            throw new FinalizationRefused(HttpStatus.CONFLICT, "Only APPROVED documents can be finalized.");
        }
        // A finalization policy clears the source to null. An empty document is finalized as it is.
        final String originalText = document.getOriginalText();
        if (originalText == null) {
            throw new FinalizationRefused(HttpStatus.CONFLICT, "Document source text is unavailable; it may have "
                    + "been cleared by a finalization policy and cannot be finalized.");
        }

        final List<Span> spans = spanRepository.findByDocumentId(document.getId());
        // A value that did not decrypt would be refused when the certificate is saved, after the document was
        // already finalized (#74), so refuse before changing anything.
        if (PiiFieldEncryption.UNDECRYPTABLE.equals(originalText)
                || spans.stream().anyMatch(sp -> PiiFieldEncryption.UNDECRYPTABLE.equals(sp.getText()))) {
            throw new FinalizationRefused(HttpStatus.CONFLICT, "This document cannot be finalized: some of its"
                    + " values could not be decrypted.");
        }

        final String previous = document.getStatus();
        final String redacted = render(originalText, spans);
        document.changeStatus("FINALIZED");
        // Kept on the document so Download and delivery still work after a finalization policy clears the source.
        document.setRedactedText(redacted);
        documentRepository.save(document);
        if (actor != null) {
            lockService.release(document.getId(), actor);
        }

        // Issued after the FINALIZED save, so its timestamps are the finalized document's.
        final RedactionCertificate certificate = certificateService.generate(document, actor);
        auditLogService.log("DOCUMENT_FINALIZE", "Document", document.getId(),
                Map.of("previous", previous == null ? "" : previous,
                        "actor", actor == null ? "" : actor,
                        "via", via,
                        "certificateId", certificate.getId() == null ? "" : certificate.getId(),
                        "documentHash", certificate.getDocumentHash() == null ? "" : certificate.getDocumentHash()));

        final Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        applyFinalizationPolicy(document, batch, actor);
        // Queued after the finalization policy has saved the document, so the delivery job and this call never save
        // it at once (#24).
        if (deliveryService.queue(document, batch, actor)) {
            documentRepository.save(document);
        }
        return redacted;
    }

    /**
     * The redacted text: each approved span replaced by its type in double angle brackets, such as {@code <<SSN>>}.
     * A span that overlaps an earlier one or falls outside the text is skipped.
     */
    public static String render(final String originalText, final List<Span> spans) {
        final String text = originalText == null ? "" : originalText;
        final List<Span> sorted = spans.stream()
                .filter(s -> "APPROVED".equals(s.getStatus()) && s.getLocation() != null)
                .sorted(Comparator.comparingInt(s -> s.getLocation().characterStart()))
                .toList();
        final StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (Span span : sorted) {
            final int start = span.getLocation().characterStart();
            final int end = span.getLocation().characterEnd();
            if (start < cursor || end > text.length() || start > end) continue;
            out.append(text, cursor, start);
            out.append("<<").append(span.getType() == null ? "" : span.getType().toUpperCase(Locale.ROOT)).append(">>");
            cursor = end;
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    /**
     * Applies the batch's finalization policy. Runs after the certificate is issued, so deleting the source cannot
     * strand it.
     */
    private void applyFinalizationPolicy(final Document document, final Batch batch, final String actor) {
        final String policyId = batch == null ? null : batch.getFinalizationPolicyId();
        final FinalizationPolicy policy = policyId == null || policyId.isBlank() ? null
                : finalizationPolicyRepository.findById(policyId).orElse(null);
        if (policy == null) return;
        final String option = policy.getOption() == null ? "" : policy.getOption();
        switch (option) {
            case FinalizationPolicy.OPTION_LEGAL_HOLD ->
                    auditLogService.log("FINALIZATION_POLICY_APPLIED", "Document", document.getId(),
                            Map.of("policyId", policy.getId() == null ? "" : policy.getId(),
                                    "policyName", policy.getName() == null ? "" : policy.getName(),
                                    "option", option,
                                    "action", "RETAIN",
                                    "actor", actor == null ? "" : actor));
            case FinalizationPolicy.OPTION_DELETE_IMMEDIATELY -> {
                // Clear the source text and the spans' detected values, keeping the document record so it stays in
                // the Document Queue and its certificate stays linked. The redacted text saved above keeps Download
                // working.
                final long spansDeleted = spanRepository.deleteByDocumentId(document.getId());
                document.setOriginalText(null);
                document.setStoragePath(null);
                documentRepository.save(document);
                // The full-text index holds the original text too.
                final boolean indexCleared = searchService.removeDocument(document.getId());
                auditLogService.log("FINALIZATION_POLICY_APPLIED", "Document", document.getId(),
                        Map.of("policyId", policy.getId() == null ? "" : policy.getId(),
                                "policyName", policy.getName() == null ? "" : policy.getName(),
                                "option", option,
                                "action", "SOURCE_CLEARED",
                                "spansDeleted", spansDeleted,
                                "indexCleared", indexCleared,
                                "actor", actor == null ? "" : actor));
            }
            default -> {
                // Delete after X days and after 48 hours need a scheduled job, which does not exist yet (#68).
            }
        }
    }
}
