/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp;

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Coordinates;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.model.UserSettings;
import ai.philterd.workspace.service.ApprovalRuleEvaluator;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.BatchAccessService;
import ai.philterd.workspace.service.DocumentAccessService;
import ai.philterd.workspace.service.DocumentLockService;
import ai.philterd.workspace.service.DocumentSearchService;
import ai.philterd.workspace.service.GeneralSettingsService;
import ai.philterd.workspace.service.LlmJudgeDefaultsService;
import ai.philterd.workspace.service.RedactionCertificateService;
import ai.philterd.workspace.service.UserGroupsService;
import ai.philterd.workspace.service.UserSettingsService;
import ai.philterd.workspace.webapp.controllers.ReviewViewController;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The review page is read-only for auditors (#60) and unchanged for reviewers, and shows a delivery's state (#24). */
@WebMvcTest(controllers = ReviewViewController.class)
@Import({SecurityConfig.class, DocumentAccessService.class, BatchAccessService.class})
class ReviewPageReadOnlyTest extends WebMvcSliceMocks {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private UserGroupsService userGroupsService;
    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private LlmJudgeDefaultsService llmJudgeDefaultsService;
    @MockitoBean private UserSettingsService userSettingsService;
    @MockitoBean private ApprovalRuleEvaluator approvalRuleEvaluator;
    @MockitoBean private DocumentSearchService documentSearchService;
    @MockitoBean private DocumentLockService documentLockService;
    @MockitoBean private RedactionCertificateService redactionCertificateService;
    @MockitoBean private GeneralSettingsService generalSettingsService;

    private Document document;

    @BeforeEach
    void setUp() {
        document = new Document();
        document.setId("d1");
        document.setBatchId("b1");
        document.setFilename("motion.txt");
        document.setOriginalText("Jane Roe wrote.");
        document.setStatus("REVIEW_REQUIRED");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(document));
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        when(userGroupsService.groupIdsForEmail(anyString())).thenReturn(Set.of("g1"));
        final Span span = new Span();
        span.setId("s1");
        span.setDocumentId("d1");
        span.setType("person");
        span.setText("Jane Roe");
        span.setStatus("PENDING");
        span.setConfidence(0.9);
        span.setCreatedAt(LocalDateTime.now());
        span.setLocation(new Location(0, 8, 1, new Coordinates(0, 0, 0, 0)));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new java.util.ArrayList<>(List.of(span)));
        when(userSettingsService.loadForEmail(any())).thenReturn(new UserSettings());
        when(approvalRuleEvaluator.approvalsRequired(any(), any(), any())).thenReturn(1);
        when(generalSettingsService.load()).thenReturn(new GeneralSettings());
        when(llmJudgeDefaultsService.load()).thenReturn(new ai.philterd.workspace.model.LlmJudgeDefaults());
        when(ollamaInstanceRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());
        when(documentRepository.findByBatchId("b1")).thenReturn(new java.util.ArrayList<>(List.of(document)));
        when(documentLockService.acquire(anyString(), anyString())).thenReturn(document);
    }

    private String page(final String role) throws Exception {
        return mockMvc.perform(get("/review/d1").with(signedIn("someone", role)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void anAuditorGetsAReadOnlyPageAndTakesNoLock() throws Exception {
        final String html = page("AUDITOR");

        assertTrue(html.contains("const readOnly = true;"), "the span controls follow the read-only flag");
        assertTrue(html.contains("const documentLocked = readOnly ||"));
        assertFalse(html.contains("/review/d1/approve") || html.contains("/review/d1/reject"), "no decision forms");
        assertFalse(html.contains("/review/d1/release"), "Back to queue is a link, not a write");
        assertTrue(html.contains("href=\"/queue\""));
        assertTrue(html.matches("(?s).*id=\"explainBtn\"[^>]*\\sdisabled=\"disabled\".*"), "Explain is disabled");
        assertTrue(html.matches("(?s).*id=\"commentsSaveBtn\"[^>]*\\sdisabled=\"disabled\".*"), "comments cannot be saved");
        assertFalse(html.contains("id=\"editCaseBtn\""));
        assertTrue(html.contains("Read-only access"));
        assertTrue(html.contains("id=\"focusToggle\""), "Focus stays");
        verify(documentLockService, never()).acquire(anyString(), anyString());
    }

    @Test
    void aReviewerSeesEveryActionAsBefore() throws Exception {
        final String html = page("USER");

        assertTrue(html.contains("const readOnly = false;"));
        assertTrue(html.contains("/review/d1/approve") && html.contains("/review/d1/reject"));
        assertTrue(html.contains("/review/d1/release"));
        assertFalse(html.matches("(?s).*id=\"explainBtn\"[^>]*\\sdisabled=\"disabled\".*"));
        assertFalse(html.matches("(?s).*id=\"commentsSaveBtn\"[^>]*\\sdisabled=\"disabled\".*"));
        assertTrue(html.contains("id=\"editCaseBtn\""));
        assertFalse(html.contains("Read-only access"));
        verify(documentLockService).acquire("d1", "someone");
    }

    @Test
    void aFailedDeliveryIsShownToEveryoneAndOnlyAnAdministratorCanRetryIt() throws Exception {
        document.setStatus("FINALIZED");
        document.setDeliveryStatus("FAILED");
        document.setDeliveryAttempts(4);
        document.setDeliveryDestination("Local directory: staging");
        document.setDeliveryError("Directory does not exist: /srv/staging");

        final String admin = page("ADMIN");
        assertTrue(admin.contains("Delivery to <span class=\"font-medium\">Local directory: staging</span> failed"), admin);
        assertTrue(admin.contains("Directory does not exist: /srv/staging"));
        assertTrue(admin.contains("action=\"/review/d1/delivery/retry\""), "an administrator can retry");

        final String auditor = page("AUDITOR");
        assertTrue(auditor.contains("Directory does not exist: /srv/staging"), "auditors see the failure");
        assertFalse(auditor.contains("/review/d1/delivery/retry"), "but cannot retry");
        assertFalse(page("USER").contains("/review/d1/delivery/retry"), "nor can a reviewer");
    }

    @Test
    void aPendingRetryAndADeliveryAreDescribed() throws Exception {
        document.setStatus("FINALIZED");
        document.setDeliveryStatus("PENDING");
        document.setDeliveryAttempts(1);
        document.setDeliveryDestination("Amazon S3: staging");
        document.setDeliveryError("Could not write to S3: Access Denied");
        document.setDeliveryNextAttemptAt(java.time.Instant.parse("2026-10-31T12:01:00Z"));
        final String pending = page("ADMIN").replaceAll("\\s+", " ");
        assertTrue(pending.contains("is pending: attempt <span>1</span> failed (<span>Could not write to S3: Access Denied</span>)"
                + " and is retried at"), pending);
        assertFalse(pending.contains("/review/d1/delivery/retry"), "nothing to retry yet");

        document.setDeliveryStatus("DELIVERED");
        document.setDeliveredAt(java.time.Instant.parse("2026-10-31T12:01:02Z"));
        assertTrue(page("ADMIN").replaceAll("\\s+", " ").contains("Delivered to <span class=\"font-medium\">Amazon S3: staging</span>"));

        document.setDeliveryStatus(null);
        assertFalse(page("ADMIN").contains("id=\"deliveryStatus\""), "no destination, no delivery line");
    }

    @Test
    void openingAnApprovedRejectedOrFinalizedDocumentTakesNoLock() throws Exception {
        // Its spans cannot change until it is reopened, and a lock taken by viewing it would hold up Finalize,
        // Unapprove, and Unreject for everyone else (#71, #67).
        for (String status : List.of("APPROVED", "REJECTED", "FINALIZED")) {
            document.setStatus(status);
            page("USER");
        }
        verify(documentLockService, never()).acquire(anyString(), anyString());
    }

    /** Approved by another reviewer and selected for blind double review (#86). */
    private void approvedForBlindReview() {
        document.setStatus("APPROVED");
        document.setDoubleReview(true);
        document.setFirstReviewer("first@example.com");
        document.getApprovedBy().add("first@example.com");
    }

    @Test
    void someoneWhoMayDoTheBlindReviewSeesTheTextWithoutAnySpan() throws Exception {
        approvedForBlindReview();

        final String html = page("USER");

        assertTrue(html.contains("const blindLanding = true;"));
        assertTrue(html.contains("const originalRedactions = [];"), "no span, and so no decision, is sent");
        assertTrue(html.contains("/review/d1/blind-review/start"));
        assertFalse(html.contains("/review/d1/unapprove"), "the first review's decision is not shown");
        assertFalse(html.contains("first@example.com"));
        assertTrue(html.contains("Blind second review"));
        verify(documentLockService, never()).acquire(anyString(), anyString());
    }

    @Test
    void theBlindReviewerWorksOnTheirOwnCopy() throws Exception {
        approvedForBlindReview();
        document.setSecondReviewer("someone");
        final ai.philterd.workspace.model.BlindReviewSpan copy = new ai.philterd.workspace.model.BlindReviewSpan();
        copy.setId("c1");
        copy.setDocumentId("d1");
        copy.setType("person");
        copy.setText("Jane Roe");
        copy.setStatus("PENDING");
        copy.setLocation(new Location(0, 8, 1, new Coordinates(0, 0, 0, 0)));
        when(blindDoubleReviewService.copies("d1")).thenReturn(List.of(copy));

        final String html = page("USER");

        assertTrue(html.contains("const blindReview = true;"));
        assertTrue(html.contains("\"id\":\"c1\""), "the copy is shown");
        assertFalse(html.contains("\"id\":\"s1\""), "the document's own span is not");
        assertTrue(html.contains("/review/d1/blind-review/submit"));
        assertFalse(html.contains("/review/d1/approve") || html.contains("/review/d1/unapprove"));
        assertFalse(html.contains("first@example.com"), "who decided the first review is not shown");
        assertTrue(html.contains("let approvalsGiven = 0;"), "the first review's approvals are not shown");
        verify(documentLockService, never()).acquire(anyString(), anyString());
    }
}
