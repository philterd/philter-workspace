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
import ai.philterd.workspace.model.Coordinates;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.FinalizationPolicy;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.RedactionCertificate;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.FinalizationPolicyRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One finalization for the review page and the API (#61). */
class DocumentFinalizationServiceTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final SpanRepository spans = mock(SpanRepository.class);
    private final BatchRepository batches = mock(BatchRepository.class);
    private final FinalizationPolicyRepository policies = mock(FinalizationPolicyRepository.class);
    private final RedactionCertificateService certificates = mock(RedactionCertificateService.class);
    private final DocumentLockService locks = mock(DocumentLockService.class);
    private final DocumentSearchService search = mock(DocumentSearchService.class);
    private final ai.philterd.workspace.repository.PendingUploadRepository pendingUploads =
            mock(ai.philterd.workspace.repository.PendingUploadRepository.class);
    private final DocumentDeliveryService delivery = mock(DocumentDeliveryService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private DocumentFinalizationService service;
    private Batch batch;

    @BeforeEach
    void setUp() {
        service = new DocumentFinalizationService(documents, spans, batches, policies, certificates, locks,
                new DocumentChangeGuard(documents, audit), new SourceRetentionService(documents, spans,
                mock(ai.philterd.workspace.repository.BlindReviewSpanRepository.class),
                        pendingUploads, batches, policies, search, audit), delivery, audit);
        batch = new Batch();
        batch.setId("b1");
        when(batches.findById("b1")).thenReturn(Optional.of(batch));
        final RedactionCertificate certificate = new RedactionCertificate();
        certificate.setId("cert-1");
        certificate.setDocumentHash("hash-1");
        when(certificates.generate(any(), any())).thenReturn(certificate);
    }

    private Document approved() {
        final Document d = new Document();
        d.setId("d1");
        d.setBatchId("b1");
        d.setOriginalText("Bob's SSN is 123-45-6789 and Ann wrote.");
        d.setStatus("APPROVED");
        when(spans.findByDocumentId("d1")).thenReturn(new ArrayList<>(List.of(
                span("s1", "ssn", "APPROVED", 13, 24), span("s2", "person", "REJECTED", 29, 32))));
        return d;
    }

    private static Span span(final String id, final String type, final String status, final int start, final int end) {
        final Span s = new Span();
        s.setId(id);
        s.setType(type);
        s.setStatus(status);
        s.setLocation(new Location(start, end, 1, new Coordinates(0, 0, 0, 0)));
        return s;
    }

    @Test
    void theReviewPageAndTheApiLeaveADocumentInTheSameState() {
        for (String via : List.of("review", "api")) {
            org.mockito.Mockito.clearInvocations(documents, spans, locks, certificates, audit, delivery);
            final Document d = approved();

            final String redacted = service.finalizeDocument(d, "lead@example.com", via);

            assertEquals("Bob's SSN is <<SSN>> and Ann wrote.", redacted, via);
            assertEquals("FINALIZED", d.getStatus(), via);
            assertEquals(redacted, d.getRedactedText(), via);
            verify(locks).release("d1", "lead@example.com");
            verify(certificates).generate(d, "lead@example.com");
            verify(audit).log("DOCUMENT_FINALIZE", "Document", "d1", Map.of("previous", "APPROVED",
                    "actor", "lead@example.com", "via", via, "certificateId", "cert-1", "documentHash", "hash-1"));
            verify(delivery).queue(d, batch, "lead@example.com");
        }
    }

    @Test
    void theRedactedTextMarksEachApprovedSpanWithItsTypeAndSkipsBrokenOnes() {
        final List<Span> list = List.of(span("a", "person", "APPROVED", 0, 3), span("b", "ssn", "APPROVED", 1, 5),
                span("c", "email-address", "APPROVED", 6, 99), span("d", "zip", "PENDING", 6, 8));
        assertEquals("<<PERSON>> two three", DocumentFinalizationService.render("one two three", list),
                "an overlapping span and one past the end are skipped; unapproved spans are not applied");
        assertEquals("", DocumentFinalizationService.render(null, List.of()));
    }

    @Test
    void aDocumentIsFinalizedOnlyWhenApprovedHasItsSourceAndNoOneElseHoldsIt() {
        final Document pending = approved();
        pending.setStatus("REVIEW_REQUIRED");
        assertRefused(pending, "Only APPROVED documents can be finalized.");

        final Document cleared = approved();
        cleared.setOriginalText(null);
        assertRefused(cleared, "Document source text is unavailable; it may have been cleared by a finalization "
                + "policy and cannot be finalized.");

        final Document held = approved();
        held.setLockedBy("other@example.com");
        held.setLockExpiresAt(Instant.now().plusSeconds(600));
        assertRefused(held, DocumentChangeGuard.lockedMessage("other@example.com"));

        verify(documents, never()).save(any());
        verify(certificates, never()).generate(any(), any());
        verify(delivery, never()).queue(any(), any(), any());
    }

    @Test
    void aDocumentWithAValueThatDidNotDecryptIsNotFinalized() {
        final Document d = approved();
        when(spans.findByDocumentId("d1")).thenReturn(new ArrayList<>(List.of(
                span("s1", "ssn", "APPROVED", 13, 24))));
        spans.findByDocumentId("d1").get(0).setText(PiiFieldEncryption.UNDECRYPTABLE);
        assertRefused(d, "This document cannot be finalized: some of its values could not be decrypted.");

        final Document undecryptable = approved();
        undecryptable.setOriginalText(PiiFieldEncryption.UNDECRYPTABLE);
        assertRefused(undecryptable, "This document cannot be finalized: some of its values could not be decrypted.");
        verify(documents, never()).save(any());
        verify(certificates, never()).generate(any(), any());
    }

    @Test
    void anEmptyDocumentIsFinalizedRatherThanTakenForAClearedOne() {
        final Document empty = approved();
        empty.setOriginalText("");
        when(spans.findByDocumentId("d1")).thenReturn(new ArrayList<>());

        assertEquals("", service.finalizeDocument(empty, "lead@example.com", "review"));
        assertEquals("FINALIZED", empty.getStatus());
        verify(certificates).generate(empty, "lead@example.com");
    }

    private void assertRefused(final Document d, final String message) {
        final DocumentFinalizationService.FinalizationRefused e = assertThrows(
                DocumentFinalizationService.FinalizationRefused.class,
                () -> service.finalizeDocument(d, "lead@example.com", "api"));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(message, e.getMessage());
    }

    @Test
    void deleteImmediatelyClearsTheSourceAfterTheCertificateAndBeforeDelivery() {
        final FinalizationPolicy policy = new FinalizationPolicy();
        policy.setId("fp1");
        policy.setName("Delete");
        policy.setOption(FinalizationPolicy.OPTION_DELETE_IMMEDIATELY);
        batch.setFinalizationPolicyId("fp1");
        when(policies.findById("fp1")).thenReturn(Optional.of(policy));
        when(spans.deleteByDocumentId("d1")).thenReturn(2L);
        when(search.removeDocument("d1")).thenReturn(true);
        when(delivery.queue(any(), any(), anyString())).thenReturn(true);
        final Document d = approved();

        service.finalizeDocument(d, "lead@example.com", "api");

        assertNull(d.getOriginalText());
        assertNotNull(d.getSourceClearedAt());
        assertNotNull(d.getFinalizedAt());
        assertEquals("Bob's SSN is <<SSN>> and Ann wrote.", d.getRedactedText(), "Download keeps working");
        verify(pendingUploads).deleteById("d1");
        final InOrder order = inOrder(certificates, spans, search, delivery);
        order.verify(certificates).generate(d, "lead@example.com");
        order.verify(spans).deleteByDocumentId("d1");
        order.verify(search).removeDocument("d1");
        order.verify(delivery).queue(d, batch, "lead@example.com");
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("FINALIZATION_POLICY_APPLIED"), eq("Document"), eq("d1"), details.capture());
        assertEquals("SOURCE_CLEARED", details.getValue().get("action"));
        assertEquals(2L, details.getValue().get("spansDeleted"));
    }

    @Test
    void legalHoldKeepsTheSourceAndIsAudited() {
        final FinalizationPolicy policy = new FinalizationPolicy();
        policy.setId("fp1");
        policy.setOption(FinalizationPolicy.OPTION_LEGAL_HOLD);
        batch.setFinalizationPolicyId("fp1");
        when(policies.findById("fp1")).thenReturn(Optional.of(policy));
        final Document d = approved();

        service.finalizeDocument(d, "lead@example.com", "review");

        assertEquals("Bob's SSN is 123-45-6789 and Ann wrote.", d.getOriginalText());
        assertNull(d.getSourceClearedAt());
        verify(spans, never()).deleteByDocumentId(anyString());
        verify(audit).log(eq("FINALIZATION_POLICY_APPLIED"), eq("Document"), eq("d1"), anyMap());
    }
}
