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

import ai.philterd.workspace.api.controller.ReviewController;
import ai.philterd.workspace.api.controller.TriageController;
import ai.philterd.workspace.dto.SpanUpdateRequest;
import ai.philterd.workspace.model.BlindReviewSpan;
import ai.philterd.workspace.model.DetectedSpan;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Coordinates;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.RedactionCertificate;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.model.UserSettings;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.BlindReviewSpanRepository;
import ai.philterd.workspace.repository.ComplianceProfileRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.FinalizationPolicyRepository;
import ai.philterd.workspace.repository.OllamaInstanceRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.service.ApprovalRuleEvaluator;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.BatchAccessService;
import ai.philterd.workspace.service.BlindDoubleReviewService;
import ai.philterd.workspace.service.DetectionRuleService;
import ai.philterd.workspace.service.DocumentChangeGuard;
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
import org.springframework.web.server.ResponseStatusException;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private final Map<String, BlindReviewSpan> copies = new HashMap<>();
    private BlindReviewSpanRepository copyRepository;
    private AuditLogService auditLogService;
    private DocumentAccessService documentAccessService;
    private BlindDoubleReviewService blindService;

    private ReviewViewController reviewController;
    private ReviewController reviewApi;
    private TriageController triageController;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        spanRepository = mock(SpanRepository.class);
        batchRepository = mock(BatchRepository.class);
        copyRepository = mock(BlindReviewSpanRepository.class);
        auditLogService = mock(AuditLogService.class);

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

        final UserGroupsService userGroupsService = mock(UserGroupsService.class);
        documentAccessService = new DocumentAccessService(batchRepository, documentRepository,
                new BatchAccessService(batchRepository, userGroupsService));
        blindService = new BlindDoubleReviewService(documentRepository, spanRepository, copyRepository,
                documentAccessService, auditLogService);
        reviewController = buildReviewController();
        reviewApi = new ReviewController(spanRepository, documentRepository, documentAccessService, auditLogService,
                mock(DetectionRuleService.class), new DocumentChangeGuard(documentRepository, auditLogService),
                copyRepository, blindService);
        triageController = buildTriageController();
    }

    // ---------- the multi-user happy path ----------

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

    // ---------- independent blind second review (#86) ----------

    @Test
    void secondReviewerOpensADocumentTheFirstApprovedAndSeesOnlyUnreviewedDetections() {
        final Document doc = approvedByAlice("d1");

        // Bob has not been shown the spans: the page shows the text without them and offers the blind review.
        final org.springframework.ui.ConcurrentModel landing = page("d1", bob());
        assertEquals("LANDING", landing.getAttribute("blindView"));
        assertEquals(true, landing.getAttribute("blindMayStart"));
        assertTrue(shownSpans(landing).isEmpty(), "No span, and so no decision, is shown before the review starts.");
        assertTrue(queueRowsFor(bob()).stream().anyMatch(r -> "d1".equals(r.get("id"))));

        reviewController.startBlindReview("d1", bob(), flash());
        assertEquals("bob@x.com", doc.getSecondReviewer());

        // Every detection, including one Alice left out, as unreviewed, with no one named as deciding it.
        final org.springframework.ui.ConcurrentModel blind = page("d1", bob());
        assertEquals("BLIND", blind.getAttribute("blindView"));
        final List<Map<String, Object>> shown = shownSpans(blind);
        assertEquals(3, shown.size());
        for (Map<String, Object> entry : shown) {
            assertEquals("PENDING", entry.get("status"));
            assertNull(entry.get("statusChangedBy"));
            assertNull(entry.get("adjudicatedBy"));
            assertFalse(String.valueOf(entry.get("id")).startsWith("d1-s"), "never the document's own spans");
        }
        assertEquals(3, reviewApi.getSpans("d1", bob()).size());
        assertTrue(reviewApi.getSpans("d1", bob()).stream().allMatch(sp -> sp instanceof BlindReviewSpan));

        // The first review is untouched.
        assertEquals("APPROVED", doc.getStatus());
        assertEquals(List.of("alice@x.com"), doc.getApprovedBy());
        assertTrue(spansByDocument.get("d1").stream().allMatch(sp -> "alice@x.com".equals(sp.getStatusChangedBy())));
    }

    @Test
    void theBlindReviewersDecisionsStayOnTheirOwnCopy() {
        final Document doc = approvedByAlice("d2");
        reviewController.startBlindReview("d2", bob(), flash());
        final List<Span> mine = reviewApi.getSpans("d2", bob());

        reviewApi.updateSpan(mine.get(0).getId(), new SpanUpdateRequest("REJECTED", null), bob());
        reviewApi.createSpan("d2", new ReviewController.CreateSpanRequest("person", 12, 19), bob());

        assertEquals("REJECTED", copies.get(mine.get(0).getId()).getStatus());
        assertEquals(List.of("alice@x.com"), doc.getApprovedBy(), "a change to the copy clears no approval");
        assertEquals(2, spansByDocument.get("d2").size(), "the document's spans are neither changed nor added to");
        assertTrue(spansByDocument.get("d2").stream().allMatch(sp -> "APPROVED".equals(sp.getStatus())));

        // Bob cannot change the document's spans or its decision, and no one else can reach his copy.
        assertThrows(ResponseStatusException.class, () -> reviewApi.updateSpan("d2-s1",
                new SpanUpdateRequest("REJECTED", null), bob()));
        final RedirectAttributes refused = flash();
        reviewController.unapprove("d2", bob(), refused);
        assertNotNull(refused.getFlashAttributes().get("error"));
        assertEquals("APPROVED", doc.getStatus());
        final ResponseStatusException hidden = assertThrows(ResponseStatusException.class,
                () -> reviewApi.updateSpan(mine.get(1).getId(), new SpanUpdateRequest("APPROVED", null), alice()));
        assertEquals(404, hidden.getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> reviewApi.updateSpan(mine.get(1).getId(),
                new SpanUpdateRequest(Span.STATUS_NEEDS_SECOND_OPINION, null), bob()));
    }

    @Test
    void theBlindReviewerIsNotHeldUpByTheDocumentsLockNorToldWhoHoldsIt() {
        final Document doc = approvedByAlice("d9");
        reviewController.startBlindReview("d9", bob(), flash());
        // Alice reopens her review and holds the lock while Bob works.
        doc.setStatus("REVIEW_REQUIRED");
        doc.setLockedBy("alice@x.com");
        doc.setLockExpiresAt(java.time.Instant.now().plusSeconds(600));

        final org.springframework.ui.ConcurrentModel blind = page("d9", bob());

        assertEquals("BLIND", blind.getAttribute("blindView"));
        assertEquals(false, blind.getAttribute("lockedByOther"));
        assertEquals("", blind.getAttribute("lockHolder"));
        reviewApi.updateSpan(reviewApi.getSpans("d9", bob()).get(0).getId(),
                new SpanUpdateRequest("APPROVED", null), bob());
    }

    @Test
    void theBlindReviewIsRecordedOnceEverySpanIsDecided() {
        final Document doc = approvedByAlice("d3");
        reviewController.startBlindReview("d3", bob(), flash());

        final RedirectAttributes early = flash();
        reviewController.submitBlindReview("d3", bob(), early);
        assertNotNull(early.getFlashAttributes().get("error"));
        assertNull(doc.getSecondReviewSpans());

        decideEveryCopy("d3", bob(), "APPROVED");
        reviewController.submitBlindReview("d3", bob(), flash());

        assertEquals(3, doc.getSecondReviewSpans().size(), "Bob's three approvals");
        assertEquals(2, doc.getFirstReviewSpans().size(), "Alice's two, compared independently by the report");
        assertNotNull(doc.getSecondReviewSubmittedAt());
        // Recorded: Bob is shown the document as it is, and his copy can no longer be changed.
        assertEquals("RECORD", page("d3", bob()).getAttribute("blindView"));
        final String copyId = copies.values().iterator().next().getId();
        assertThrows(ResponseStatusException.class,
                () -> reviewApi.updateSpan(copyId, new SpanUpdateRequest("REJECTED", null), bob()));
    }

    @Test
    void finalizationWaitsUntilAnApproverWhoDidNeitherReviewReconcilesThem() {
        final Document doc = approvedByAlice("d4");
        final RedirectAttributes tooSoon = flash();
        reviewController.finalizeDocument("d4", alice(), tooSoon);
        assertNotNull(tooSoon.getFlashAttributes().get("error"));
        assertEquals("APPROVED", doc.getStatus());

        reviewController.startBlindReview("d4", bob(), flash());
        decideEveryCopy("d4", bob(), "APPROVED");
        reviewController.submitBlindReview("d4", bob(), flash());

        final RedirectAttributes unreconciled = flash();
        reviewController.finalizeDocument("d4", alice(), unreconciled);
        assertNotNull(unreconciled.getFlashAttributes().get("error"));

        // Carol sees where the reviews differ: Bob redacted "charlie", which Alice did not.
        final org.springframework.ui.ConcurrentModel carolsPage = page("d4", carol());
        assertEquals(true, carolsPage.getAttribute("blindShowComparison"));
        @SuppressWarnings("unchecked")
        final List<BlindDoubleReviewService.Disagreement> differences =
                (List<BlindDoubleReviewService.Disagreement>) carolsPage.getAttribute("blindDisagreements");
        assertEquals(1, differences.size());
        assertEquals("charlie", differences.get(0).text());
        assertNull(differences.get(0).firstType());
        assertEquals("person", differences.get(0).secondType());
        assertTrue(queueRowsFor(carol()).stream().anyMatch(r -> "d4".equals(r.get("id"))));
        assertTrue(queueRowsFor(alice()).stream().noneMatch(r -> "d4".equals(r.get("id"))));

        for (Authentication reviewer : List.of(alice(), bob())) {
            final RedirectAttributes own = flash();
            reviewController.reconcileBlindReview("d4", reviewer, own);
            assertNotNull(own.getFlashAttributes().get("error"), "neither reviewer reconciles their own reviews");
        }
        reviewController.reconcileBlindReview("d4", carol(), flash());
        assertEquals("carol@x.com", doc.getDoubleReviewReconciledBy());

        reviewController.finalizeDocument("d4", carol(), flash());
        assertEquals("FINALIZED", doc.getStatus());
    }

    @Test
    void someoneShownTheDocumentsSpansCannotDoItsBlindReview() {
        final Document doc = doubleReviewDoc("d5", "alpha bravo", List.of(approvedSpan("d5-s1", "d5", 0, 5)));
        doc.setDoubleReviewDetections(List.of(detected(0, 5)));
        // Carol opens it while the first review is in progress.
        assertEquals("RECORD", page("d5", carol()).getAttribute("blindView"));
        assertTrue(doc.getFirstReviewSeenBy().contains("carol@x.com"));

        reviewController.approve("d5", alice(), flash());

        assertEquals("RECORD", page("d5", carol()).getAttribute("blindView"));
        assertEquals(false, page("d5", carol()).getAttribute("blindMayStart"));
        final RedirectAttributes refused = flash();
        reviewController.startBlindReview("d5", carol(), refused);
        assertNotNull(refused.getFlashAttributes().get("error"));
        assertNull(doc.getSecondReviewer());
        assertTrue(queueRowsFor(carol()).stream().noneMatch(r -> "d5".equals(r.get("id"))));
        assertTrue(queueRowsFor(bob()).stream().anyMatch(r -> "d5".equals(r.get("id"))));
    }

    @Test
    void showingTheFirstReviewGivesUpTheBlindReview() {
        final Document doc = approvedByAlice("d6");
        assertEquals("LANDING", page("d6", dave()).getAttribute("blindView"));

        reviewController.revealFirstReview("d6", dave(), flash());

        final org.springframework.ui.ConcurrentModel shown = page("d6", dave());
        assertEquals("RECORD", shown.getAttribute("blindView"));
        assertEquals(2, shownSpans(shown).size());
        assertFalse(BlindDoubleReviewService.mayStart(dave(), doc));
    }

    @Test
    void anApproverReleasesABlindReviewSoSomeoneElseCanDoIt() {
        final Document doc = approvedByAlice("d7");
        reviewController.startBlindReview("d7", bob(), flash());
        reviewApi.updateSpan(reviewApi.getSpans("d7", bob()).get(0).getId(),
                new SpanUpdateRequest("APPROVED", null), bob());
        assertTrue(queueRowsFor(carol()).stream().noneMatch(r -> "d7".equals(r.get("id"))), "Bob is doing it");
        assertTrue(queueRowsFor(bob()).stream().anyMatch(r -> "d7".equals(r.get("id"))));
        assertEquals("LANDING", page("d7", carol()).getAttribute("blindView"));

        reviewController.releaseBlindReview("d7", carol(), flash());

        assertNull(doc.getSecondReviewer());
        assertTrue(copies.isEmpty(), "Bob's copy is discarded");
        reviewController.startBlindReview("d7", carol(), flash());
        assertEquals("carol@x.com", doc.getSecondReviewer());
        assertTrue(reviewApi.getSpans("d7", carol()).stream().allMatch(sp -> "PENDING".equals(sp.getStatus())),
                "Carol starts from the detections, not from Bob's decisions");
    }

    @Test
    void aDocumentSelectedBeforeDetectionsWereKeptStartsFromItsDetectedSpans() {
        final Span moved = approvedSpan("d8-s1", "d8", 0, 11);
        moved.setOriginalCharacterStart(0);
        moved.setOriginalCharacterEnd(5);
        moved.setAdjusted(true);
        final Span added = approvedSpan("d8-s2", "d8", 12, 19);
        added.setManuallyCreated(true);
        final Document doc = doubleReviewDoc("d8", "alpha bravo charlie", List.of(moved, added));
        reviewController.approve("d8", alice(), flash());

        reviewController.startBlindReview("d8", bob(), flash());

        final List<Span> mine = reviewApi.getSpans("d8", bob());
        assertEquals(1, mine.size(), "a span added by hand is not a detection");
        assertEquals(0, mine.get(0).getLocation().characterStart());
        assertEquals(5, mine.get(0).getLocation().characterEnd(), "at the boundaries it was detected at");
        assertEquals("PENDING", mine.get(0).getStatus());
        assertNull(doc.getDoubleReviewDetections());
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
        s.setType("person");
        s.changeStatus("APPROVED", "alice@x.com");
        s.setLocation(new Location(start, end, 1, new Coordinates(0, 0, 0, 0)));
        return s;
    }

    private static DetectedSpan detected(final int start, final int end) {
        return new DetectedSpan("person", 0.9, new Location(start, end, 1, new Coordinates(0, 0, 0, 0)));
    }

    private org.springframework.ui.ConcurrentModel page(final String id, final Authentication who) {
        final org.springframework.ui.ConcurrentModel model = new org.springframework.ui.ConcurrentModel();
        reviewController.review(id, who, model);
        return model;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shownSpans(final org.springframework.ui.ConcurrentModel model) {
        return (List<Map<String, Object>>) model.getAttribute("originalRedactions");
    }

    /** "alpha bravo charlie": Alice approves alpha and bravo; Philter also detected charlie. */
    private Document approvedByAlice(final String id) {
        final Document doc = doubleReviewDoc(id, "alpha bravo charlie", List.of(
                approvedSpan(id + "-s1", id, 0, 5), approvedSpan(id + "-s2", id, 6, 11)));
        doc.setDoubleReviewDetections(List.of(detected(0, 5), detected(6, 11), detected(12, 19)));
        reviewController.approve(id, alice(), flash());
        assertEquals("APPROVED", doc.getStatus());
        return doc;
    }

    private void decideEveryCopy(final String id, final Authentication who, final String status) {
        for (Span c : reviewApi.getSpans(id, who)) {
            reviewApi.updateSpan(c.getId(), new SpanUpdateRequest(status, null), who);
        }
    }

    private static Authentication dave() {
        return new UsernamePasswordAuthenticationToken("dave@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
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
                .thenAnswer(inv -> new ArrayList<>(
                        spansByDocument.getOrDefault(inv.getArgument(0, String.class), List.of())));
        when(spanRepository.findById(anyString())).thenAnswer(inv -> spansByDocument.values().stream()
                .flatMap(List::stream).filter(sp -> inv.getArgument(0, String.class).equals(sp.getId())).findFirst());
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        // The blind review copies, kept in their own collection.
        when(copyRepository.findByDocumentId(anyString())).thenAnswer(inv -> copies.values().stream()
                .filter(c -> inv.getArgument(0, String.class).equals(c.getDocumentId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        when(copyRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(copies.get(inv.getArgument(0, String.class))));
        when(copyRepository.save(any(BlindReviewSpan.class))).thenAnswer(inv -> {
            final BlindReviewSpan c = inv.getArgument(0);
            copies.put(c.getId(), c);
            return c;
        });
        when(copyRepository.saveAll(any())).thenAnswer(inv -> {
            final List<BlindReviewSpan> saved = new ArrayList<>();
            for (Object o : inv.getArgument(0, Iterable.class)) {
                copies.put(((BlindReviewSpan) o).getId(), (BlindReviewSpan) o);
                saved.add((BlindReviewSpan) o);
            }
            return saved;
        });
        org.mockito.Mockito.doAnswer(inv -> copies.remove(inv.getArgument(0, String.class)))
                .when(copyRepository).deleteById(anyString());
        when(copyRepository.deleteByDocumentId(anyString())).thenAnswer(inv -> {
            final String id = inv.getArgument(0, String.class);
            final List<String> gone = copies.values().stream().filter(c -> id.equals(c.getDocumentId()))
                    .map(BlindReviewSpan::getId).toList();
            gone.forEach(copies::remove);
            return (long) gone.size();
        });
    }

    private ReviewViewController buildReviewController() {
        final UserGroupsService userGroupsService = mock(UserGroupsService.class);

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
                        new ai.philterd.workspace.service.SourceRetentionService(documentRepository, spanRepository,
                                copyRepository,
                                org.mockito.Mockito.mock(ai.philterd.workspace.repository.PendingUploadRepository.class),
                                batchRepository, finalizationPolicyRepository, openSearchIndexService, auditLogService), mock(ai.philterd.workspace.service.DocumentDeliveryService.class),
                        auditLogService),
                blindService);
    }

    private TriageController buildTriageController() {
        final UserGroupsService userGroupsService = mock(UserGroupsService.class);
        final BatchAccessService batchAccessService = mock(BatchAccessService.class);
        when(batchAccessService.allowedBatchIds(any())).thenReturn(Set.of(BATCH_ID));
        return new TriageController(documentRepository, batchRepository, spanRepository,
                userGroupsService, approvalRuleEvaluator, batchAccessService,
                new ai.philterd.workspace.webapp.ui.BatchLabels(org.mockito.Mockito.mock(ai.philterd.workspace.repository.GroupRepository.class)),
                blindService);
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
