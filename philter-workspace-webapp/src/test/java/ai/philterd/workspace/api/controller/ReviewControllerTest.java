/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.api.controller;

import ai.philterd.workspace.dto.SpanUpdateRequest;
import ai.philterd.workspace.model.Coordinates;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewControllerTest {

    private SpanRepository spanRepository;
    private DocumentRepository documentRepository;
    private BatchRepository batchRepository;
    private TestDoubles.FakeUserGroups userGroupsService;
    private TestDoubles.RecordingAuditLog auditLogService;
    private ReviewController controller;
    private ai.philterd.workspace.service.DetectionRuleService detectionRules;
    private static final org.springframework.security.core.Authentication ADMIN = TestAuth.admin("admin@example.com");

    @BeforeEach
    void setUp() {
        spanRepository = mock(SpanRepository.class);
        documentRepository = mock(DocumentRepository.class);
        batchRepository = mock(BatchRepository.class);
        userGroupsService = TestDoubles.userGroups();
        auditLogService = TestDoubles.auditLog();
        controller = new ReviewController(spanRepository, documentRepository,
                TestDoubles.documentAccess(batchRepository, documentRepository, userGroupsService),
                auditLogService, detectionRules = mock(ai.philterd.workspace.service.DetectionRuleService.class),
                new ai.philterd.workspace.service.DocumentChangeGuard(documentRepository, auditLogService));
        // Default stub: a document "d1" the access helper can resolve.
        // Tests that need a different shape override this.
        when(documentRepository.findById("d1")).thenReturn(Optional.of(document("d1", "the original text")));
    }

    private static Span span(final String id, final String docId, final String type, final String status,
                             final int start, final int end, final String text) {
        final Span s = new Span();
        s.setId(id);
        s.setDocumentId(docId);
        s.setType(type);
        s.setStatus(status);
        s.setText(text);
        s.setLocation(new Location(start, end, 1, new Coordinates(0, 0, 0, 0)));
        return s;
    }

    private static Document document(final String id, final String text) {
        final Document d = new Document();
        d.setId(id);
        d.setOriginalText(text);
        return d;
    }

    // ---- detection rules (#39) ----

    @Test
    void refusingADetectedSpanAsksWhetherToProposeARule() {
        final Span s = span("s1", "d1", "person", "PENDING", 0, 3, "the");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(s));
        when(spanRepository.save(any(Span.class))).thenAnswer(i -> i.getArgument(0));

        controller.updateSpan("s1", new SpanUpdateRequest("REJECTED", null), ADMIN);
        verify(detectionRules).noteRefusal(org.mockito.ArgumentMatchers.same(s), any(Document.class));

        // Already refused, or accepted: nothing more to count.
        controller.updateSpan("s1", new SpanUpdateRequest("REJECTED", null), ADMIN);
        controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null), ADMIN);
        verify(detectionRules, org.mockito.Mockito.times(1)).noteRefusal(any(), any());
    }

    @Test
    void makingARuleAnswersWithItsStatusOrWhyNot() {
        final Span s = span("s1", "d1", "person", "REJECTED", 0, 3, "the");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(s));
        final ai.philterd.workspace.model.DetectionRule rule = new ai.philterd.workspace.model.DetectionRule();
        rule.setStatus(ai.philterd.workspace.model.DetectionRule.STATUS_PROPOSED);
        when(detectionRules.makeFromSpan(eq("NEVER"), eq(s), any(Document.class), eq(ADMIN)))
                .thenReturn(new ai.philterd.workspace.service.DetectionRuleService.Made(rule, "Rule proposed."));

        final org.springframework.http.ResponseEntity<Map<String, Object>> made =
                controller.makeRule("s1", new ReviewController.RuleRequest("NEVER"), ADMIN);
        assertEquals(200, made.getStatusCode().value());
        assertEquals(Map.of("status", "PROPOSED", "message", "Rule proposed."), made.getBody());

        when(detectionRules.makeFromSpan(eq("ALWAYS"), eq(s), any(Document.class), eq(ADMIN)))
                .thenThrow(new ai.philterd.workspace.service.DetectionRuleService.RuleException("Not this one."));
        final org.springframework.http.ResponseEntity<Map<String, Object>> refused =
                controller.makeRule("s1", new ReviewController.RuleRequest("ALWAYS"), ADMIN);
        assertEquals(409, refused.getStatusCode().value());
        assertEquals(Map.of("message", "Not this one."), refused.getBody());

        assertThrows(ResponseStatusException.class,
                () -> controller.makeRule("ghost", new ReviewController.RuleRequest("NEVER"), ADMIN));
    }

    // ---- getSpans ----

    @Test
    void getSpansReturnsRepositoryResult() {
        final Span s = span("s1", "d1", "ssn", "PENDING", 0, 11, "123-45-6789");
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(s));

        final List<Span> result = controller.getSpans("d1", ADMIN);

        assertEquals(1, result.size());
        assertEquals("s1", result.get(0).getId());
    }

    @Test
    void getSpansReturns409WhenSourceCleared() {
        when(documentRepository.findById("d1")).thenReturn(Optional.of(document("d1", null)));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("d1", ADMIN));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("finalization policy"));
        verify(spanRepository, never()).findByDocumentId(anyString());
    }

    @Test
    void getSpansReturns409WhenSourceEmpty() {
        when(documentRepository.findById("d1")).thenReturn(Optional.of(document("d1", "")));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("d1", ADMIN));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    // ---- updateSpan ----

    @Test
    void updateSpanRejectsNullRequest() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", null, ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateSpanRejectsBothFieldsNull() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest(null, null), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateSpanRejectsUnknownSpan() {
        when(spanRepository.findById("ghost")).thenReturn(Optional.empty());
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("ghost", new SpanUpdateRequest("APPROVED", null), ADMIN));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void updateSpanRejectsInvalidStatus() {
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest("WHATEVER", null), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateSpanRejectsInvalidType() {
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest(null, "made-up"), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateSpanAppliesStatusAndAudits() {
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null), ADMIN);

        assertEquals("APPROVED", updated.getStatus());
        assertTrue(auditLogService.hasAction("SPAN_UPDATE"));
    }

    @Test
    void updateSpanNormalizesType() {
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1", new SpanUpdateRequest(null, "  Phone-Number  "), ADMIN);

        assertEquals("phone-number", updated.getType());
    }

    @Test
    void updateSpanCanChangeBoth() {
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1", new SpanUpdateRequest("REJECTED", "phone-number"), ADMIN);

        assertEquals("REJECTED", updated.getStatus());
        assertEquals("phone-number", updated.getType());
    }

    // ---- redactAllLike ----

    @Test
    void redactAllLikeRejectsUnknownSpan() {
        when(spanRepository.findById("ghost")).thenReturn(Optional.empty());
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("ghost", ADMIN));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void redactAllLikeRejectsRejectedSourceSpan() {
        final Span source = span("s1", "d1", "ssn", "REJECTED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("s1", ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).saveAll(anyList());
    }

    @Test
    void redactAllLikeRejectsSecondOpinionSourceSpan() {
        final Span source = span("s1", "d1", "ssn", Span.STATUS_NEEDS_SECOND_OPINION, 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("s1", ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).saveAll(anyList());
    }

    @Test
    void redactAllLikeRejectsEmptySourceText() {
        final Span source = span("s1", "d1", "ssn", "APPROVED", 0, 0, "");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("s1", ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void redactAllLikeRefusesASpanShorterThanTheMinimum() {
        // #75: a one-character span would match every such character in the document.
        for (String text : List.of("a", " ab ")) {
            final Span source = span("s1", "d1", "person", "APPROVED", 0, text.length(), text);
            when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
            when(documentRepository.findById("d1")).thenReturn(Optional.of(document("d1", text + " a a a ab ab")));

            final ReviewController.RedactLikeRefused refused = assertThrows(ReviewController.RedactLikeRefused.class,
                    () -> controller.redactAllLike("s1", ADMIN));
            assertTrue(refused.getMessage().contains("at least 3 characters"), refused.getMessage());
            assertEquals(Map.of("message", refused.getMessage()), controller.redactLikeRefused(refused).getBody(),
                    "the reason is in the body, since error messages are left out of error responses");
            assertEquals(400, controller.redactLikeRefused(refused).getStatusCode().value());
        }
        verify(spanRepository, never()).save(any(Span.class));
        verify(spanRepository, never()).saveAll(anyList());
    }

    @Test
    void redactAllLikeStopsAtTheLimitAndASecondClickContinues() {
        // 600 occurrences: the source and 599 others, more than one click applies.
        final String text = "abc ".repeat(600);
        final Span source = span("s1", "d1", "person", "PENDING", 0, 3, "abc");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(document("d1", text)));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(source));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));
        final List<Span> saved = new ArrayList<>();
        when(spanRepository.saveAll(anyList())).thenAnswer(inv -> {
            saved.addAll(inv.getArgument(0));
            return inv.getArgument(0);
        });

        final Map<String, Object> first = controller.redactAllLike("s1", ADMIN);
        assertEquals(ReviewController.REDACT_LIKE_MAX_MATCHES, first.get("created"));
        assertEquals(true, first.get("limitReached"));
        assertEquals(ReviewController.REDACT_LIKE_MAX_MATCHES, first.get("limit"));
        assertEquals(ReviewController.REDACT_LIKE_MAX_MATCHES, saved.size());

        // The second click sees the first click's spans, already approved by a person, and does not count them.
        final List<Span> afterFirst = new ArrayList<>(List.of(source));
        afterFirst.addAll(saved);
        when(spanRepository.findByDocumentId("d1")).thenReturn(afterFirst);
        saved.clear();
        final Map<String, Object> second = controller.redactAllLike("s1", ADMIN);
        assertEquals(599 - ReviewController.REDACT_LIKE_MAX_MATCHES, second.get("created"));
        assertEquals(0, second.get("approved"));
        assertEquals(false, second.get("limitReached"));
    }

    @Test
    void theOverlapCheckAgreesWithAScanOfEveryRange() {
        // #75: a sorted structure in place of a scan of every range for each match.
        final java.util.Random random = new java.util.Random(75);
        for (int trial = 0; trial < 200; trial++) {
            final List<long[]> ranges = new ArrayList<>();
            for (int i = random.nextInt(8); i > 0; i--) {
                final long start = random.nextInt(50);
                ranges.add(new long[]{start, start + 1 + random.nextInt(10)});
            }
            final ReviewController.Ranges sorted = new ReviewController.Ranges(ranges);
            for (int start = 0; start < 60; start++) {
                final int end = start + 1 + random.nextInt(6);
                boolean scanned = false;
                for (long[] r : ranges) {
                    scanned |= start < r[1] && end > r[0];
                }
                assertEquals(scanned, sorted.overlaps(start, end), "[" + start + ", " + end + ")");
            }
        }
    }

    @Test
    void redactAllLikeAccessCheckRunsBeforeEmptyTextCheck() {
        // Regression for the F4-ordering bug: an empty-text span used to throw 400
        // *before* the access helper ran. A non-admin probing a real-but-inaccessible
        // span would therefore see 400 while a missing-span probe got 404, letting
        // them enumerate span ids by status code. After the fix, both probes return
        // the same uniform 404 "Span not found." — same body, same status.
        final Span source = span("s1", "d1", "ssn", "APPROVED", 0, 0, "");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));

        // Path A: real span, caller can't see its document.
        final ResponseStatusException accessDenied = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("s1", TestAuth.user("alice@example.com")));

        // Path B: span doesn't exist at all.
        when(spanRepository.findById("ghost-span")).thenReturn(Optional.empty());
        final ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("ghost-span", TestAuth.user("alice@example.com")));

        assertEquals(HttpStatus.NOT_FOUND, accessDenied.getStatusCode(),
                "access-denied must produce 404, not the pre-fix 400 from empty-text");
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals(missing.getReason(), accessDenied.getReason(),
                "miss vs access-denied bodies must be byte-identical");
        assertEquals("Span not found.", accessDenied.getReason(),
                "access-denied body must be the generic span-not-found message");
        // Defence-in-depth: neither reason should mention the span id or "empty"
        // (the pre-fix 400 body said 'Source span has no text to match.' — that
        // string would leak existence of the span row to a probing caller).
        assertTrue(accessDenied.getReason() == null
                        || !accessDenied.getReason().toLowerCase().contains("no text to match"),
                "access-denied body leaked 'no text to match': " + accessDenied.getReason());
        assertTrue(accessDenied.getReason() == null
                        || !accessDenied.getReason().contains("s1"),
                "access-denied body leaked the span id: " + accessDenied.getReason());
    }

    @Test
    void redactAllLikeReturnsZerosWhenDocumentTextEmpty() {
        final Span source = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        final Document doc = document("d1", "");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));

        final Map<String, Object> result = controller.redactAllLike("s1", ADMIN);

        assertEquals(0, result.get("created"));
        assertEquals(0, result.get("approved"));
    }

    @Test
    void redactAllLikeCreatesNewSpansForAdditionalMatches() {
        // Document has "Project Icarus" three times. Source span covers the first occurrence.
        final String text = "Project Icarus is great. Look at Project Icarus again. And Project Icarus.";
        final int firstStart = text.indexOf("Project Icarus");
        final int firstEnd = firstStart + "Project Icarus".length();
        final Span source = span("s1", "d1", "person", "PENDING", firstStart, firstEnd, "Project Icarus");
        final Document doc = document("d1", text);

        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(source));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Map<String, Object> result = controller.redactAllLike("s1", ADMIN);

        // Source becomes APPROVED + 2 new spans created for the other matches.
        assertEquals(2, result.get("created"));
        assertEquals(0, result.get("approved"));
        verify(spanRepository).saveAll(anyList());
        assertTrue(auditLogService.hasAction("SPAN_REDACT_LIKE"));
    }

    @Test
    void redactAllLikeFlipsExistingExactRangesToApproved() {
        // Document has "alpha" twice. Source covers the first; an existing span at the second range exists.
        final String text = "alpha and alpha";
        final int firstStart = 0;
        final int firstEnd = 5;
        final int secondStart = text.indexOf("alpha", firstEnd);
        final int secondEnd = secondStart + 5;

        final Span source = span("s1", "d1", "person", "PENDING", firstStart, firstEnd, "alpha");
        final Span existing = span("s2", "d1", "first-name", "PENDING", secondStart, secondEnd, "alpha");
        final Document doc = document("d1", text);

        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(source, existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Map<String, Object> result = controller.redactAllLike("s1", ADMIN);

        assertEquals(0, result.get("created"));
        assertEquals(1, result.get("approved"));
        // The existing span should now be APPROVED with type aligned to source.
        assertEquals("APPROVED", existing.getStatus());
        assertEquals("person", existing.getType());
    }

    @Test
    void redactAllLikeSkipsOverlappingNonExactRanges() {
        // Source span "abc" appears once; another match "abc" overlaps with an
        // existing-but-non-exact span occupying [5, 10] inside that area.
        final String text = "abc xx abcxxx";
        final Span source = span("s1", "d1", "person", "PENDING", 0, 3, "abc");
        // Existing span covers offsets 5..10 (overlapping the second "abc" at 7..10)
        final Span overlapping = span("s2", "d1", "other", "PENDING", 5, 10, "x abcx");
        final Document doc = document("d1", text);

        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.findByDocumentId("d1")).thenReturn(new ArrayList<>(List.of(source, overlapping)));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Map<String, Object> result = controller.redactAllLike("s1", ADMIN);

        // The second "abc" overlaps an existing range, so neither new span nor flip.
        assertEquals(0, result.get("created"));
        assertEquals(0, result.get("approved"));
        verify(spanRepository, never()).saveAll(anyList());
    }

    // ---- group-access enforcement (non-admin) ----

    /**
     * Wires up document "d1" → batch "b1" → group "g1" so the access helper has
     * something to compare against. The non-admin user is created by the caller.
     */
    private void seedGroupedDocument() {
        final Document doc = document("d1", "the original text");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
    }

    @Test
    void getSpansForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void accessDeniedReturnsSameGenericBodyAsLookupMiss() {
        // The reason field on a 404 must not leak whether the id exists. An attacker
        // probing for valid ids should see an identical message whether they pass an
        // id that doesn't exist or one that exists but they can't see.
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));

        // Path A: id exists, but caller has no access.
        final ResponseStatusException accessDenied = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("d1", TestAuth.user("alice@example.com")));

        // Path B: id genuinely doesn't exist.
        when(documentRepository.findById("ghost")).thenReturn(Optional.empty());
        final ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("ghost", TestAuth.user("alice@example.com")));

        assertEquals(HttpStatus.NOT_FOUND, accessDenied.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        // Bodies must be byte-identical — that's the whole point of the standardization.
        assertEquals(missing.getReason(), accessDenied.getReason());
        // And neither must contain a real id.
        assertTrue(accessDenied.getReason() == null
                || !accessDenied.getReason().contains("d1"),
                "access-denied body leaked the id: " + accessDenied.getReason());
        assertTrue(missing.getReason() == null
                || !missing.getReason().contains("ghost"),
                "lookup-miss body leaked the id: " + missing.getReason());
    }

    @Test
    void getSpansAllowsNonAdminInGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g1"));
        when(spanRepository.findByDocumentId("d1"))
                .thenReturn(List.of(span("s1", "d1", "ssn", "PENDING", 0, 5, "hello")));

        final List<Span> result = controller.getSpans("d1", TestAuth.user("alice@example.com"));
        assertEquals(1, result.size());
    }

    @Test
    void updateSpanForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null),
                        TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void updateSpanAccessDeniedReturnsSameGenericBodyAsLookupMiss() {
        // F4 mirror of the F9 indistinguishability test: the body for "no such span" must
        // be identical to "span exists but you can't see it" so an attacker can't enumerate
        // span ids by reading the response message.
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        // Path A: span id exists, but the caller can't see its document.
        final ResponseStatusException accessDenied = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null),
                        TestAuth.user("alice@example.com")));

        // Path B: span id genuinely doesn't exist.
        when(spanRepository.findById("ghost-span")).thenReturn(Optional.empty());
        final ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("ghost-span", new SpanUpdateRequest("APPROVED", null),
                        TestAuth.user("alice@example.com")));

        assertEquals(HttpStatus.NOT_FOUND, accessDenied.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        // Bodies must be byte-identical.
        assertEquals(missing.getReason(), accessDenied.getReason());
        // No id of either span surfaces in the body.
        assertTrue(accessDenied.getReason() == null
                || !accessDenied.getReason().contains("s1"),
                "access-denied body leaked the span id: " + accessDenied.getReason());
        assertTrue(missing.getReason() == null
                || !missing.getReason().contains("ghost"),
                "lookup-miss body leaked the span id: " + missing.getReason());
        // Specifically the body must not say "Document not found." — that would distinguish
        // "span exists, doc inaccessible" from "no such span".
        assertTrue(accessDenied.getReason() != null
                && !accessDenied.getReason().contains("Document"),
                "access-denied body must not mention 'Document': " + accessDenied.getReason());
    }

    @Test
    void updateSpanWhenParentDocumentMissingAlsoReturnsSameBody() {
        // The third failure mode handled by loadAccessibleParentForSpan: the span exists,
        // but its referenced parent document has been deleted. Must surface as the same
        // "Span not found." body — not the previous "Document not found." which would
        // leak the existence of the span row.
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g1"));
        final Span existing = span("s1", "ghost-doc", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(documentRepository.findById("ghost-doc")).thenReturn(Optional.empty());

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null),
                        TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Span not found.", ex.getReason());
    }

    @Test
    void redactAllLikeForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        final Span source = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("s1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void createSpanForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.createSpan("d1",
                        new ReviewController.CreateSpanRequest("ssn", 0, 5),
                        TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---- deleteSpan ----

    @Test
    void deleteSpanRejectsUnknown() {
        when(spanRepository.findById("ghost")).thenReturn(Optional.empty());
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSpan("ghost", ADMIN));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void deleteSpanRejectsDetectionSpan() {
        final Span detected = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        // manuallyCreated defaults to false → detection-style span
        when(spanRepository.findById("s1")).thenReturn(Optional.of(detected));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSpan("s1", ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).deleteById(any(String.class));
    }

    @Test
    void deleteSpanRemovesManualSpan() {
        final Span manual = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        manual.setManuallyCreated(true);
        when(spanRepository.findById("s1")).thenReturn(Optional.of(manual));

        final Map<String, Object> result = controller.deleteSpan("s1", ADMIN);
        assertEquals("s1", result.get("id"));
        assertEquals(true, result.get("deleted"));
        verify(spanRepository).deleteById("s1");
        assertTrue(auditLogService.hasAction("SPAN_DELETE"));
    }

    @Test
    void deleteSpanForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        final Span manual = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        manual.setManuallyCreated(true);
        when(spanRepository.findById("s1")).thenReturn(Optional.of(manual));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSpan("s1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(spanRepository, never()).deleteById(any(String.class));
    }

    @Test
    void createSpanMarksManuallyCreated() {
        final Document doc = document("d1", "Hello world, this is a sample document.");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span result = controller.createSpan("d1",
                new ReviewController.CreateSpanRequest("ssn", 0, 5),
                ADMIN);
        assertTrue(result.isManuallyCreated());
        assertEquals(1.0, result.getConfidence());
        assertEquals("APPROVED", result.getStatus());
    }

    // ---- overturn-reason enforcement ----

    private static final org.springframework.security.core.Authentication ALICE =
            TestAuth.admin("alice@example.com");
    private static final org.springframework.security.core.Authentication BOB =
            TestAuth.admin("bob@example.com");

    private static Span approvedBy(final String id, final String docId, final String approver) {
        final Span s = span(id, docId, "ssn", "APPROVED", 0, 5, "hello");
        s.setStatusChangedBy(approver);
        return s;
    }

    private TestDoubles.RecordingAuditLog.Entry lastSpanUpdateAudit() {
        for (int i = auditLogService.entries.size() - 1; i >= 0; i--) {
            final TestDoubles.RecordingAuditLog.Entry e = auditLogService.entries.get(i);
            if ("SPAN_UPDATE".equals(e.action())) return e;
        }
        return null;
    }

    @Test
    void sameReviewerCanFlipTheirOwnApprovedSpanWithoutReason() {
        final Span existing = approvedBy("s1", "d1", "alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null), ALICE);

        assertEquals("REJECTED", updated.getStatus());
        assertEquals("alice@example.com", updated.getStatusChangedBy());
        assertEquals(false, lastSpanUpdateAudit().details().getOrDefault("overturn", false));
    }

    @Test
    void differentReviewerOverturningApprovedSpanWithoutReasonReturns409() {
        final Span existing = approvedBy("s1", "d1", "alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1",
                        new SpanUpdateRequest("REJECTED", null, null), BOB));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("OVERTURN_REASON_REQUIRED"));
        verify(spanRepository, never()).save(any(Span.class));
    }

    @Test
    void differentReviewerOverturningApprovedSpanWithBlankReasonReturns409() {
        final Span existing = approvedBy("s1", "d1", "alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1",
                        new SpanUpdateRequest("REJECTED", null, "   "), BOB));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void differentReviewerOverturningApprovedSpanWithReasonSucceedsAndAuditsReason() {
        final Span existing = approvedBy("s1", "d1", "alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null, "PII not actually present"), BOB);

        assertEquals("REJECTED", updated.getStatus());
        assertEquals("bob@example.com", updated.getStatusChangedBy());
        final Map<String, Object> details = lastSpanUpdateAudit().details();
        assertEquals(true, details.get("overturn"));
        assertEquals("PII not actually present", details.get("reason"));
        assertEquals("alice@example.com", details.get("previousStatusChangedBy"));
    }

    @Test
    void overturnIsCaseInsensitiveForReviewerEmail() {
        final Span existing = approvedBy("s1", "d1", "Alice@Example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        // Same human, different casing of email — should NOT trigger an overturn.
        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null), ALICE);

        assertEquals("REJECTED", updated.getStatus());
        assertEquals(false, lastSpanUpdateAudit().details().getOrDefault("overturn", false));
    }

    @Test
    void noReasonRequiredWhenPriorActorWasSystem() {
        // System-set APPROVED (e.g. auto-approved during ingest) leaves statusChangedBy null.
        // A reviewer can flip it without a reason — no human decision is being overturned.
        final Span existing = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        // statusChangedBy intentionally left null
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null), BOB);

        assertEquals("REJECTED", updated.getStatus());
        assertEquals("bob@example.com", updated.getStatusChangedBy());
    }

    @Test
    void noReasonRequiredWhenPriorStatusWasNotApproved() {
        // Bob flips a PENDING span (originally touched by Alice somehow, e.g. type change)
        // to REJECTED — that's not an overturn of an approval.
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        existing.setStatusChangedBy("alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null), BOB);

        assertEquals("REJECTED", updated.getStatus());
    }

    @Test
    void typeOnlyChangeOnApprovedSpanByDifferentReviewerNeedsNoReason() {
        // Status doesn't change — no overturn check applies.
        final Span existing = approvedBy("s1", "d1", "alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest(null, "phone-number"), BOB);

        assertEquals("APPROVED", updated.getStatus());
        assertEquals("phone-number", updated.getType());
    }

    @Test
    void approvedReapprovedByDifferentReviewerNeedsNoReason() {
        // Setting APPROVED again on an already-APPROVED span isn't an overturn.
        final Span existing = approvedBy("s1", "d1", "alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("APPROVED", null), BOB);

        assertEquals("APPROVED", updated.getStatus());
        assertEquals("bob@example.com", updated.getStatusChangedBy());
    }

    // ---- second-opinion enforcement ----

    @Test
    void requesterCanResolveTheirOwnSecondOpinionToApproved() {
        // Any reviewer — including the requester — may flip a NEEDS_SECOND_OPINION span
        // to APPROVED or REJECTED. The "another reviewer must resolve" constraint is no
        // longer enforced at the span level.
        final Span existing = span("s1", "d1", "ssn", Span.STATUS_NEEDS_SECOND_OPINION, 0, 5, "hello");
        existing.setStatusChangedBy("alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("APPROVED", null), ALICE);

        assertEquals("APPROVED", updated.getStatus());
        assertEquals("alice@example.com", updated.getStatusChangedBy());
    }

    @Test
    void requesterCanResolveTheirOwnSecondOpinionToRejected() {
        final Span existing = span("s1", "d1", "ssn", Span.STATUS_NEEDS_SECOND_OPINION, 0, 5, "hello");
        existing.setStatusChangedBy("alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null), ALICE);

        assertEquals("REJECTED", updated.getStatus());
    }

    @Test
    void differentReviewerCanResolveSecondOpinionToApproved() {
        final Span existing = span("s1", "d1", "ssn", Span.STATUS_NEEDS_SECOND_OPINION, 0, 5, "hello");
        existing.setStatusChangedBy("alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("APPROVED", null), BOB);

        assertEquals("APPROVED", updated.getStatus());
        assertEquals("bob@example.com", updated.getStatusChangedBy());
    }

    @Test
    void differentReviewerCanResolveSecondOpinionToRejected() {
        final Span existing = span("s1", "d1", "ssn", Span.STATUS_NEEDS_SECOND_OPINION, 0, 5, "hello");
        existing.setStatusChangedBy("alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("REJECTED", null), BOB);

        assertEquals("REJECTED", updated.getStatus());
    }

    @Test
    void requesterCanCancelTheirOwnSecondOpinionByMovingToPending() {
        // Self-resolve guard only applies to APPROVED/REJECTED; the requester can return
        // the span to PENDING (effectively cancelling their own request).
        final Span existing = span("s1", "d1", "ssn", Span.STATUS_NEEDS_SECOND_OPINION, 0, 5, "hello");
        existing.setStatusChangedBy("alice@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        final Span updated = controller.updateSpan("s1",
                new SpanUpdateRequest("PENDING", null), ALICE);

        assertEquals("PENDING", updated.getStatus());
    }

    @Test
    void rejectedDocumentBlocksSpanEdits() {
        final Document rejected = document("d1", "");
        rejected.changeStatus("REJECTED");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(rejected));
        final Span existing = span("s1", "d1", "ssn", "PENDING", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1",
                        new SpanUpdateRequest("APPROVED", null), ADMIN));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("REJECTED"));
    }

    @Test
    void documentEndpointsForbidWhenBatchHasNoGroup() {
        // Document exists but its batch has no groupId — non-admins are blocked.
        final Document doc = document("d1", "");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        final Batch batch = new Batch();
        batch.setId("b1");
        // groupId left null
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g1"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSpans("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---- resetSpan ----

    @Test
    void resetSpanRejectsArbitraryStatus() {
        final Span existing = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resetSpan("s1",
                        new ReviewController.ResetSpanRequest("HACKED"), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).save(any());
    }

    @Test
    void resetSpanRejectsNullStatus() {
        final Span existing = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resetSpan("s1",
                        new ReviewController.ResetSpanRequest(null), ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).save(any());
    }

    @Test
    void resetSpanRejectsNullRequest() {
        final Span existing = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resetSpan("s1", null, ADMIN));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(spanRepository, never()).save(any());
    }

    @Test
    void resetSpanAcceptsAllValidStatuses() {
        for (final String status : List.of("APPROVED", "REJECTED", "PENDING", Span.STATUS_NEEDS_SECOND_OPINION)) {
            final Span existing = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
            when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));
            when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

            final Span result = controller.resetSpan("s1",
                    new ReviewController.ResetSpanRequest(status), ADMIN);
            assertEquals(status, result.getStatus(), "Expected status " + status + " to be accepted");
        }
    }

    @Test
    void resetSpanForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", java.util.Set.of("g2"));
        final Span existing = span("s1", "d1", "ssn", "APPROVED", 0, 5, "hello");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(existing));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.resetSpan("s1",
                        new ReviewController.ResetSpanRequest("PENDING"),
                        TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(spanRepository, never()).save(any());
    }

    // ---- who decided a span (#40): exports tell person-decided spans from threshold-accepted ones ----

    /** A span the batch's PII Threshold accepted at ingest: status set with no actor. */
    private static Span thresholdAccepted(final String id, final int start, final int end, final String text) {
        final Span s = span(id, "d1", "person", "PENDING", start, end, text);
        s.changeStatus("APPROVED");
        return s;
    }

    @Test
    void aSpanSetAtIngestAndNeverTouchedWasNotDecidedByAPerson() {
        final Span untouched = thresholdAccepted("s1", 0, 3, "the");
        assertFalse(untouched.decidedByPerson());

        // Changing only its type does not decide it either.
        when(spanRepository.findById("s1")).thenReturn(Optional.of(untouched));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));
        assertFalse(controller.updateSpan("s1", new SpanUpdateRequest(null, "ssn"), ADMIN).decidedByPerson());
    }

    @Test
    void aSpanAPersonAcceptedWasDecidedByThem() {
        when(spanRepository.findById("s1")).thenReturn(Optional.of(span("s1", "d1", "ssn", "PENDING", 0, 3, "the")));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null), ADMIN).decidedByPerson());
    }

    @Test
    void aSpanAPersonRefusedThenResetWasDecidedByThem() {
        final Span span = thresholdAccepted("s1", 0, 3, "the");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(span));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.updateSpan("s1", new SpanUpdateRequest("REJECTED", null), ADMIN);
        final Span reset = controller.resetSpan("s1", new ReviewController.ResetSpanRequest("APPROVED"), ADMIN);

        assertEquals("APPROVED", reset.getStatus());
        assertTrue(reset.decidedByPerson());
    }

    @Test
    void aSpanAddedByHandWasDecidedByAPerson() {
        final Document doc = document("d1", "Hello world, this is a sample document.");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(controller.createSpan("d1", new ReviewController.CreateSpanRequest("ssn", 0, 5), ADMIN)
                .decidedByPerson());
    }

    @Test
    void redactAllLikeThisDecidesTheSpansItApproves() {
        final String text = "alpha and alpha";
        final Span source = span("s1", "d1", "person", "PENDING", 0, 5, "alpha");
        final Span second = thresholdAccepted("s2", 10, 15, "alpha");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(documentRepository.findById("d1")).thenReturn(Optional.of(document("d1", text)));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(source, second));
        when(spanRepository.save(any(Span.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.redactAllLike("s1", ADMIN);

        assertTrue(source.decidedByPerson());
        assertTrue(second.decidedByPerson());
    }

    // ---- boundaries (#20) ----

    /** "the original text": s1 is "the" (0-3), s2 is "text" (13-17). */
    private Span seedBoundaries(final String status) {
        final Span s1 = span("s1", "d1", "person", status, 0, 3, "the");
        final Span s2 = span("s2", "d1", "person", "PENDING", 13, 17, "text");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(s1));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(s1, s2));
        when(spanRepository.save(any(Span.class))).thenAnswer(i -> i.getArgument(0));
        return s1;
    }

    private static SpanUpdateRequest move(final int start, final int end) {
        return new SpanUpdateRequest(null, null, null, null, null, start, end);
    }

    @Test
    void movingASpanAcceptsItKeepsItsDetectionAndAuditsBothPositions() {
        final Span s = seedBoundaries("REJECTED");

        final Span saved = controller.updateSpan("s1", move(0, 12), ADMIN);

        assertEquals("the original", saved.getText());
        assertEquals(0, saved.getLocation().characterStart());
        assertEquals(12, saved.getLocation().characterEnd());
        assertEquals("APPROVED", saved.getStatus(), "moving a span accepts it");
        assertEquals("s1", saved.getId(), "the same span, so the link to the detection is kept");
        assertEquals(0, saved.getOriginalCharacterStart());
        assertEquals(3, saved.getOriginalCharacterEnd());
        assertTrue(saved.isAdjusted());
        assertEquals("admin@example.com", saved.getAdjustedBy());
        final TestDoubles.RecordingAuditLog.Entry adjust = auditLogService.entries.stream()
                .filter(e -> "SPAN_ADJUST".equals(e.action())).findFirst().orElseThrow();
        assertEquals(Map.of("spanId", "s1", "documentId", "d1", "previousStart", 0, "previousEnd", 3, "start", 0,
                "end", 12, "originalStart", 0, "originalEnd", 3, "length", 12), adjust.details());
        assertTrue(auditLogService.entries.stream().anyMatch(e -> "SPAN_UPDATE".equals(e.action())
                && "APPROVED".equals(e.details().get("status")) && "REJECTED".equals(e.details().get("previousStatus"))));
        assertFalse(adjust.details().values().stream().anyMatch(v -> String.valueOf(v).contains("original")),
                "positions only, never the text");
        assertEquals(s, saved);
    }

    @Test
    void theDetectionIsRecordedOnceAndMovingBackClearsAdjusted() {
        seedBoundaries("PENDING");
        controller.updateSpan("s1", move(4, 12), ADMIN);
        final Span again = controller.updateSpan("s1", move(4, 8), ADMIN);
        assertEquals(0, again.getOriginalCharacterStart(), "still where it was detected");
        assertEquals(3, again.getOriginalCharacterEnd());
        assertEquals("orig", again.getText());

        final Span back = controller.updateSpan("s1", move(0, 3), ADMIN);
        assertFalse(back.isAdjusted());
        assertEquals("the", back.getText());
    }

    @Test
    void aMoveCarriesTheExemptionCodeLikeAnyAcceptance() {
        seedBoundaries("PENDING");
        final Span saved = controller.updateSpan("s1",
                new SpanUpdateRequest("APPROVED", null, null, "b6", null, 0, 12), ADMIN);
        assertEquals("b6", saved.getExemptionCode());
    }

    @Test
    void aMoveIsRefusedOntoAnotherSpanOutOfRangeOrWithoutBothEnds() {
        seedBoundaries("PENDING");
        final java.util.function.Function<SpanUpdateRequest, Integer> status = request -> assertThrows(
                ResponseStatusException.class, () -> controller.updateSpan("s1", request, ADMIN))
                .getStatusCode().value();
        assertEquals(409, status.apply(move(0, 14)), "onto s2");
        assertEquals(400, status.apply(move(10, 99)), "past the end");
        assertEquals(400, status.apply(move(5, 5)), "empty");
        assertEquals(400, status.apply(move(3, 4)), "only a space");
        assertEquals(400, status.apply(new SpanUpdateRequest(null, null, null, null, null, 0, null)));
        assertEquals(400, status.apply(new SpanUpdateRequest("REJECTED", null, null, null, null, 0, 12)),
                "moving a span accepts it");
        assertTrue(auditLogService.entries.isEmpty());
        verify(spanRepository, never()).save(any());
    }

    @Test
    void aSpanInAPdfCannotBeMovedYet() {
        final Span s = seedBoundaries("PENDING");
        s.setLocation(new Location(0, 3, 2, new Coordinates(72, 600, 40, 12)));
        final ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", move(0, 12), ADMIN));
        assertEquals(409, e.getStatusCode().value());
        verify(spanRepository, never()).save(any());
    }

    @Test
    void aMoveFollowsTheDocumentAndAdjudicationRules() {
        final Span s = seedAdjudication("APPROVED");
        s.setAdjudicatedBy("ann@example.com");
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(s));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", move(0, 12), REVIEWER)).getStatusCode().value());
        final Span byApprover = controller.updateSpan("s1", move(0, 12), APPROVER);
        assertEquals("ann@example.com", byApprover.getAdjudicatedBy(), "an approver's change is a new adjudication");

        documentRepository.findById("d1").orElseThrow().setStatus("APPROVED");
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", move(0, 3), APPROVER)).getStatusCode().value());
    }

    @Test
    void resetPutsAnAdjustedSpanBackWhereItWasDetected() {
        seedBoundaries("PENDING");
        controller.updateSpan("s1", move(0, 12), ADMIN);
        auditLogService.entries.clear();

        final Span reset = controller.resetSpan("s1", new ReviewController.ResetSpanRequest("PENDING"), ADMIN);

        assertEquals("PENDING", reset.getStatus());
        assertEquals("the", reset.getText());
        assertEquals(0, reset.getLocation().characterStart());
        assertEquals(3, reset.getLocation().characterEnd());
        assertFalse(reset.isAdjusted());
        assertEquals(List.of("SPAN_RESET", "SPAN_ADJUST"),
                auditLogService.entries.stream().map(TestDoubles.RecordingAuditLog.Entry::action).toList());
        assertEquals(12, auditLogService.entries.get(1).details().get("previousEnd"));
        assertEquals(3, auditLogService.entries.get(1).details().get("end"));
    }

    @Test
    void resetLeavesTheBoundariesAloneWhenASpanAddedSinceCoversThem() {
        final Span s = seedBoundaries("PENDING");
        controller.updateSpan("s1", move(1, 3), ADMIN);
        // A span added by hand over the "t" the move freed.
        final Span added = span("s3", "d1", "person", "APPROVED", 0, 1, "t");
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(s, added));
        auditLogService.entries.clear();

        final ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.resetSpan("s1", new ReviewController.ResetSpanRequest("PENDING"), ADMIN));
        assertEquals(409, e.getStatusCode().value());
        assertEquals("APPROVED", s.getStatus(), "nothing changed");
        assertEquals(1, s.getLocation().characterStart());
        assertTrue(auditLogService.entries.isEmpty());
    }

    // ---- approvals and the review lock (#71) ----

    /** d1 with one of two approvals, and s1 ("the", PENDING) on it. */
    private Document seedApproved() {
        final Document d = document("d1", "the original text");
        d.setStatus("REVIEW_REQUIRED");
        d.getApprovedBy().add("first@example.com");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(d));
        final Span s1 = span("s1", "d1", "person", "APPROVED", 0, 3, "the");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(s1));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(s1));
        when(spanRepository.save(any(Span.class))).thenAnswer(i -> i.getArgument(0));
        return d;
    }

    private void assertApprovalsClearedBy(final Document d, final String change) {
        assertTrue(d.getApprovedBy().isEmpty(), change + " clears the approvals");
        verify(documentRepository).save(d);
        final TestDoubles.RecordingAuditLog.Entry cleared = auditLogService.entries.stream()
                .filter(e -> "DOCUMENT_APPROVALS_CLEARED".equals(e.action())).findFirst().orElseThrow();
        assertEquals(change, cleared.details().get("change"));
        assertEquals(List.of("first@example.com"), cleared.details().get("clearedApprovals"));
        assertEquals("admin@example.com", cleared.details().get("actor"));
    }

    @Test
    void refusingASpanAfterTheFirstApprovalClearsIt() {
        final Document d = seedApproved();
        controller.updateSpan("s1", new SpanUpdateRequest("REJECTED", null, "not PII"), ADMIN);
        assertApprovalsClearedBy(d, "SPAN_UPDATE");
    }

    @Test
    void retypingMovingAddingRemovingAndResettingAlsoClearIt() {
        Document d = seedApproved();
        controller.updateSpan("s1", new SpanUpdateRequest(null, "ssn"), ADMIN);
        assertApprovalsClearedBy(d, "SPAN_UPDATE");

        setUp();
        d = seedApproved();
        controller.updateSpan("s1", new SpanUpdateRequest(null, null, null, null, null, 0, 12), ADMIN);
        assertApprovalsClearedBy(d, "SPAN_ADJUST");

        setUp();
        d = seedApproved();
        controller.createSpan("d1", new ReviewController.CreateSpanRequest("person", 13, 17), ADMIN);
        assertApprovalsClearedBy(d, "SPAN_CREATE");

        setUp();
        d = seedApproved();
        final Span manual = span("m1", "d1", "person", "APPROVED", 13, 17, "text");
        manual.setManuallyCreated(true);
        when(spanRepository.findById("m1")).thenReturn(Optional.of(manual));
        controller.deleteSpan("m1", ADMIN);
        assertApprovalsClearedBy(d, "SPAN_DELETE");

        setUp();
        d = seedApproved();
        controller.resetSpan("s1", new ReviewController.ResetSpanRequest("PENDING"), ADMIN);
        assertApprovalsClearedBy(d, "SPAN_RESET");

        setUp();
        d = seedApproved();
        controller.redactAllLike("s1", ADMIN);
        assertApprovalsClearedBy(d, "SPAN_REDACT_LIKE");
    }

    @Test
    void aChangeWithNoApprovalsRecordedSavesNoDocumentAndAuditsNoClearing() {
        final Document d = seedApproved();
        d.getApprovedBy().clear();
        controller.updateSpan("s1", new SpanUpdateRequest("REJECTED", null, "not PII"), ADMIN);
        verify(documentRepository, never()).save(any(Document.class));
        assertFalse(auditLogService.hasAction("DOCUMENT_APPROVALS_CLEARED"));
    }

    @Test
    void noSpanChangesWhileAnotherPersonHoldsTheReviewLock() {
        final Document d = seedApproved();
        d.setLockedBy("holder@example.com");
        d.setLockExpiresAt(java.time.Instant.now().plusSeconds(600));
        final Span manual = span("m1", "d1", "person", "APPROVED", 13, 17, "text");
        manual.setManuallyCreated(true);
        when(spanRepository.findById("m1")).thenReturn(Optional.of(manual));

        final List<org.junit.jupiter.api.function.Executable> changes = List.of(
                () -> controller.updateSpan("s1", new SpanUpdateRequest("REJECTED", null, "x"), ADMIN),
                () -> controller.createSpan("d1", new ReviewController.CreateSpanRequest("person", 13, 17), ADMIN),
                () -> controller.deleteSpan("m1", ADMIN),
                () -> controller.resetSpan("s1", new ReviewController.ResetSpanRequest("PENDING"), ADMIN),
                () -> controller.redactAllLike("s1", ADMIN));
        for (org.junit.jupiter.api.function.Executable change : changes) {
            final ResponseStatusException e = assertThrows(ResponseStatusException.class, change);
            assertEquals(409, e.getStatusCode().value());
        }
        verify(spanRepository, never()).save(any());
        verify(spanRepository, never()).deleteById(any());
        assertEquals(List.of("first@example.com"), d.getApprovedBy(), "nothing cleared");
        assertTrue(auditLogService.entries.isEmpty());
    }

    @Test
    void theLockHolderAndAnExpiredLockDoNotBlockAChange() {
        final Document d = seedApproved();
        d.setLockedBy("admin@example.com");
        d.setLockExpiresAt(java.time.Instant.now().plusSeconds(600));
        controller.updateSpan("s1", new SpanUpdateRequest("REJECTED", null, "x"), ADMIN);

        setUp();
        final Document expired = seedApproved();
        expired.setLockedBy("holder@example.com");
        expired.setLockExpiresAt(java.time.Instant.now().minusSeconds(1));
        controller.updateSpan("s1", new SpanUpdateRequest("REJECTED", null, "x"), ADMIN);
        assertTrue(expired.getApprovedBy().isEmpty());
    }

    // ---- adjudication (#5) ----

    private static final org.springframework.security.core.Authentication APPROVER = TestAuth.user("ann@example.com");
    private static final org.springframework.security.core.Authentication REVIEWER = TestAuth.user("rob@example.com");
    private static final org.springframework.security.core.Authentication OTHER_APPROVER =
            TestAuth.user("oz@example.com");

    private Span seedAdjudication(final String status) {
        seedGroupedDocument();
        userGroupsService.withApprover("ann@example.com", java.util.Set.of("g1"));
        userGroupsService.withMembership("rob@example.com", java.util.Set.of("g1"));
        userGroupsService.withApprover("oz@example.com", java.util.Set.of("g2"));
        final Span s = span("s1", "d1", "person", status, 0, 3, "the");
        s.setStatusChangedBy("rob@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(s));
        when(spanRepository.save(any(Span.class))).thenAnswer(i -> i.getArgument(0));
        return s;
    }

    private static SpanUpdateRequest adjudicate(final String status) {
        return new SpanUpdateRequest(status, null, null, null, true);
    }

    @Test
    void anApproverAdjudicatesASpanAwaitingASecondOpinion() {
        final Span s = seedAdjudication(Span.STATUS_NEEDS_SECOND_OPINION);

        final Span saved = controller.updateSpan("s1", adjudicate("REJECTED"), APPROVER);

        assertEquals("REJECTED", saved.getStatus());
        assertEquals("ann@example.com", saved.getAdjudicatedBy());
        org.junit.jupiter.api.Assertions.assertNotNull(saved.getAdjudicatedAt());
        assertTrue(auditLogService.entries.stream().anyMatch(e -> "SPAN_ADJUDICATE".equals(e.action())),
                "recorded as an adjudication");
        assertEquals(s, saved);
    }

    @Test
    void onlyAnApproverOfTheDocumentsGroupCanAdjudicate() {
        seedAdjudication("APPROVED");
        final ResponseStatusException reviewer = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", adjudicate("APPROVED"), REVIEWER));
        assertEquals(403, reviewer.getStatusCode().value());
        // An approver of another group cannot see the document at all.
        final ResponseStatusException outsider = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", adjudicate("APPROVED"), OTHER_APPROVER));
        assertEquals(404, outsider.getStatusCode().value());
        // An adjudication accepts or refuses.
        final ResponseStatusException pending = assertThrows(ResponseStatusException.class,
                () -> controller.updateSpan("s1", adjudicate("PENDING"), APPROVER));
        assertEquals(400, pending.getStatusCode().value());
        assertTrue(auditLogService.entries.isEmpty());
    }

    @Test
    void onlyAnApproverCanChangeAnAdjudicatedSpanAndTheirChangeIsANewAdjudication() {
        final Span s = seedAdjudication("REJECTED");
        s.setAdjudicatedBy("ann@example.com");

        for (Runnable change : List.<Runnable>of(
                () -> controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null), REVIEWER),
                () -> controller.updateSpan("s1", new SpanUpdateRequest(null, "location"), REVIEWER),
                () -> controller.resetSpan("s1", new ReviewController.ResetSpanRequest("PENDING"), REVIEWER))) {
            final ResponseStatusException e = assertThrows(ResponseStatusException.class, change::run);
            assertEquals(403, e.getStatusCode().value());
        }
        assertEquals("REJECTED", s.getStatus());

        userGroupsService.withApprover("amy@example.com", java.util.Set.of("g1"));
        final Span saved = controller.updateSpan("s1", new SpanUpdateRequest("APPROVED", null),
                TestAuth.user("amy@example.com"));
        assertEquals("amy@example.com", saved.getAdjudicatedBy(), "an approver's change is theirs");
        assertTrue(auditLogService.entries.stream().anyMatch(e -> "SPAN_ADJUDICATE".equals(e.action())
                && "ann@example.com".equals(e.details().get("previousAdjudicatedBy"))));
    }

    @Test
    void resettingAnAdjudicatedSpanWithdrawsTheAdjudication() {
        final Span s = seedAdjudication("REJECTED");
        s.setAdjudicatedBy("ann@example.com");
        final Span saved = controller.resetSpan("s1", new ReviewController.ResetSpanRequest("PENDING"), APPROVER);
        assertEquals("PENDING", saved.getStatus());
        assertEquals(null, saved.getAdjudicatedBy());
    }

    @Test
    void redactAllLikeThisLeavesAdjudicatedSpansAlone() {
        seedGroupedDocument();
        final Document doc = document("d1", "the cat and the dog");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        userGroupsService.withMembership("rob@example.com", java.util.Set.of("g1"));
        final Span source = span("s1", "d1", "person", "PENDING", 0, 3, "the");
        final Span adjudicated = span("s2", "d1", "person", "REJECTED", 12, 15, "the");
        adjudicated.setAdjudicatedBy("ann@example.com");
        when(spanRepository.findById("s1")).thenReturn(Optional.of(source));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(source, adjudicated));

        final Map<String, Object> result = controller.redactAllLike("s1", REVIEWER);

        assertEquals(1, result.get("adjudicatedSkipped"));
        assertEquals(0, result.get("approved"));
        assertEquals("REJECTED", adjudicated.getStatus());

        // Nor can a reviewer start from an adjudicated span.
        when(spanRepository.findById("s2")).thenReturn(Optional.of(adjudicated));
        adjudicated.setStatus("APPROVED");
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.redactAllLike("s2", REVIEWER)).getStatusCode().value());
    }

    @Test
    void anAdjudicatedSpanAddedByHandIsRemovedOnlyByAnApprover() {
        final Span s = seedAdjudication("APPROVED");
        s.setManuallyCreated(true);
        s.setAdjudicatedBy("ann@example.com");
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.deleteSpan("s1", REVIEWER)).getStatusCode().value());
        controller.deleteSpan("s1", APPROVER);
        verify(spanRepository).deleteById("s1");
    }
}
