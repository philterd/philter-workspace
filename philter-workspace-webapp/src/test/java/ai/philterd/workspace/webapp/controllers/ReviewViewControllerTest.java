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

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Coordinates;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.FinalizationPolicy;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.RedactionCertificate;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.ComplianceProfileRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.FinalizationPolicyRepository;
import ai.philterd.workspace.repository.OllamaInstanceRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.service.ApprovalRuleEvaluator;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.DocumentLockService;
import ai.philterd.workspace.service.LlmJudgeDefaultsService;
import ai.philterd.workspace.service.OpenSearchIndexService;
import ai.philterd.workspace.service.UserGroupsService;
import ai.philterd.workspace.service.UserSettingsService;
import ai.philterd.workspace.service.RedactionCertificateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the finalize / download / review-gating behaviors that key off source-availability
 * and the FinalizationPolicy applied at finalize time. Other pieces of the controller
 * (review-page rendering, span editing, locks, second-opinion plumbing) are out of scope here.
 */
class ReviewViewControllerTest {

    private DocumentRepository documentRepository;
    private SpanRepository spanRepository;
    private BatchRepository batchRepository;
    private ComplianceProfileRepository complianceProfileRepository;
    private UserGroupsService userGroupsService;
    private AuditLogService auditLogService;
    private OllamaInstanceRepository ollamaInstanceRepository;
    private LlmJudgeDefaultsService llmJudgeDefaultsService;
    private UserSettingsService userSettingsService;
    private UserRepository userRepository;
    private ApprovalRuleEvaluator approvalRuleEvaluator;
    private DocumentLockService documentLockService;
    private OpenSearchIndexService openSearchIndexService;
    private RedactionCertificateService redactionCertificateService;
    private FinalizationPolicyRepository finalizationPolicyRepository;
    private ai.philterd.workspace.service.DocumentDeliveryService deliveryService;
    private ReviewViewController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        spanRepository = mock(SpanRepository.class);
        batchRepository = mock(BatchRepository.class);
        complianceProfileRepository = mock(ComplianceProfileRepository.class);
        userGroupsService = mock(UserGroupsService.class);
        auditLogService = mock(AuditLogService.class);
        // hashForAudit is called from controllers when building audit details (R2-F7/R2-F8).
        // Default the mock to return "" for any input so Map.of(...) doesn't NPE when a
        // particular test doesn't care about the hash value. Tests that DO care can
        // override with their own stub.
        when(auditLogService.hashForAudit(any())).thenReturn("");
        ollamaInstanceRepository = mock(OllamaInstanceRepository.class);
        llmJudgeDefaultsService = mock(LlmJudgeDefaultsService.class);
        userSettingsService = mock(UserSettingsService.class);
        userRepository = mock(UserRepository.class);
        approvalRuleEvaluator = mock(ApprovalRuleEvaluator.class);
        documentLockService = mock(DocumentLockService.class);
        openSearchIndexService = mock(OpenSearchIndexService.class);
        redactionCertificateService = mock(RedactionCertificateService.class);
        finalizationPolicyRepository = mock(FinalizationPolicyRepository.class);

        final ai.philterd.workspace.service.GeneralSettingsService generalSettingsService =
                mock(ai.philterd.workspace.service.GeneralSettingsService.class);
        when(generalSettingsService.load()).thenReturn(new ai.philterd.workspace.model.GeneralSettings());
        deliveryService = mock(ai.philterd.workspace.service.DocumentDeliveryService.class);
        final ai.philterd.workspace.service.DocumentChangeGuard changeGuard =
                new ai.philterd.workspace.service.DocumentChangeGuard(documentRepository, auditLogService);
        controller = new ReviewViewController(documentRepository, spanRepository, batchRepository,
                complianceProfileRepository, userGroupsService,
                new ai.philterd.workspace.service.DocumentAccessService(batchRepository, documentRepository,
                        new ai.philterd.workspace.service.BatchAccessService(batchRepository, userGroupsService)),
                auditLogService,
                ollamaInstanceRepository, llmJudgeDefaultsService, userSettingsService,
                userRepository, approvalRuleEvaluator, openSearchIndexService, documentLockService,
                generalSettingsService, deliveryService, changeGuard,
                new ai.philterd.workspace.service.DocumentFinalizationService(documentRepository, spanRepository,
                        batchRepository, finalizationPolicyRepository, redactionCertificateService, documentLockService,
                        changeGuard, openSearchIndexService, deliveryService, auditLogService));

        // Default lock-acquire behavior: succeed by routing the request through the
        // mocked DocumentRepository so the returned doc carries the test's stubbed
        // state. Specific tests override this for the lock-lost scenarios.
        org.mockito.Mockito.when(documentLockService.acquire(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    final String id = inv.getArgument(0);
                    return documentRepository.findById(id).orElse(null);
                });

        // Certificate generation always returns a stub so the audit-log payload doesn't NPE.
        final RedactionCertificate cert = new RedactionCertificate();
        cert.setId("cert-1");
        cert.setDocumentHash("hash-1");
        when(redactionCertificateService.generate(any(), any())).thenReturn(cert);
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }

    private static Document approvedDoc(final String id, final String batchId, final String original) {
        final Document d = new Document();
        d.setId(id);
        d.setBatchId(batchId);
        d.setStatus("APPROVED");
        d.setFilename(id + ".txt");
        d.setOriginalText(original);
        return d;
    }

    private static Span approvedSpan(final String id, final String docId, final String type,
                                     final int start, final int end, final String text) {
        final Span s = new Span();
        s.setId(id);
        s.setDocumentId(docId);
        s.setType(type);
        s.setStatus("APPROVED");
        s.setText(text);
        s.setLocation(new Location(start, end, 1, new Coordinates(0, 0, 0, 0)));
        return s;
    }

    // ---------- review() gating ----------

    @Test
    void reviewReturns409WhenSourceCleared() {
        final Document d = approvedDoc("d1", "b1", null);
        d.setStatus("FINALIZED");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.review("d1", admin(), null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("finalization policy"));
    }

    @Test
    void reviewReturns409WhenSourceEmpty() {
        final Document d = approvedDoc("d1", "b1", "");
        d.setStatus("FINALIZED");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.review("d1", admin(), null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    // ---------- finalizeDocument() ----------

    @Test
    void finalizeRejectsNonApproved() {
        final Document d = approvedDoc("d1", "b1", "hello");
        d.setStatus("REVIEW_REQUIRED");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final RedirectAttributes ra = flash();
        final String view = controller.finalizeDocument("d1", admin(), ra);
        assertEquals("redirect:/review/d1", view);
        assertEquals("Only APPROVED documents can be finalized.", error(ra));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void finalizeRedirectsToQueueOnSuccess() {
        final Document d = approvedDoc("d1", "b1", "hello");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>());

        final RedirectAttributes ra = flash();
        final String view = controller.finalizeDocument("d1", admin(), ra);
        assertEquals("redirect:/queue", view);
    }

    @Test
    void finalizeMissingDocumentRedirectsToQueue() {
        when(documentRepository.findById("ghost")).thenReturn(Optional.empty());

        final RedirectAttributes ra = flash();
        final String view = controller.finalizeDocument("ghost", admin(), ra);
        assertEquals("redirect:/queue", view);
        assertEquals("Document not found.", error(ra));
    }

    @Test
    void finalizePersistsRenderedRedactedTextAndChangesStatus() {
        final Document d = approvedDoc("d1", "b1", "Hi Bob, your SSN is 123-45-6789.");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>(List.of(
                approvedSpan("s1", "d1", "ssn", 20, 31, "123-45-6789"))));

        controller.finalizeDocument("d1", admin(), flash());

        final ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(saved.capture());
        assertEquals("FINALIZED", saved.getValue().getStatus());
        assertEquals("Hi Bob, your SSN is <<SSN>>.", saved.getValue().getRedactedText());
    }

    @Test
    void finalizeAuditsDocumentFinalize() {
        final Document d = approvedDoc("d1", "b1", "hello");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>());

        controller.finalizeDocument("d1", admin(), flash());

        verify(auditLogService).log(eq("DOCUMENT_FINALIZE"), eq("Document"), eq("d1"), any());
    }

    @Test
    void finalizeQueuesDeliveryToTheBatchDestinationAfterTheFinalizationPolicy() {
        final Document d = approvedDoc("d1", "b1", "hello");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>());
        final Batch b = new Batch();
        b.setId("b1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(deliveryService.queue(d, b, "admin@x.com")).thenReturn(true);

        controller.finalizeDocument("d1", admin(), flash());

        final org.mockito.InOrder order = org.mockito.Mockito.inOrder(deliveryService, documentRepository);
        order.verify(documentRepository).save(d);
        order.verify(deliveryService).queue(d, b, "admin@x.com");
        order.verify(documentRepository).save(d);
    }

    @Test
    void aFailedDeliveryIsRetriedByABatchLeadButNotByAReviewer() {
        final Document d = approvedDoc("d1", "b1", "hello");
        d.changeStatus("FINALIZED");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        final Batch b = new Batch();
        b.setId("b1");
        b.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(deliveryService.retry(d, "admin@x.com")).thenReturn(null);

        final RedirectAttributes ra = flash();
        assertEquals("redirect:/review/d1", controller.retryDelivery("d1", admin(), ra));
        verify(deliveryService).retry(d, "admin@x.com");

        when(userGroupsService.groupIdsForEmail("rev@x.com")).thenReturn(Set.of("g1"));
        final Authentication reviewer = new UsernamePasswordAuthenticationToken("rev@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
        final ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.retryDelivery("d1", reviewer, flash()));
        assertEquals(403, e.getStatusCode().value());
        verify(deliveryService, never()).retry(d, "rev@x.com");
    }

    // ---------- applyFinalizationPolicy() via finalizeDocument() ----------

    @Test
    void legalHoldPolicyDoesNotMutateDocumentAndAudits() {
        final Document d = approvedDoc("d1", "b1", "hello");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>());

        final Batch b = new Batch();
        b.setId("b1");
        b.setFinalizationPolicyId("fp-hold");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));

        final FinalizationPolicy policy = new FinalizationPolicy();
        policy.setId("fp-hold");
        policy.setName("Legal Hold");
        policy.setOption(FinalizationPolicy.OPTION_LEGAL_HOLD);
        when(finalizationPolicyRepository.findById("fp-hold")).thenReturn(Optional.of(policy));

        controller.finalizeDocument("d1", admin(), flash());

        // Source/storage untouched.
        assertEquals("hello", d.getOriginalText());
        verify(spanRepository, never()).deleteByDocumentId(anyString());
        verify(documentRepository, never()).deleteById(anyString());

        final ArgumentCaptor<Map<String, Object>> details = capturingDetails("FINALIZATION_POLICY_APPLIED", "d1");
        assertEquals("RETAIN", details.getValue().get("action"));
        assertEquals("LEGAL_HOLD", details.getValue().get("option"));
        assertEquals("Legal Hold", details.getValue().get("policyName"));
    }

    @Test
    void deleteImmediatelyClearsSourceAndDeletesSpansButKeepsDocument() {
        final Document d = approvedDoc("d1", "b1", "hello");
        d.setStoragePath("/tmp/d1.txt");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>());

        final Batch b = new Batch();
        b.setId("b1");
        b.setFinalizationPolicyId("fp-del");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));

        final FinalizationPolicy policy = new FinalizationPolicy();
        policy.setId("fp-del");
        policy.setName("Delete Immediately");
        policy.setOption(FinalizationPolicy.OPTION_DELETE_IMMEDIATELY);
        when(finalizationPolicyRepository.findById("fp-del")).thenReturn(Optional.of(policy));
        when(spanRepository.deleteByDocumentId("d1")).thenReturn(3L);

        controller.finalizeDocument("d1", admin(), flash());

        // Source content is wiped but the Document record is kept (queue still shows it,
        // certificate stays linkable).
        assertNull(d.getOriginalText());
        assertNull(d.getStoragePath());
        verify(spanRepository).deleteByDocumentId("d1");
        verify(documentRepository, never()).deleteById(anyString());
        // documentRepository.save() is called twice — once for FINALIZED + redactedText,
        // once after the source clear.
        verify(documentRepository, times(2)).save(d);

        final ArgumentCaptor<Map<String, Object>> details = capturingDetails("FINALIZATION_POLICY_APPLIED", "d1");
        assertEquals("SOURCE_CLEARED", details.getValue().get("action"));
        assertEquals("DELETE_IMMEDIATELY", details.getValue().get("option"));
        assertEquals(3L, details.getValue().get("spansDeleted"));
        // The index holds the original text too, so it is removed there as well.
        verify(openSearchIndexService).removeDocument("d1");
        assertEquals(false, details.getValue().get("indexCleared"), "the mock reports the removal as not done");
    }

    @Test
    void noBatchPolicyMeansNoFinalizationAuditOrMutation() {
        final Document d = approvedDoc("d1", "b1", "hello");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>());

        final Batch b = new Batch();
        b.setId("b1");
        b.setFinalizationPolicyId(null);
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));

        controller.finalizeDocument("d1", admin(), flash());

        verify(finalizationPolicyRepository, never()).findById(anyString());
        verify(auditLogService, never()).log(eq("FINALIZATION_POLICY_APPLIED"), any(), any(), any());
        // Source untouched.
        assertEquals("hello", d.getOriginalText());
    }

    // ---------- download() ----------

    @Test
    void downloadReturns409WhenNotFinalized() {
        final Document d = approvedDoc("d1", "b1", "hello");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.download("d1", admin()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("finalized"));
    }

    @Test
    void downloadUsesPersistedRedactedText() {
        final Document d = approvedDoc("d1", "b1", null);
        d.setStatus("FINALIZED");
        d.setRedactedText("Hi Bob, your SSN is <<SSN>>.");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final ResponseEntity<Resource> response = controller.download("d1", admin());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        final byte[] body = ((org.springframework.core.io.ByteArrayResource) response.getBody()).getByteArray();
        assertEquals("Hi Bob, your SSN is <<SSN>>.", new String(body, java.nio.charset.StandardCharsets.UTF_8));
        assertNotNull(response.getHeaders().getFirst("Content-Disposition"));
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("d1_redacted.txt"));
        // Spans should NOT be queried when redactedText is already cached on the document.
        verify(spanRepository, never()).findByDocumentId(anyString());
    }

    @Test
    void aPdfUploadDownloadsAsTextNamedAndTypedAsText() {
        // #66: the download is the redacted text, so a PDF upload downloads as .txt, not as a .pdf of text.
        final Document d = approvedDoc("d1", "b1", null);
        d.setFilename("filing.pdf");
        d.setStatus("FINALIZED");
        d.setRedactedText("Filed by <<PERSON>>.");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(auditLogService.hashForAudit("filing_redacted.txt")).thenReturn("hashed");

        final ResponseEntity<Resource> response = controller.download("d1", admin());

        final org.springframework.http.ContentDisposition disposition = org.springframework.http.ContentDisposition
                .parse(response.getHeaders().getFirst("Content-Disposition"));
        assertTrue(disposition.isAttachment());
        assertEquals("filing_redacted.txt", disposition.getFilename());
        assertEquals("text/plain;charset=UTF-8", response.getHeaders().getContentType().toString());
        final ArgumentCaptor<Map<String, Object>> details = capturingDetails("DOCUMENT_DOWNLOAD", "d1");
        assertEquals("hashed", details.getValue().get("filenameHash"), "the filename is recorded only as a hash");
    }

    @Test
    void aNameOutsideAsciiIsSentEncoded() {
        final Document d = approvedDoc("d1", "b1", null);
        d.setFilename("Müller \"brief\".txt");
        d.setStatus("FINALIZED");
        d.setRedactedText("x");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final String disposition = controller.download("d1", admin()).getHeaders().getFirst("Content-Disposition");

        assertEquals(org.springframework.http.ContentDisposition.parse(disposition).getFilename(),
                "Müller \"brief\"_redacted.txt");
        assertTrue(disposition.contains("filename*=UTF-8''"), disposition);
    }

    @Test
    void downloadFallsBackToRenderingWhenRedactedTextNotPersisted() {
        // Legacy doc: FINALIZED prior to the redactedText persistence change.
        final Document d = approvedDoc("d1", "b1", "Hi Bob, your SSN is 123-45-6789.");
        d.setStatus("FINALIZED");
        d.setRedactedText(null);
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>(List.of(
                approvedSpan("s1", "d1", "ssn", 20, 31, "123-45-6789"))));

        final ResponseEntity<Resource> response = controller.download("d1", admin());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        final String body = new String(
                ((org.springframework.core.io.ByteArrayResource) response.getBody()).getByteArray(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("Hi Bob, your SSN is <<SSN>>.", body);
    }

    @Test
    void downloadAuditsDocumentDownload() {
        final Document d = approvedDoc("d1", "b1", null);
        d.setStatus("FINALIZED");
        d.setRedactedText("redacted");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        when(auditLogService.hashForAudit("d1_redacted.txt")).thenReturn("hashed-filename");

        controller.download("d1", admin());

        final ArgumentCaptor<Map<String, Object>> details = capturingDetails("DOCUMENT_DOWNLOAD", "d1");
        assertEquals("admin@x.com", details.getValue().get("actor"));
        assertEquals(8, details.getValue().get("bytes"));
        // R2-F7: filename is hashed (filenames carry PII in this product and the
        // audit log is cross-group-readable). The controller must hand the filename
        // through auditLogService.hashForAudit; the actual HMAC behaviour is
        // pinned by AuditLogServiceTest.
        org.junit.jupiter.api.Assertions.assertFalse(details.getValue().containsKey("filename"),
                "audit details must not contain the cleartext filename — that's the R2-F7 leak");
        org.junit.jupiter.api.Assertions.assertEquals("hashed-filename",
                details.getValue().get("filenameHash"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "d1_redacted.txt".length(),
                details.getValue().get("filenameLength"));
    }

    @Test
    void downloadDoesNotIncludeRejectedSpans() {
        final Document d = approvedDoc("d1", "b1", "alpha bravo charlie");
        d.setStatus("FINALIZED");
        d.setRedactedText(null);
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        final Span rejected = approvedSpan("s1", "d1", "name", 6, 11, "bravo");
        rejected.setStatus("REJECTED");
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>(List.of(rejected)));

        final ResponseEntity<Resource> response = controller.download("d1", admin());
        final String body = new String(
                ((org.springframework.core.io.ByteArrayResource) response.getBody()).getByteArray(),
                java.nio.charset.StandardCharsets.UTF_8);
        // Rejected span text is preserved verbatim; only APPROVED spans are masked.
        assertEquals("alpha bravo charlie", body);
        assertFalse(body.contains("<<NAME>>"));
    }

    // ---------- unapprove() / unreject() access checks ----------

    private static Authentication userIn(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void unapproveUnrejectAndFinalizeWaitForWhoeverHoldsTheReviewLock() {
        final Document d = approvedDoc("d1", "b1", "hello");
        d.getApprovedBy().add("first@example.com");
        d.setLockedBy("holder@example.com");
        d.setLockExpiresAt(java.time.Instant.now().plusSeconds(600));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));

        for (String action : List.of("unapprove", "unreject", "finalize")) {
            final RedirectAttributes ra = flash();
            final String view = switch (action) {
                case "unapprove" -> controller.unapprove("d1", admin(), ra);
                case "unreject" -> controller.unreject("d1", admin(), ra);
                default -> controller.finalizeDocument("d1", admin(), ra);
            };
            assertEquals("redirect:/review/d1", view, action);
            assertEquals("holder@example.com is reviewing this document. Wait for them to finish, or ask an approver "
                    + "to release it.", error(ra), action);
        }
        assertEquals("APPROVED", d.getStatus());
        assertEquals(List.of("first@example.com"), d.getApprovedBy());
        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void unapproveRejectsNonAdminOutsideGroupAndDoesNotMutate() {
        // Document lives in batch b1 / group g1; caller alice is only in g2 — must be denied.
        final Document d = approvedDoc("d1", "b1", "hello");
        d.getApprovedBy().add("bob@x.com");
        d.getApprovedBy().add("carol@x.com");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        final Batch b = new Batch();
        b.setId("b1");
        b.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g2"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.unapprove("d1", userIn("alice@x.com"), flash()));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        // The fix: clearApprovals must not run before the access check.
        assertEquals(2, d.getApprovedBy().size(),
                "approvedBy was wiped despite no access — IDOR write regression");
        verify(documentRepository, never()).save(any());
        verify(auditLogService, never()).log(eq("DOCUMENT_UNAPPROVE"), any(), any(), any());
    }

    @Test
    void unrejectRejectsNonAdminOutsideGroupAndDoesNotMutate() {
        final Document d = approvedDoc("d1", "b1", "hello");
        d.setStatus("REJECTED");
        d.getApprovedBy().add("bob@x.com");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        final Batch b = new Batch();
        b.setId("b1");
        b.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g2"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.unreject("d1", userIn("alice@x.com"), flash()));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals(1, d.getApprovedBy().size());
        verify(documentRepository, never()).save(any());
        verify(auditLogService, never()).log(eq("DOCUMENT_UNREJECT"), any(), any(), any());
    }

    @Test
    void unapproveAllowsNonAdminInGroupAndClearsApprovers() {
        final Document d = approvedDoc("d1", "b1", "hello");
        d.getApprovedBy().add("bob@x.com");
        d.getApprovedBy().add("carol@x.com");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        final Batch b = new Batch();
        b.setId("b1");
        b.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));

        final String view = controller.unapprove("d1", userIn("alice@x.com"), flash());

        assertEquals("redirect:/review/d1", view);
        assertTrue(d.getApprovedBy().isEmpty());
        verify(auditLogService).log(eq("DOCUMENT_UNAPPROVE"), eq("Document"), eq("d1"), any());
    }

    @Test
    void unapproveOnFinalizedDocumentRefusesEvenForOwner() {
        final Document d = approvedDoc("d1", "b1", "hello");
        d.setStatus("FINALIZED");
        d.getApprovedBy().add("bob@x.com");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        final Batch b = new Batch();
        b.setId("b1");
        b.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(userGroupsService.groupIdsForEmail("alice@x.com")).thenReturn(Set.of("g1"));

        final RedirectAttributes ra = flash();
        final String view = controller.unapprove("d1", userIn("alice@x.com"), ra);

        assertEquals("redirect:/review/d1", view);
        assertEquals("Document is FINALIZED. Finalized documents cannot be reopened.", error(ra));
        // Approvers are preserved on FINALIZED — the document is locked entirely.
        assertEquals(1, d.getApprovedBy().size());
        verify(documentRepository, never()).save(any());
        verify(auditLogService, never()).log(eq("DOCUMENT_UNAPPROVE"), any(), any(), any());
    }

    @Test
    void unapproveMissingDocumentReturnsGenericNotFound() {
        when(documentRepository.findById("ghost")).thenReturn(Optional.empty());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.unapprove("ghost", admin(), flash()));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Document not found.", ex.getReason());
    }

    // ---------- approve()/reject() lock + version contract (fix #3) ----------

    private Document reviewableDoc(final String id) {
        final Document d = approvedDoc(id, "b1", "hello world");
        d.setStatus("REVIEW_REQUIRED");
        d.setBatchId("b1");
        when(documentRepository.findById(id)).thenReturn(Optional.of(d));
        // Approval rules + batch stubs default to "single approval, batch loadable".
        final ai.philterd.workspace.model.Batch b = new ai.philterd.workspace.model.Batch();
        b.setId("b1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(approvalRuleEvaluator.dualApprovalRequired(any(), any(), any(), any()))
                .thenReturn(false);
        when(spanRepository.findByDocumentId(id)).thenReturn(java.util.List.of());
        return d;
    }

    @Test
    void approveRefusesWhenLockHeldByAnotherReviewer() {
        // The default setUp() stub makes acquire() succeed. Override it for this test
        // to simulate Bob trying to approve while Alice still holds the lock.
        reviewableDoc("d1");
        when(documentLockService.acquire(eq("d1"), eq("admin@x.com"))).thenReturn(null);

        final RedirectAttributes ra = flash();
        final String view = controller.approve("d1", admin(), ra);

        assertEquals("redirect:/review/d1", view);
        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("another reviewer"),
                "expected lock-lost error, got: " + error(ra));
        // The approval was never recorded — no save, no audit, no review-count bump.
        verify(documentRepository, never()).save(any(Document.class));
        verify(auditLogService, never()).log(eq("DOCUMENT_APPROVAL"), any(), any(), any());
    }

    @Test
    void rejectRefusesWhenLockHeldByAnotherReviewer() {
        reviewableDoc("d1");
        when(documentLockService.acquire(eq("d1"), eq("admin@x.com"))).thenReturn(null);

        final RedirectAttributes ra = flash();
        final String view = controller.reject("d1", admin(), ra);

        assertEquals("redirect:/review/d1", view);
        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("another reviewer"));
        verify(documentRepository, never()).save(any(Document.class));
        verify(auditLogService, never()).log(eq("DOCUMENT_REJECT"), any(), any(), any());
    }

    @Test
    void approveSurfacesOptimisticLockingFailureAsLockLost() {
        // Acquire succeeds (the pessimistic lock), but the actual save throws
        // OptimisticLockingFailureException because another reviewer's save sneaked
        // in across a lock-expiry boundary. The mutation must be refused, not
        // silently retried with stale state.
        final Document d = reviewableDoc("d1");
        when(documentRepository.save(any(Document.class)))
                .thenThrow(new OptimisticLockingFailureException("stale version"));

        final RedirectAttributes ra = flash();
        final String view = controller.approve("d1", admin(), ra);

        assertEquals("redirect:/review/d1", view);
        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("another reviewer"),
                "expected stale-version error, got: " + error(ra));
        // No audit row written when the save was refused.
        verify(auditLogService, never()).log(eq("DOCUMENT_APPROVAL"), any(), any(), any());
    }

    @Test
    void rejectSurfacesOptimisticLockingFailureAsLockLost() {
        reviewableDoc("d1");
        when(documentRepository.save(any(Document.class)))
                .thenThrow(new OptimisticLockingFailureException("stale version"));

        final RedirectAttributes ra = flash();
        final String view = controller.reject("d1", admin(), ra);

        assertEquals("redirect:/review/d1", view);
        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("another reviewer"));
        verify(auditLogService, never()).log(eq("DOCUMENT_REJECT"), any(), any(), any());
    }

    @Test
    void approveAcquiresAndReleasesLockOnHappyPath() {
        // Pin the lifecycle: lock acquired before mutation, released after save +
        // audit. Both the pessimistic-lock and audit-row writes happen — proof the
        // happy path still flows.
        reviewableDoc("d1");
        // The advance-to-next setting feeds into the post-save redirect logic; default
        // to a real (no-advance) UserSettings so we don't NPE chasing a sibling doc.
        when(userSettingsService.loadForEmail(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ai.philterd.workspace.model.UserSettings());

        final RedirectAttributes ra = flash();
        controller.approve("d1", admin(), ra);

        final org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                documentLockService, documentRepository, auditLogService);
        // Contract: acquire → save → release → audit. Release happens immediately
        // after the save so the lock is freed even on the audit-write path, and
        // the audit row captures the post-release state.
        order.verify(documentLockService).acquire(eq("d1"), eq("admin@x.com"));
        order.verify(documentRepository).save(any(Document.class));
        order.verify(documentLockService).release(eq("d1"), eq("admin@x.com"));
        order.verify(auditLogService).log(eq("DOCUMENT_APPROVAL"), any(), eq("d1"), any());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> capturingDetails(final String action, final String entityId) {
        final ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq(action), eq("Document"), eq(entityId), captor.capture());
        return captor;
    }

    // ---- approvers (#5) ----

    /** d1 in batch b1 of group g1, needing two approvals, the second reserved for an approver. */
    private Document approverDoc() {
        final Document d = reviewableDoc("d1");
        final Batch b = new Batch();
        b.setId("b1");
        b.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(approvalRuleEvaluator.dualApprovalRequired(any(), any(), any(), any())).thenReturn(true);
        when(approvalRuleEvaluator.approverRequired(any(), any(), any(), any())).thenReturn(true);
        when(userGroupsService.groupIdsForEmail(anyString())).thenReturn(Set.of("g1"));
        when(userGroupsService.approverGroupIdsForEmail("ann@x.com")).thenReturn(Set.of("g1"));
        when(documentLockService.acquire(eq("d1"), anyString())).thenReturn(d);
        when(userSettingsService.loadForEmail(anyString())).thenReturn(new ai.philterd.workspace.model.UserSettings());
        return d;
    }

    @Test
    void theSecondApprovalReservedForAnApproverIsRefusedFromAnyoneElse() {
        final Document d = approverDoc();
        controller.approve("d1", userIn("rob@x.com"), flash());
        assertEquals(List.of("rob@x.com"), d.getApprovedBy(), "the first approval may come from anyone");
        assertEquals("REVIEW_REQUIRED", d.getStatus());

        final RedirectAttributes ra = flash();
        controller.approve("d1", userIn("cal@x.com"), ra);
        assertEquals("This document's second approval must come from an approver of its group.", error(ra));
        assertEquals(List.of("rob@x.com"), d.getApprovedBy());
        assertEquals("REVIEW_REQUIRED", d.getStatus());

        controller.approve("d1", userIn("ann@x.com"), flash());
        assertEquals(List.of("rob@x.com", "ann@x.com"), d.getApprovedBy());
        assertEquals("APPROVED", d.getStatus());
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService, times(2)).log(eq("DOCUMENT_APPROVAL"), eq("Document"), eq("d1"), details.capture());
        assertEquals(true, details.getValue().get("approverRequired"));
    }

    @Test
    void anApproverWhoGaveTheFirstApprovalStillNeedsAnotherApprover() {
        final Document d = approverDoc();
        controller.approve("d1", userIn("ann@x.com"), flash());
        final RedirectAttributes ra = flash();
        controller.approve("d1", userIn("rob@x.com"), ra);
        assertEquals("This document's second approval must come from an approver of its group.", error(ra));
        assertEquals("REVIEW_REQUIRED", d.getStatus());
    }

    @Test
    void withoutTheOptionAnyOtherReviewerGivesTheSecondApproval() {
        final Document d = approverDoc();
        when(approvalRuleEvaluator.approverRequired(any(), any(), any(), any())).thenReturn(false);
        controller.approve("d1", userIn("rob@x.com"), flash());
        controller.approve("d1", userIn("cal@x.com"), flash());
        assertEquals("APPROVED", d.getStatus());
    }

    @Test
    void anApproverOfTheGroupReleasesADocumentAnotherReviewerHolds() {
        final Document d = approverDoc();
        d.setLockedBy("rob@x.com");

        final RedirectAttributes ra = flash();
        controller.breakLock("d1", userIn("ann@x.com"), ra);
        verify(documentLockService).breakLock("d1");
        assertEquals("Lock previously held by rob@x.com has been cleared.", ra.getFlashAttributes().get("success"));
        final ArgumentCaptor<Map<String, Object>> details = capturingDetails("DOCUMENT_LOCK_BROKEN", "d1");
        assertEquals(true, details.getValue().get("asApprover"));
    }

    @Test
    void aReviewerWhoIsNotAnApproverCannotReleaseADocumentAndAnOutsiderCannotSeeIt() {
        approverDoc();
        final RedirectAttributes ra = flash();
        controller.breakLock("d1", userIn("cal@x.com"), ra);
        assertEquals("Only an administrator or an approver of this document's group can release it.", error(ra));

        when(userGroupsService.groupIdsForEmail("oz@x.com")).thenReturn(Set.of("g2"));
        when(userGroupsService.approverGroupIdsForEmail("oz@x.com")).thenReturn(Set.of("g2"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.breakLock("d1", userIn("oz@x.com"), flash()));
        verify(documentLockService, never()).breakLock(anyString());
    }
}
