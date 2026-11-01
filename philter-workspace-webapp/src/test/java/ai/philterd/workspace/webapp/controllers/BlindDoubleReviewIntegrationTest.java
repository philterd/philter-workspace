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

import ai.philterd.workspace.api.controller.TriageController;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Coordinates;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.RedactionCertificate;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.model.UserSettings;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.ComplianceProfileRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.FinalizationPolicyRepository;
import ai.philterd.workspace.repository.OllamaInstanceRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.service.ApprovalRuleEvaluator;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.BatchAccessService;
import ai.philterd.workspace.service.DocumentAccessService;
import ai.philterd.workspace.service.DocumentLockService;
import ai.philterd.workspace.service.LlmJudgeDefaultsService;
import ai.philterd.workspace.service.OpenSearchIndexService;
import ai.philterd.workspace.service.RedactionCertificateService;
import ai.philterd.workspace.service.UserGroupsService;
import ai.philterd.workspace.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration tests for the Blind Double Review feature. Two reviewers (Alice and
 * Bob) work documents in a batch with the feature enabled. The tests exercise three pieces of
 * the pipeline together:
 *
 * <ul>
 *     <li>The {@link ReviewViewController} approve/reject paths, where the first reviewer's
 *         identity and the per-reviewer span snapshot are stamped on the {@link Document}.</li>
 *     <li>The {@link TriageController#getQueue queue endpoint}, which hides
 *         {@code APPROVED}/{@code REJECTED} documents from everyone except a second reviewer
 *         who hasn't yet seen the document.</li>
 *     <li>The Previous/Next sibling lookup on the review page, which skips any document the
 *         current user already first-reviewed so the second pass is genuinely blind.</li>
 * </ul>
 *
 * The tests use shared in-memory document and batch state so writes from a controller call
 * are visible to subsequent calls — closer to a true integration test than to isolated unit
 * tests that re-stub each call individually. The complementary IAA-correctness tests live in
 * {@link InterAnnotatorAgreementReportIntegrationTest}.
 */
class BlindDoubleReviewIntegrationTest {

    private static final String BATCH_ID = "b-blind";

    private final Map<String, Document> documents = new HashMap<>();
    private final Map<String, List<Span>> spansByDocument = new HashMap<>();
    private Batch batch;

    private DocumentRepository documentRepository;
    private SpanRepository spanRepository;
    private BatchRepository batchRepository;
    private UserSettingsService userSettingsService;
    private ApprovalRuleEvaluator approvalRuleEvaluator;
    /**
     * Lives at the class level so individual tests can flip its return value before
     * exercising the controller — used by the FTS-gate tests to verify the model
     * attribute follows the persisted flag.
     */
    private ai.philterd.workspace.service.GeneralSettingsService generalSettingsService;

    private ReviewViewController reviewController;
    private TriageController triageController;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        spanRepository = mock(SpanRepository.class);
        batchRepository = mock(BatchRepository.class);

        wireRepositoryStubsToInMemoryState();

        batch = new Batch();
        batch.setId(BATCH_ID);
        batch.setName("Blind Review Batch");
        batch.setBlindDoubleReviewEnabled(true);
        batch.setBlindDoubleReviewPercentage(100);
        when(batchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));

        // Settings: skip-completed is on so Prev/Next exercises the skip path.
        userSettingsService = mock(UserSettingsService.class);
        final UserSettings advancing = new UserSettings();
        advancing.setSkipCompletedInReview(true);
        advancing.setAdvanceToNextOnApprove(true);
        when(userSettingsService.loadForEmail(anyString())).thenReturn(advancing);

        approvalRuleEvaluator = mock(ApprovalRuleEvaluator.class);
        when(approvalRuleEvaluator.dualApprovalRequired(any(), any(), any(), any())).thenReturn(false);

        reviewController = buildReviewController();
        triageController = buildTriageController();
    }

    // ---------- the multi-user happy path ----------

    @Test
    void aliceReviewsThenBobSeesItForSecondReview() {
        // Alice and Bob share access. The batch has Blind Double Review enabled at 100% so
        // every document is sampled into the second-review cohort.
        final Document doc = doubleReviewDoc("d1", "alpha bravo charlie", List.of(
                approvedSpan("s1", "d1", 0, 5),     // alpha
                approvedSpan("s2", "d1", 6, 11)));  // bravo

        // 1) Alice approves. The document transitions to APPROVED, firstReviewer = alice,
        //    and her two-span snapshot is captured.
        reviewController.approve(doc.getId(), alice(), flash());
        assertEquals("APPROVED", doc.getStatus(),
                "First approval should transition status to APPROVED when only one reviewer is required.");
        assertEquals("alice@x.com", doc.getFirstReviewer(),
                "First reviewer should be the user who first approved or rejected the document.");
        assertNotNull(doc.getFirstReviewSpans(),
                "The first reviewer's span snapshot must be captured for IAA computation.");
        assertEquals(2, doc.getFirstReviewSpans().size());
        assertNull(doc.getSecondReviewer(), "Second reviewer should not be set until a different user reviews.");
        assertNull(doc.getSecondReviewSpans(), "Second reviewer's snapshot is captured only on second review.");

        // 2) Alice's queue: the now-APPROVED double-review document should have dropped out —
        //    she did the first review and is disqualified from the second.
        final List<Map<String, Object>> aliceQueue = queueRowsFor(alice());
        assertTrue(aliceQueue.stream().noneMatch(r -> doc.getId().equals(r.get("id"))),
                "First reviewer must not see their own approved double-review documents in the queue.");

        // 3) Bob's queue: the same APPROVED document IS visible because doubleReview = true
        //    and the first reviewer is not Bob.
        final List<Map<String, Object>> bobQueue = queueRowsFor(bob());
        assertTrue(bobQueue.stream().anyMatch(r -> doc.getId().equals(r.get("id"))),
                "An approved double-review document must appear in another reviewer's queue for the second pass.");

        // 4) Bob completes the second review. firstReviewer is unchanged; secondReviewer is
        //    Bob; secondReviewSpans is set to whatever spans were APPROVED at that moment.
        reviewController.approve(doc.getId(), bob(), flash());
        assertEquals("alice@x.com", doc.getFirstReviewer(),
                "First reviewer stays alice across subsequent reviews.");
        assertEquals("bob@x.com", doc.getSecondReviewer());
        assertNotNull(doc.getSecondReviewSpans());
        assertEquals(2, doc.getSecondReviewSpans().size());

        // 5) After both reviews, the queue for both users no longer surfaces the document.
        final List<Map<String, Object>> aliceQueueAfter = queueRowsFor(alice());
        final List<Map<String, Object>> bobQueueAfter = queueRowsFor(bob());
        assertTrue(aliceQueueAfter.stream().noneMatch(r -> doc.getId().equals(r.get("id"))));
        assertTrue(bobQueueAfter.stream().noneMatch(r -> doc.getId().equals(r.get("id"))));
    }

    @Test
    void rejectionAlsoStampsFirstReviewerAndSnapshot() {
        final Document doc = doubleReviewDoc("d2", "alpha bravo", List.of(
                approvedSpan("s3", "d2", 0, 5)));

        reviewController.reject(doc.getId(), alice(), flash());

        assertEquals("REJECTED", doc.getStatus());
        assertEquals("alice@x.com", doc.getFirstReviewer(),
                "Rejection by the first reviewer must stamp them just like approval does.");
        assertNotNull(doc.getFirstReviewSpans(),
                "Rejection must also capture the first reviewer's span snapshot.");
        assertEquals(1, doc.getFirstReviewSpans().size());

        // Bob then performs the second pass via reject (he disagrees).
        reviewController.reject(doc.getId(), bob(), flash());
        assertEquals("bob@x.com", doc.getSecondReviewer());
        assertNotNull(doc.getSecondReviewSpans());
    }

    @Test
    void approvedDocumentWithoutDoubleReviewStaysOutOfTheQueue() {
        // Same setup but doubleReview = false. After approval the document leaves every
        // reviewer's queue — there is no second pass.
        final Document doc = baseDocument("d3", "single-review");
        doc.setDoubleReview(false);
        documents.put(doc.getId(), doc);
        spansByDocument.put(doc.getId(), new ArrayList<>(List.of(approvedSpan("s4", "d3", 0, 6))));

        reviewController.approve(doc.getId(), alice(), flash());

        assertEquals("APPROVED", doc.getStatus());
        // The snapshot is captured for every first reviewer regardless of doubleReview, but
        // the IAA report ignores the document because doubleReview is false — verified
        // separately in {@link InterAnnotatorAgreementReportIntegrationTest}.

        // Neither reviewer's queue should show this APPROVED single-review document.
        for (Authentication who : List.of(alice(), bob())) {
            final List<Map<String, Object>> rows = queueRowsFor(who);
            assertTrue(rows.stream().noneMatch(r -> doc.getId().equals(r.get("id"))),
                    "APPROVED documents without doubleReview=true must never re-surface in the queue.");
        }
    }

    // ---------- Previous / Next skipping ----------

    @Test
    void previousAndNextSkipDocumentsTheCurrentUserAlreadyFirstReviewed() {
        // Three documents in the batch. Alice first-reviewed d-mid; the review page she
        // opens for d-first should advance straight to d-last when she clicks Next, skipping
        // d-mid because she is the first reviewer.
        // Set distinct risk scores so the default sort (riskScore desc, id tie-break)
        // produces the deterministic order d-first → d-mid → d-last regardless of how
        // the in-memory map iterates.
        final Document first = baseDocument("d-first", "first");
        first.setRiskScore(0.9);
        final Document mid = baseDocument("d-mid", "mid");
        mid.setRiskScore(0.5);
        final Document last = baseDocument("d-last", "last");
        last.setRiskScore(0.1);
        for (Document d : List.of(first, mid, last)) {
            documents.put(d.getId(), d);
            spansByDocument.put(d.getId(), new ArrayList<>());
        }
        // Mark mid as a double-review doc that Alice has already first-reviewed.
        mid.setDoubleReview(true);
        mid.setFirstReviewer("alice@x.com");
        mid.setStatus("APPROVED");

        // Open d-first as Alice and inspect the model attributes the controller populated.
        // The findSiblingId logic should skip d-mid because Alice is its first reviewer,
        // so Next should jump to d-last.
        final org.springframework.ui.ConcurrentModel model = new org.springframework.ui.ConcurrentModel();
        reviewController.review("d-first", alice(), model);
        assertEquals("d-last", model.getAttribute("nextDocumentId"),
                "Next must skip a double-review document the current user first-reviewed.");

        // Bob, on the other hand, has not reviewed mid yet, so Next from d-first lands on
        // d-mid for him (the standard sibling order).
        final org.springframework.ui.ConcurrentModel bobModel = new org.springframework.ui.ConcurrentModel();
        reviewController.review("d-first", bob(), bobModel);
        assertEquals("d-mid", bobModel.getAttribute("nextDocumentId"),
                "A reviewer who did NOT do the first review must still see the document in their navigation.");
    }

    // ---------- review page: full-text search gate on the Find similar button ----------

    @Test
    void reviewPageExposesFullTextSearchEnabledFlagWhenItIsOn() {
        // The controller must surface the flag as a model attribute so the template can render the
        // Find similar documents button.
        final ai.philterd.workspace.model.GeneralSettings enabled = new ai.philterd.workspace.model.GeneralSettings();
        enabled.setFullTextSearchEnabled(true);
        when(generalSettingsService.load()).thenReturn(enabled);
        final Document d = baseDocument("d-fts-on", "alpha bravo");
        documents.put(d.getId(), d);
        spansByDocument.put(d.getId(), new ArrayList<>());

        final org.springframework.ui.ConcurrentModel model = new org.springframework.ui.ConcurrentModel();
        reviewController.review("d-fts-on", alice(), model);

        assertEquals(true, model.getAttribute("fullTextSearchEnabled"),
                "When the master flag is on, the review page must expose fullTextSearchEnabled=true.");
    }

    @Test
    void reviewPageHidesFindSimilarOnADefaultInstallation() {
        // Full text search is off by default (#23).
        when(generalSettingsService.load()).thenReturn(new ai.philterd.workspace.model.GeneralSettings());
        final Document d = baseDocument("d-fts-default", "alpha bravo");
        documents.put(d.getId(), d);
        spansByDocument.put(d.getId(), new ArrayList<>());

        final org.springframework.ui.ConcurrentModel model = new org.springframework.ui.ConcurrentModel();
        reviewController.review("d-fts-default", alice(), model);

        assertEquals(false, model.getAttribute("fullTextSearchEnabled"));
    }

    @Test
    void reviewPageHidesFindSimilarWhenFullTextSearchDisabled() {
        // Flip the persisted flag off and exercise the GET. The model attribute must drop
        // to false so the template hides the button (and its modal + script wiring).
        final ai.philterd.workspace.model.GeneralSettings disabled = new ai.philterd.workspace.model.GeneralSettings();
        disabled.setFullTextSearchEnabled(false);
        when(generalSettingsService.load()).thenReturn(disabled);

        final Document d = baseDocument("d-fts-off", "alpha bravo");
        documents.put(d.getId(), d);
        spansByDocument.put(d.getId(), new ArrayList<>());

        final org.springframework.ui.ConcurrentModel model = new org.springframework.ui.ConcurrentModel();
        reviewController.review("d-fts-off", alice(), model);

        assertEquals(false, model.getAttribute("fullTextSearchEnabled"),
                "When the master flag is off, the review page must expose fullTextSearchEnabled=false "
                        + "so the Find similar documents button is not rendered.");
    }

    @Test
    void reviewPageDefaultsFullTextSearchToFalseWhenSettingsLoadThrows() {
        // Defensive: if the settings service throws (e.g. transient Mongo failure during a
        // page render) the controller must default the flag to false rather than show a
        // button that would lead to a broken modal — better to hide a working feature for
        // a moment than to render a broken affordance.
        when(generalSettingsService.load())
                .thenThrow(new RuntimeException("Mongo briefly unreachable"));

        final Document d = baseDocument("d-fts-throw", "alpha bravo");
        documents.put(d.getId(), d);
        spansByDocument.put(d.getId(), new ArrayList<>());

        final org.springframework.ui.ConcurrentModel model = new org.springframework.ui.ConcurrentModel();
        reviewController.review("d-fts-throw", alice(), model);

        assertEquals(false, model.getAttribute("fullTextSearchEnabled"));
    }

    // ---------- negative & edge cases on the queue filter ----------

    @Test
    void queueHidesApprovedDoubleReviewWhenAuthenticationHasNoEmail() {
        // Defensive: if a request reaches the queue without an authenticated principal,
        // the filter cannot match a "first reviewer != current user" predicate, so the
        // safe default is to hide the APPROVED double-review document.
        final Document doc = doubleReviewDoc("d-noauth", "alpha bravo", List.of());
        doc.setStatus("APPROVED");
        doc.setFirstReviewer("alice@x.com");

        final Page<Map<String, Object>> page = triageController.getQueue(
                0, 50, null, null, null, false, "riskScore", "desc", null);
        assertTrue(page.getContent().stream().noneMatch(r -> doc.getId().equals(r.get("id"))),
                "Anonymous calls must not see APPROVED double-review documents — there is no current "
                        + "user to disqualify against, so the safe default is to hide.");
    }

    @Test
    void queueHidesDoubleReviewDocumentsWithNoFirstReviewerStamped() {
        // Defensive: a doubleReview-flagged document that somehow reached APPROVED without a
        // firstReviewer stamp is malformed data. The filter must hide it rather than expose it
        // to anyone — leaving it visible would let any reviewer act as the second reviewer
        // without the system having recorded who reviewed it first.
        final Document doc = doubleReviewDoc("d-no-first", "alpha bravo", List.of());
        doc.setStatus("APPROVED");
        doc.setFirstReviewer(null);

        final List<Map<String, Object>> rows = queueRowsFor(bob());
        assertTrue(rows.stream().noneMatch(r -> doc.getId().equals(r.get("id"))),
                "An APPROVED doubleReview document with no firstReviewer must be hidden from every reviewer.");
    }

    @Test
    void queueHidesDoubleReviewDocumentsWithBlankFirstReviewer() {
        final Document doc = doubleReviewDoc("d-blank-first", "alpha", List.of());
        doc.setStatus("APPROVED");
        doc.setFirstReviewer("   ");

        final List<Map<String, Object>> rows = queueRowsFor(bob());
        assertTrue(rows.stream().noneMatch(r -> doc.getId().equals(r.get("id"))),
                "Blank firstReviewer is treated the same as a missing one for visibility purposes.");
    }

    @Test
    void firstReviewerComparisonIsCaseInsensitive() {
        // A user signing in as "Alice@X.com" must still be recognized as the first reviewer
        // when their stored stamp is lower-cased — emails are case-insensitive in practice
        // and a case-mismatch must not allow Alice to see the document she already reviewed.
        final Document doc = doubleReviewDoc("d-case", "alpha bravo", List.of());
        doc.setStatus("APPROVED");
        doc.setFirstReviewer("alice@x.com");

        final Authentication aliceMixedCase = new UsernamePasswordAuthenticationToken(
                "Alice@X.COM", null, Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        final Page<Map<String, Object>> page = triageController.getQueue(
                0, 50, null, null, null, false, "riskScore", "desc", aliceMixedCase);
        assertTrue(page.getContent().stream().noneMatch(r -> doc.getId().equals(r.get("id"))),
                "Email matching must be case-insensitive so Alice can't slip past her own first review.");
    }

    @Test
    void queueLeavesNonApprovedRejectedStatusesAlone() {
        // The blind double review filter only narrows APPROVED and REJECTED visibility. Other
        // terminal-ish statuses (FAILED, FINALIZED, AUTO_APPROVED) follow whatever the rest of
        // the queue logic decides — the filter must not silently affect them.
        final Document failed = doubleReviewDoc("d-failed", "alpha", List.of());
        failed.setStatus("FAILED");
        failed.setFirstReviewer("alice@x.com");
        final Document finalized = doubleReviewDoc("d-finalized", "alpha", List.of());
        finalized.setStatus("FINALIZED");
        finalized.setFirstReviewer("alice@x.com");

        // From Alice's view, both documents pass through the filter (status not in the
        // {APPROVED, REJECTED} branch), so they remain visible just like any other doc.
        final List<Map<String, Object>> rows = queueRowsFor(alice());
        assertTrue(rows.stream().anyMatch(r -> "d-failed".equals(r.get("id"))),
                "FAILED documents must not be filtered by the blind double review rule.");
        assertTrue(rows.stream().anyMatch(r -> "d-finalized".equals(r.get("id"))),
                "FINALIZED documents must not be filtered by the blind double review rule.");
    }

    @Test
    void rejectedDoubleReviewDocumentIsAlsoRoutedToTheSecondReviewer() {
        // The visibility rule applies symmetrically to APPROVED and REJECTED. After Alice
        // rejects a double-review document, Bob must still see it for his second pass.
        final Document doc = doubleReviewDoc("d-rej", "alpha bravo", List.of(
                approvedSpan("s5", "d-rej", 0, 5)));

        reviewController.reject(doc.getId(), alice(), flash());

        assertEquals("REJECTED", doc.getStatus());
        final List<Map<String, Object>> bobQueue = queueRowsFor(bob());
        assertTrue(bobQueue.stream().anyMatch(r -> doc.getId().equals(r.get("id"))),
                "A REJECTED double-review document must surface in another reviewer's queue.");
        final List<Map<String, Object>> aliceQueue = queueRowsFor(alice());
        assertTrue(aliceQueue.stream().noneMatch(r -> doc.getId().equals(r.get("id"))),
                "Alice can't be the second reviewer of a document she rejected.");
    }

    // ---------- workflow invariants: no overwriting once stamped ----------

    @Test
    void firstReviewerIsNotOverwrittenBySecondReview() {
        final Document doc = doubleReviewDoc("d-stamp", "alpha bravo", List.of(
                approvedSpan("s6", "d-stamp", 0, 5)));

        reviewController.approve(doc.getId(), alice(), flash());
        assertEquals("alice@x.com", doc.getFirstReviewer());
        final java.util.List<int[]> aliceSnapshot = doc.getFirstReviewSpans();

        reviewController.approve(doc.getId(), bob(), flash());
        assertEquals("alice@x.com", doc.getFirstReviewer(),
                "firstReviewer is write-once across subsequent reviews.");
        assertSame(aliceSnapshot, doc.getFirstReviewSpans(),
                "firstReviewSpans is captured once at the first review and never replaced.");
    }

    @Test
    void thirdReviewerDoesNotOverwriteSecondReviewer() {
        // Three distinct reviewers approve the same document in order. The second snapshot
        // belongs to whoever was the FIRST distinct second reviewer — a third (or later)
        // reviewer must not reset it.
        final Document doc = doubleReviewDoc("d-three", "alpha bravo", List.of(
                approvedSpan("s7", "d-three", 0, 5)));

        reviewController.approve(doc.getId(), alice(), flash());
        reviewController.approve(doc.getId(), bob(), flash());

        final java.util.List<int[]> bobSnapshot = doc.getSecondReviewSpans();
        assertEquals("bob@x.com", doc.getSecondReviewer());
        assertNotNull(bobSnapshot);

        // A third reviewer signs in and approves. Carol's email is recorded in approvedBy
        // (the existing dual-approval mechanism) but the blind-double-review attribution
        // must not move from Bob to Carol.
        reviewController.approve(doc.getId(), carol(), flash());
        assertEquals("bob@x.com", doc.getSecondReviewer(),
                "secondReviewer must not be overwritten by a third reviewer.");
        assertSame(bobSnapshot, doc.getSecondReviewSpans(),
                "secondReviewSpans must remain Bob's snapshot, not Carol's.");
    }

    @Test
    void sameReviewerCannotApproveADocumentTwice() {
        // Existing dual-approval guard: a reviewer who already approved cannot approve again.
        // Verifies the guard is in force for blind-double-review documents — Alice can't
        // sneakily provide both reviews by clicking Approve twice.
        final Document doc = doubleReviewDoc("d-once", "alpha", List.of(
                approvedSpan("s8", "d-once", 0, 5)));

        reviewController.approve(doc.getId(), alice(), flash());

        final RedirectAttributes ra = flash();
        final String view = reviewController.approve(doc.getId(), alice(), ra);
        assertEquals("redirect:/review/" + doc.getId(), view);
        assertNotNull(ra.getFlashAttributes().get("error"),
                "A second approve by the same user must surface an error redirect.");
        assertNull(doc.getSecondReviewer(),
                "Same-user repeat approval must not promote them to secondReviewer.");
        assertNull(doc.getSecondReviewSpans());
    }

    @Test
    void rejectingTwiceByDifferentReviewersStampsTheSecondReviewer() {
        // Two-step rejection scenario. Alice rejects → secondReviewer null. Bob then
        // rejects → secondReviewer = bob, secondReviewSpans captured. The doc never leaves
        // REJECTED status across both events.
        final Document doc = doubleReviewDoc("d-double-reject", "alpha bravo", List.of(
                approvedSpan("s9", "d-double-reject", 0, 5)));

        reviewController.reject(doc.getId(), alice(), flash());
        assertEquals("REJECTED", doc.getStatus());
        assertEquals("alice@x.com", doc.getFirstReviewer());
        assertNull(doc.getSecondReviewer());

        reviewController.reject(doc.getId(), bob(), flash());
        assertEquals("REJECTED", doc.getStatus());
        assertEquals("bob@x.com", doc.getSecondReviewer());
        assertNotNull(doc.getSecondReviewSpans());
    }

    @Test
    void firstReviewerAttemptingToSecondReviewIsBlockedFromTheQueue() {
        // Defense in depth: even if Alice somehow guesses the URL of a document she already
        // first-reviewed, she must not appear in the queue. The Prev/Next skip is one
        // safeguard; the queue filter is the other.
        final Document doc = doubleReviewDoc("d-self", "alpha bravo", List.of(
                approvedSpan("s10", "d-self", 0, 5)));
        reviewController.approve(doc.getId(), alice(), flash());

        final List<Map<String, Object>> rows = queueRowsFor(alice());
        assertTrue(rows.stream().noneMatch(r -> doc.getId().equals(r.get("id"))),
                "First reviewer must never appear as the second reviewer's queue entry on their own document.");
    }

    // ---------- helpers ----------

    private List<Map<String, Object>> queueRowsFor(final Authentication auth) {
        final Page<Map<String, Object>> page = triageController.getQueue(
                0, 50, null, null, null, false, "riskScore", "desc", auth);
        return page.getContent();
    }

    private Document doubleReviewDoc(final String id, final String text, final List<Span> spans) {
        final Document d = baseDocument(id, text);
        d.setDoubleReview(true);
        documents.put(id, d);
        spansByDocument.put(id, new ArrayList<>(spans));
        return d;
    }

    private Document baseDocument(final String id, final String text) {
        final Document d = new Document();
        d.setId(id);
        d.setBatchId(BATCH_ID);
        d.setStatus("REVIEW_REQUIRED");
        d.setOriginalText(text);
        d.setFilename(id + ".txt");
        return d;
    }

    private static Span approvedSpan(final String id, final String documentId, final int start, final int end) {
        final Span s = new Span();
        s.setId(id);
        s.setDocumentId(documentId);
        s.setStatus("APPROVED");
        s.setLocation(new Location(start, end, 1, new Coordinates(0, 0, 0, 0)));
        return s;
    }

    /**
     * Wire the mocked repositories to read and write our shared {@code documents} /
     * {@code spansByDocument} maps. This makes a controller call's mutations visible to the
     * next call without re-stubbing — closer to a real database than to per-call stubs.
     */
    private void wireRepositoryStubsToInMemoryState() {
        when(documentRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(documents.get(inv.getArgument(0, String.class))));
        when(documentRepository.save(any(Document.class)))
                .thenAnswer(inv -> {
                    final Document d = inv.getArgument(0, Document.class);
                    documents.put(d.getId(), d);
                    return d;
                });
        when(documentRepository.findByBatchId(anyString()))
                .thenAnswer(inv -> {
                    // ReviewViewController#findSiblingId mutates the returned list (in-place sort)
                    // so the stub must hand back a mutable copy each time.
                    final List<Document> siblings = new ArrayList<>();
                    for (Document d : documents.values()) {
                        if (inv.getArgument(0, String.class).equals(d.getBatchId())) {
                            siblings.add(d);
                        }
                    }
                    return siblings;
                });
        // The queue endpoint reaches the unrestricted findByStatusNotIn branch when an admin
        // calls it without any explicit batch/status/filename filter.
        when(documentRepository.findByStatusNotIn(any(), any(PageRequest.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    final java.util.Set<String> excluded = inv.getArgument(0, java.util.Set.class);
                    final List<Document> snapshot = documents.values().stream()
                            .filter(d -> !excluded.contains(d.getStatus()))
                            .toList();
                    return new PageImpl<>(snapshot, inv.getArgument(1, PageRequest.class), snapshot.size());
                });
        // The restricted branch (non-admin caller, or null auth) calls
        // findByBatchIdInAndStatusNotIn — same shape, just additionally scoped to a
        // specific set of batch ids.
        when(documentRepository.findByBatchIdInAndStatusNotIn(any(), any(), any(PageRequest.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    final java.util.Set<String> allowed = inv.getArgument(0, java.util.Set.class);
                    @SuppressWarnings("unchecked")
                    final java.util.Set<String> excluded = inv.getArgument(1, java.util.Set.class);
                    final List<Document> snapshot = documents.values().stream()
                            .filter(d -> allowed.contains(d.getBatchId()))
                            .filter(d -> !excluded.contains(d.getStatus()))
                            .toList();
                    return new PageImpl<>(snapshot, inv.getArgument(2, PageRequest.class), snapshot.size());
                });
        when(batchRepository.findAllById(any()))
                .thenAnswer(inv -> {
                    final Iterable<?> ids = inv.getArgument(0, Iterable.class);
                    final List<Batch> matched = new ArrayList<>();
                    for (Object id : ids) {
                        if (BATCH_ID.equals(id)) matched.add(batch);
                    }
                    return matched;
                });

        when(spanRepository.findByDocumentId(anyString()))
                .thenAnswer(inv -> spansByDocument.getOrDefault(inv.getArgument(0, String.class), List.of()));
    }

    private ReviewViewController buildReviewController() {
        final UserGroupsService userGroupsService = mock(UserGroupsService.class);
        final BatchAccessService batchAccessService = new BatchAccessService(batchRepository, userGroupsService);
        final DocumentAccessService documentAccessService = new DocumentAccessService(
                batchRepository, documentRepository, batchAccessService);

        final ComplianceProfileRepository complianceProfileRepository = mock(ComplianceProfileRepository.class);
        final OllamaInstanceRepository ollamaInstanceRepository = mock(OllamaInstanceRepository.class);
        when(ollamaInstanceRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
        final LlmJudgeDefaultsService llmJudgeDefaultsService = mock(LlmJudgeDefaultsService.class);
        // The review GET dereferences the loaded defaults — supply an empty record so it
        // doesn't NPE while we're exercising sibling navigation.
        when(llmJudgeDefaultsService.load()).thenReturn(new ai.philterd.workspace.model.LlmJudgeDefaults());
        final UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        final OpenSearchIndexService openSearchIndexService = mock(OpenSearchIndexService.class);
        final DocumentLockService documentLockService = mock(DocumentLockService.class);
        // Default-acquire stub: lookup the doc by id from the in-memory fake repo and
        // return it. The approve/reject flow now requires a lock; this mirrors the
        // legacy implicit "always-acquire" behavior these tests were written against.
        when(documentLockService.acquire(anyString(), anyString()))
                .thenAnswer(inv -> documents.get(inv.getArgument(0, String.class)));
        final RedactionCertificateService redactionCertificateService = mock(RedactionCertificateService.class);
        final RedactionCertificate cert = new RedactionCertificate();
        cert.setId("cert");
        cert.setDocumentHash("hash");
        when(redactionCertificateService.generate(any(), any())).thenReturn(cert);
        final FinalizationPolicyRepository finalizationPolicyRepository = mock(FinalizationPolicyRepository.class);
        final AuditLogService auditLogService = mock(AuditLogService.class);

        generalSettingsService = mock(ai.philterd.workspace.service.GeneralSettingsService.class);
        when(generalSettingsService.load()).thenReturn(new ai.philterd.workspace.model.GeneralSettings());
        return new ReviewViewController(
                documentRepository, spanRepository, batchRepository, complianceProfileRepository,
                userGroupsService, documentAccessService, auditLogService,
                ollamaInstanceRepository, llmJudgeDefaultsService, userSettingsService, userRepository,
                approvalRuleEvaluator, openSearchIndexService, documentLockService, generalSettingsService,
                mock(ai.philterd.workspace.service.DocumentDeliveryService.class),
                new ai.philterd.workspace.service.DocumentChangeGuard(documentRepository, auditLogService),
                new ai.philterd.workspace.service.DocumentFinalizationService(documentRepository, spanRepository,
                        batchRepository, finalizationPolicyRepository, redactionCertificateService, documentLockService,
                        new ai.philterd.workspace.service.DocumentChangeGuard(documentRepository, auditLogService),
                        openSearchIndexService, mock(ai.philterd.workspace.service.DocumentDeliveryService.class),
                        auditLogService));
    }

    private TriageController buildTriageController() {
        final UserGroupsService userGroupsService = mock(UserGroupsService.class);
        final BatchAccessService batchAccessService = mock(BatchAccessService.class);
        when(batchAccessService.allowedBatchIds(any())).thenReturn(Set.of(BATCH_ID));
        return new TriageController(documentRepository, batchRepository, spanRepository,
                userGroupsService, approvalRuleEvaluator, batchAccessService,
                new ai.philterd.workspace.webapp.ui.BatchLabels(org.mockito.Mockito.mock(ai.philterd.workspace.repository.GroupRepository.class)));
    }

    private static Authentication alice() {
        return new UsernamePasswordAuthenticationToken("alice@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication bob() {
        return new UsernamePasswordAuthenticationToken("bob@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication carol() {
        return new UsernamePasswordAuthenticationToken("carol@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static RedirectAttributes flash() {
        return new RedirectAttributesModelMap();
    }
}
