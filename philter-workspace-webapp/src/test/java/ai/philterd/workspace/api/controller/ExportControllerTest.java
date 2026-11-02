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

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Coordinates;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.service.DocumentFinalizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportControllerTest {

    private static final Authentication ADMIN = TestAuth.admin("admin@example.com");

    private DocumentFinalizationService finalizationService;
    private SpanRepository spanRepository;
    private DocumentRepository documentRepository;
    private BatchRepository batchRepository;
    private TestDoubles.FakeUserGroups userGroupsService;
    private ExportController controller;

    @BeforeEach
    void setUp() {
        finalizationService = mock(DocumentFinalizationService.class);
        spanRepository = mock(SpanRepository.class);
        documentRepository = mock(DocumentRepository.class);
        batchRepository = mock(BatchRepository.class);
        userGroupsService = TestDoubles.userGroups();
        final ai.philterd.workspace.service.DocumentAccessService access =
                TestDoubles.documentAccess(batchRepository, documentRepository, userGroupsService);
        controller = new ExportController(finalizationService, spanRepository, documentRepository, access,
                new ai.philterd.workspace.service.BlindDoubleReviewService(documentRepository, spanRepository,
                        mock(ai.philterd.workspace.repository.BlindReviewSpanRepository.class), access,
                        mock(ai.philterd.workspace.service.AuditLogService.class)));

        final Document doc = approvedDoc("d1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
    }

    private static Document approvedDoc(final String id) {
        final Document doc = new Document();
        doc.setId(id);
        doc.changeStatus("APPROVED");
        return doc;
    }

    private static Document docWithStatus(final String id, final String status) {
        final Document doc = new Document();
        doc.setId(id);
        if (status != null) doc.changeStatus(status);
        return doc;
    }

    private static Span span(final String id, final String type, final String text, final double conf, final String status) {
        final Span s = new Span();
        s.setId(id);
        s.setType(type);
        s.setText(text);
        s.setConfidence(conf);
        s.setStatus(status);
        s.setLocation(new Location(0, text.length(), 1, new Coordinates(0, 0, 0, 0)));
        return s;
    }

    @Test
    void finalizeReturnsServiceResult() throws IOException {
        when(finalizationService.finalizeDocument(org.mockito.ArgumentMatchers.argThat(d -> "d1".equals(d.getId())), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("api"))).thenReturn("hello <<SSN>>");

        final Map<String, String> result = controller.finalize("d1", ADMIN);

        assertEquals("hello <<SSN>>", result.get("finalizedText"));
    }

    @Test
    void auditMapsAllSpanFields() {
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of(
                span("s1", "ssn", "123-45-6789", 0.95, "APPROVED"),
                span("s2", "phone-number", "555-1234", 0.4, "PENDING")));

        final List<Map<String, Object>> result = controller.audit("d1", ADMIN);

        assertEquals(2, result.size());
        assertEquals("123-45-6789", result.get(0).get("text"));
        assertEquals("ssn", result.get(0).get("type"));
        assertEquals(0.95, result.get(0).get("confidence"));
        assertEquals("APPROVED", result.get(0).get("status"));
        assertEquals("PENDING", result.get(1).get("status"));
    }

    @Test
    void auditIsRefusedToTheBlindReviewerAndRecordsWhoeverElseReadsIt() {
        // #86: the statuses are the first review's decisions.
        final Document doc = approvedDoc("d1");
        doc.setDoubleReview(true);
        doc.setFirstReviewer("first@example.com");
        doc.setSecondReviewer("second@example.com");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(documentRepository.save(org.mockito.ArgumentMatchers.any(Document.class)))
                .thenAnswer(i -> i.getArgument(0));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of());

        final org.springframework.web.server.ResponseStatusException refused = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.audit("d1", TestAuth.admin("second@example.com")));
        assertEquals(409, refused.getStatusCode().value());

        controller.audit("d1", ADMIN);
        assertEquals(List.of("admin@example.com"), doc.getFirstReviewSeenBy());
    }

    @Test
    void auditReturnsEmptyListWhenNoSpans() {
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of());

        final List<Map<String, Object>> result = controller.audit("d1", ADMIN);

        assertTrue(result.isEmpty());
    }

    // ---- group-access enforcement (non-admin) ----

    private void seedGroupedDocument() {
        seedGroupedDocumentWithStatus("APPROVED");
    }

    private void seedGroupedDocumentWithStatus(final String status) {
        final Document doc = docWithStatus("d1", status);
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
    }

    @Test
    void finalizeForbidsNonAdminOutsideGroup() throws IOException {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", Set.of("g2"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.finalize("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(finalizationService, never()).finalizeDocument(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void auditForbidsNonAdminOutsideGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", Set.of("g2"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.audit("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(spanRepository, never()).findByDocumentId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void auditAllowsNonAdminInGroup() {
        seedGroupedDocument();
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));
        when(spanRepository.findByDocumentId("d1")).thenReturn(List.of());

        final List<Map<String, Object>> result = controller.audit("d1", TestAuth.user("alice@example.com"));
        assertTrue(result.isEmpty());
    }

    // ---- refusals (the rules themselves are DocumentFinalizationServiceTest's) ----

    @Test
    void aRefusalFromTheFinalizationServiceIsAnsweredWithItsStatus() {
        seedGroupedDocumentWithStatus("PENDING");
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));
        when(finalizationService.finalizeDocument(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenThrow(new DocumentFinalizationService.FinalizationRefused(
                        HttpStatus.CONFLICT, "Only APPROVED documents can be finalized."));
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.finalize("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Only APPROVED documents can be finalized.", ex.getReason());
    }

    @Test
    void finalizeAllowsApprovedDocument() throws IOException {
        seedGroupedDocument(); // status = APPROVED
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));
        when(finalizationService.finalizeDocument(org.mockito.ArgumentMatchers.argThat(d -> "d1".equals(d.getId())), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("api"))).thenReturn("redacted text");

        final Map<String, String> result = controller.finalize("d1", TestAuth.user("alice@example.com"));
        assertEquals("redacted text", result.get("finalizedText"));
    }

    @Test
    void documentEndpointsForbidWhenBatchHasNoGroup() {
        final Document doc = new Document();
        doc.setId("d1");
        doc.setBatchId("b1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        final Batch batch = new Batch();
        batch.setId("b1");
        // groupId left null
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.audit("d1", TestAuth.user("alice@example.com")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
