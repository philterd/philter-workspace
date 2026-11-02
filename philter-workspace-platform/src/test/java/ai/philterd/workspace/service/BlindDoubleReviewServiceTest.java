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

import ai.philterd.workspace.model.BlindReviewSpan;
import ai.philterd.workspace.model.Coordinates;
import ai.philterd.workspace.model.DetectedSpan;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.BlindReviewSpanRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The rules of blind double review (#86). */
class BlindDoubleReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final SpanRepository spans = mock(SpanRepository.class);
    private final BlindReviewSpanRepository copies = mock(BlindReviewSpanRepository.class);
    private final DocumentAccessService access = mock(DocumentAccessService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private BlindDoubleReviewService service;

    @BeforeEach
    void setUp() {
        service = new BlindDoubleReviewService(documents, spans, copies, access, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        when(documents.save(any(Document.class))).thenAnswer(i -> i.getArgument(0));
        when(access.canApproveDocument(any(), any())).thenReturn(true);
    }

    private static Authentication user(final String email, final String role) {
        return new UsernamePasswordAuthenticationToken(email, null, Set.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Document approved() {
        final Document d = new Document();
        d.setId("d1");
        d.setOriginalText("Jane Roe, SSN 123-45-6789");
        d.setStatus("APPROVED");
        d.setDoubleReview(true);
        d.setFirstReviewer("first@example.com");
        d.getApprovedBy().add("first@example.com");
        return d;
    }

    private static Location at(final int start, final int end) {
        return new Location(start, end, 1, new Coordinates(0, 0, 0, 0));
    }

    private static Span span(final String type, final String status, final int start, final int end) {
        final Span s = new Span();
        s.setId("s" + start);
        s.setType(type);
        s.setStatus(status);
        s.setLocation(at(start, end));
        return s;
    }

    private static BlindReviewSpan copy(final String type, final String status, final int start, final int end) {
        final BlindReviewSpan s = new BlindReviewSpan();
        s.setId("c" + start);
        s.setDocumentId("d1");
        s.setType(type);
        s.setStatus(status);
        s.setLocation(at(start, end));
        return s;
    }

    @Test
    void whoSeesWhat() {
        final Document d = approved();
        d.getFirstReviewSeenBy().add("looked@example.com");

        assertEquals(BlindDoubleReviewService.View.RECORD, BlindDoubleReviewService.view(user("first@example.com", "USER"), d));
        assertEquals(BlindDoubleReviewService.View.RECORD, BlindDoubleReviewService.view(user("LOOKED@example.com", "USER"), d));
        assertEquals(BlindDoubleReviewService.View.RECORD, BlindDoubleReviewService.view(user("auditor@example.com", "AUDITOR"), d));
        assertEquals(BlindDoubleReviewService.View.LANDING, BlindDoubleReviewService.view(user("new@example.com", "USER"), d));
        assertTrue(BlindDoubleReviewService.mayStart(user("new@example.com", "USER"), d));
        assertFalse(BlindDoubleReviewService.mayStart(user("auditor@example.com", "AUDITOR"), d), "an auditor only reads");

        d.setSecondReviewer("new@example.com");
        assertEquals(BlindDoubleReviewService.View.BLIND, BlindDoubleReviewService.view(user("new@example.com", "USER"), d));
        assertEquals(BlindDoubleReviewService.View.LANDING, BlindDoubleReviewService.view(user("other@example.com", "USER"), d));
        assertFalse(BlindDoubleReviewService.mayStart(user("other@example.com", "USER"), d), "one blind review at a time");

        // While the first review is in progress the document is reviewed as usual.
        final Document inReview = approved();
        inReview.setStatus("REVIEW_REQUIRED");
        assertEquals(BlindDoubleReviewService.View.RECORD,
                BlindDoubleReviewService.view(user("new@example.com", "USER"), inReview));
    }

    @Test
    void startingCopiesTheDetectionsAsUnreviewed() {
        final Document d = approved();
        d.setDoubleReviewDetections(List.of(new DetectedSpan("person", 0.99, at(0, 8)),
                new DetectedSpan("ssn", 0.5, at(14, 25)), new DetectedSpan("ssn", 0.5, at(20, 99))));

        service.start(user("new@example.com", "USER"), d);

        assertEquals("new@example.com", d.getSecondReviewer());
        assertEquals(NOW, d.getSecondReviewStartedAt());
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<BlindReviewSpan>> saved = ArgumentCaptor.forClass(List.class);
        verify(copies).saveAll(saved.capture());
        assertEquals(2, saved.getValue().size(), "a detection past the end of the text is left out");
        assertEquals("Jane Roe", saved.getValue().get(0).getText());
        assertTrue(saved.getValue().stream().allMatch(c -> "PENDING".equals(c.getStatus())
                && c.getStatusChangedBy() == null), "a high-confidence detection is unreviewed too");
        verify(spans, never()).findByDocumentId(anyString());
        verify(audit).log(eq("DOUBLE_REVIEW_START"), eq("Document"), eq("d1"), any());
    }

    @Test
    void startingIsRefusedToSomeoneWhoSawTheFirstReviewOrWhenTheDocumentChanged() {
        final Document d = approved();
        assertThrows(BlindDoubleReviewService.Refused.class, () -> service.start(user("first@example.com", "USER"), d));

        when(documents.save(any(Document.class))).thenThrow(new OptimisticLockingFailureException("changed"));
        assertThrows(BlindDoubleReviewService.Refused.class, () -> service.start(user("new@example.com", "USER"), d));
        verify(copies, never()).saveAll(any());
    }

    @Test
    void submittingNeedsEverySpanDecided() {
        final Document d = approved();
        d.setSecondReviewer("new@example.com");
        when(copies.findByDocumentId("d1")).thenReturn(List.of(copy("person", "APPROVED", 0, 8),
                copy("ssn", "PENDING", 14, 25)));
        final BlindDoubleReviewService.Refused refused = assertThrows(BlindDoubleReviewService.Refused.class,
                () -> service.submit(user("new@example.com", "USER"), d));
        assertTrue(refused.getMessage().startsWith("1 span has"));

        when(copies.findByDocumentId("d1")).thenReturn(List.of(copy("person", "APPROVED", 0, 8),
                copy("ssn", "REJECTED", 14, 25)));
        assertThrows(BlindDoubleReviewService.Refused.class, () -> service.submit(user("other@example.com", "USER"), d));
        service.submit(user("new@example.com", "USER"), d);

        assertEquals(1, d.getSecondReviewSpans().size());
        assertEquals(NOW, d.getSecondReviewSubmittedAt());
        assertTrue(BlindDoubleReviewService.awaitingReconciliation(d));
        assertEquals(BlindDoubleReviewService.View.RECORD, BlindDoubleReviewService.view(user("new@example.com", "USER"), d));
    }

    @Test
    void disagreementsCompareTheDocumentsSpansWithTheBlindReview() {
        final Document d = approved();
        d.setSecondReviewer("new@example.com");
        d.setSecondReviewSpans(List.of());
        when(spans.findByDocumentId("d1")).thenReturn(List.of(span("person", "APPROVED", 0, 8),
                span("ssn", "APPROVED", 14, 25), span("person", "REJECTED", 10, 13)));
        when(copies.findByDocumentId("d1")).thenReturn(List.of(copy("person", "APPROVED", 0, 8),
                copy("phone", "APPROVED", 14, 25), copy("person", "APPROVED", 10, 13)));

        final List<BlindDoubleReviewService.Disagreement> found = service.disagreements(d);

        assertEquals(2, found.size());
        assertEquals("SSN", found.get(0).text());
        assertNull(found.get(0).firstType(), "the first review refused it");
        assertEquals("person", found.get(0).secondType());
        assertEquals("123-45-6789", found.get(1).text());
        assertEquals("ssn", found.get(1).firstType());
        assertEquals("phone", found.get(1).secondType());
    }

    @Test
    void aSecondReviewRecordedBeforeCopiesWereKeptIsComparedByItsRanges() {
        final Document d = approved();
        d.setSecondReviewSpans(List.of(new int[]{0, 8}, new int[]{14, 25}));
        when(spans.findByDocumentId("d1")).thenReturn(List.of(span("person", "APPROVED", 0, 8)));
        when(copies.findByDocumentId("d1")).thenReturn(List.of());

        final List<BlindDoubleReviewService.Disagreement> found = service.disagreements(d);

        assertEquals(1, found.size());
        assertNull(found.get(0).firstType());
        assertEquals("", found.get(0).secondType(), "redacted, of a type not kept");
    }

    @Test
    void reconcilingIsForAnApproverWhoDidNeitherReview() {
        final Document d = approved();
        d.setSecondReviewer("new@example.com");
        d.setSecondReviewSpans(List.of());
        when(spans.findByDocumentId("d1")).thenReturn(List.of());
        when(copies.findByDocumentId("d1")).thenReturn(List.of());

        for (String reviewer : List.of("first@example.com", "NEW@example.com")) {
            assertThrows(BlindDoubleReviewService.Refused.class, () -> service.reconcile(user(reviewer, "ADMIN"), d));
        }
        when(access.canApproveDocument(any(), any())).thenReturn(false);
        assertThrows(BlindDoubleReviewService.Refused.class, () -> service.reconcile(user("member@example.com", "USER"), d));

        when(access.canApproveDocument(any(), any())).thenReturn(true);
        d.setStatus("REVIEW_REQUIRED");
        assertThrows(BlindDoubleReviewService.Refused.class, () -> service.reconcile(user("lead@example.com", "USER"), d),
                "the document is decided again first");

        d.setStatus("APPROVED");
        service.reconcile(user("lead@example.com", "USER"), d);
        assertEquals("lead@example.com", d.getDoubleReviewReconciledBy());
        assertEquals(NOW, d.getDoubleReviewReconciledAt());
        assertNull(BlindDoubleReviewService.finalizationRefusal(d));
    }

    @Test
    void finalizationWaitsForBothReviewsAndTheirReconciliation() {
        final Document d = approved();
        assertNotNull(BlindDoubleReviewService.finalizationRefusal(d));
        d.setSecondReviewSpans(List.of());
        assertTrue(BlindDoubleReviewService.finalizationRefusal(d).contains("reconciled"));
        d.setDoubleReviewReconciledAt(NOW);
        assertNull(BlindDoubleReviewService.finalizationRefusal(d));

        final Document single = approved();
        single.setDoubleReview(false);
        assertNull(BlindDoubleReviewService.finalizationRefusal(single));
    }

    @Test
    void releasingDiscardsTheCopySoSomeoneElseCanStart() {
        final Document d = approved();
        d.setSecondReviewer("new@example.com");
        d.setSecondReviewStartedAt(NOW);
        when(copies.deleteByDocumentId("d1")).thenReturn(3L);
        when(access.canApproveDocument(any(), any())).thenReturn(false);
        assertThrows(BlindDoubleReviewService.Refused.class, () -> service.release(user("member@example.com", "USER"), d));

        when(access.canApproveDocument(any(), any())).thenReturn(true);
        service.release(user("lead@example.com", "USER"), d);

        assertNull(d.getSecondReviewer());
        assertNull(d.getSecondReviewStartedAt());
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("DOUBLE_REVIEW_RELEASE"), eq("Document"), eq("d1"), details.capture());
        assertEquals("new@example.com", details.getValue().get("secondReviewer"));
        assertEquals(3L, details.getValue().get("spans"));
        assertTrue(BlindDoubleReviewService.mayStart(user("other@example.com", "USER"), d));
    }

    @Test
    void beingShownTheSpansIsRecordedOnceAndRetriedWhenTheDocumentChanged() {
        final Document d = approved();
        d.setStatus("REVIEW_REQUIRED");
        final Document fresh = approved();
        fresh.setStatus("REVIEW_REQUIRED");
        when(documents.save(any(Document.class)))
                .thenThrow(new OptimisticLockingFailureException("changed"))
                .thenAnswer(i -> i.getArgument(0));
        when(documents.findById("d1")).thenReturn(Optional.of(fresh));

        final Document saved = service.noteSeen(user("Looker@Example.com", "USER"), d);

        assertEquals(List.of("looker@example.com"), saved.getFirstReviewSeenBy());
        assertEquals(List.of("looker@example.com"), service.noteSeen(user("looker@example.com", "USER"), saved)
                .getFirstReviewSeenBy(), "not recorded twice");
        assertTrue(service.noteSeen(user("auditor@example.com", "AUDITOR"), approved()).getFirstReviewSeenBy().isEmpty());
        final Document single = approved();
        single.setDoubleReview(false);
        assertTrue(service.noteSeen(user("looker@example.com", "USER"), single).getFirstReviewSeenBy().isEmpty());
    }

    @Test
    void whatShowsTheDecisionsIsRefusedToTheBlindReviewerAndRecordedForOthers() {
        final Document d = approved();
        d.setSecondReviewer("new@example.com");
        assertThrows(BlindDoubleReviewService.Refused.class, () -> service.showSpans(user("new@example.com", "ADMIN"), d));
        assertTrue(d.getFirstReviewSeenBy().isEmpty());

        service.showSpans(user("admin@example.com", "ADMIN"), d);
        assertEquals(List.of("admin@example.com"), d.getFirstReviewSeenBy());
    }
}
