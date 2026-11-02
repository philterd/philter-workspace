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

import ai.philterd.workspace.model.AuditLog;
import ai.philterd.workspace.model.Coordinates;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.AuditLogRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.ComplianceProfileRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.service.AuditLogQueryService;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.UserGroupsService;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the audit-log CSV export at {@code GET /api/v1/documents/{id}/history.csv}:
 * admin-only access, the export-itself-is-audited semantics, and the column shape
 * that omits PII text in favor of span location.
 */
class SecondOpinionsControllerTest {

    private SpanRepository spanRepository;
    private DocumentRepository documentRepository;
    private BatchRepository batchRepository;
    private ComplianceProfileRepository complianceProfileRepository;
    private UserGroupsService userGroupsService;
    private AuditLogRepository auditLogRepository;
    private AuditLogQueryService auditLogQueryService;
    private AuditLogService auditLogService;
    private SecondOpinionsController controller;

    @BeforeEach
    void setUp() {
        spanRepository = mock(SpanRepository.class);
        documentRepository = mock(DocumentRepository.class);
        batchRepository = mock(BatchRepository.class);
        complianceProfileRepository = mock(ComplianceProfileRepository.class);
        userGroupsService = mock(UserGroupsService.class);
        auditLogRepository = mock(AuditLogRepository.class);
        auditLogQueryService = mock(AuditLogQueryService.class);
        auditLogService = mock(AuditLogService.class);
        // hashForAudit is called from documentHistoryCsv when building audit details
        // (R2-F7). Default the mock to return "" for unstubbed inputs so Map.of(...)
        // doesn't NPE. Tests that assert on the hash value override this default.
        when(auditLogService.hashForAudit(any())).thenReturn("");
        controller = new SecondOpinionsController(spanRepository, documentRepository, batchRepository,
                complianceProfileRepository, userGroupsService, auditLogRepository,
                auditLogQueryService, auditLogService, new ObjectMapper(),
                org.mockito.Mockito.mock(ai.philterd.workspace.service.BlindDoubleReviewService.class));
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication user() {
        return new UsernamePasswordAuthenticationToken("user@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static Document doc(final String id, final String filename) {
        final Document d = new Document();
        d.setId(id);
        d.setFilename(filename);
        return d;
    }

    private static Span span(final String id, final String type, final String text,
                             final int charStart, final int charEnd, final int page) {
        final Span s = new Span();
        s.setId(id);
        s.setDocumentId("doc1");
        s.setType(type);
        s.setText(text);
        s.setLocation(new Location(charStart, charEnd, page, new Coordinates(0, 0, 0, 0)));
        return s;
    }

    private static AuditLog entry(final String action, final String resourceType,
                                  final String resourceId, final Instant ts) {
        final AuditLog e = new AuditLog();
        e.setAction(action);
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setTimestamp(ts);
        e.setUserEmail("admin@x.com");
        e.setOutcome("SUCCESS");
        return e;
    }

    @Test
    void nonAdminGets403() throws IOException {
        final MockHttpServletResponse response = new MockHttpServletResponse();

        controller.documentHistoryCsv("doc1", user(), response);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        // We must NOT have looked up the document or written an export audit entry.
        verify(documentRepository, never()).findById(any());
        verify(auditLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void unauthenticatedGets403() throws IOException {
        final MockHttpServletResponse response = new MockHttpServletResponse();

        controller.documentHistoryCsv("doc1", null, response);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        verify(auditLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void missingDocumentReturns404AndDoesNotAuditExport() throws IOException {
        when(documentRepository.findById("doc1")).thenReturn(Optional.empty());
        final MockHttpServletResponse response = new MockHttpServletResponse();

        controller.documentHistoryCsv("doc1", admin(), response);

        assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getStatus());
        // No export entry should be written if the document doesn't exist.
        verify(auditLogService, never()).log(any(), any(), any(), any());
        verify(auditLogQueryService, never()).findForDocument(any(), any());
    }

    @Test
    void exportEventIsRecordedBeforeQuerying() throws IOException {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "report.txt")));
        when(spanRepository.findByDocumentId("doc1")).thenReturn(List.of());
        when(auditLogQueryService.findForDocument(eq("doc1"), any())).thenReturn(List.of());
        final MockHttpServletResponse response = new MockHttpServletResponse();

        controller.documentHistoryCsv("doc1", admin(), response);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());

        // The export event MUST be written before the query so it appears in the
        // results returned to the CSV writer (and thus as the top row).
        final InOrder order = inOrder(auditLogService, auditLogQueryService);
        order.verify(auditLogService).log(eq("DOCUMENT_AUDIT_EXPORT"), eq("Document"), eq("doc1"), any());
        order.verify(auditLogQueryService).findForDocument(eq("doc1"), any());
    }

    @Test
    void exportEntryDetailsCaptureFormatAndFilenameHash() throws IOException {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "report.txt")));
        when(spanRepository.findByDocumentId("doc1")).thenReturn(List.of());
        when(auditLogQueryService.findForDocument(eq("doc1"), any())).thenReturn(List.of());
        when(auditLogService.hashForAudit("report.txt")).thenReturn("hashed-filename");
        final MockHttpServletResponse response = new MockHttpServletResponse();

        controller.documentHistoryCsv("doc1", admin(), response);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("DOCUMENT_AUDIT_EXPORT"), eq("Document"), eq("doc1"),
                details.capture());
        assertEquals("csv", details.getValue().get("format"));
        // R2-F7: filename is hashed in audit details — filenames carry PII and
        // the audit log is read by auditors who may not have access to the source.
        // Controller must funnel the filename through auditLogService.hashForAudit;
        // the actual HMAC behaviour is pinned by AuditLogServiceTest.
        assertFalse(details.getValue().containsKey("filename"),
                "audit details must not contain the cleartext filename");
        assertEquals("hashed-filename", details.getValue().get("filenameHash"));
        assertEquals("report.txt".length(), details.getValue().get("filenameLength"));
    }

    @Test
    void csvSetsAttachmentHeadersAndOmitsPiiTextWhileIncludingSpanLocation() throws IOException {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "report.txt")));
        // The redaction left one SSN span at offsets [10, 21] on page 3.
        final Span ssn = span("span1", "ssn", "555-12-3456", 10, 21, 3);
        when(spanRepository.findByDocumentId("doc1")).thenReturn(List.of(ssn));

        // Server returns DESC by timestamp. The first row should be the export event itself.
        final Map<String, Object> spanDetails = new LinkedHashMap<>();
        spanDetails.put("previousStatus", "PENDING");
        spanDetails.put("status", "APPROVED");
        final AuditLog spanUpdate = entry("SPAN_UPDATE", "Span", "span1",
                Instant.parse("2026-05-05T12:00:00Z"));
        spanUpdate.setDetails(spanDetails);
        final AuditLog exportEvent = entry("DOCUMENT_AUDIT_EXPORT", "Document", "doc1",
                Instant.parse("2026-05-05T12:30:00Z"));
        when(auditLogQueryService.findForDocument(eq("doc1"), any()))
                .thenReturn(List.of(exportEvent, spanUpdate));

        final MockHttpServletResponse response = new MockHttpServletResponse();
        controller.documentHistoryCsv("doc1", admin(), response);

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertTrue(response.getContentType() != null && response.getContentType().startsWith("text/csv"),
                "content-type was: " + response.getContentType());
        final String disposition = response.getHeader("Content-Disposition");
        assertTrue(disposition != null && disposition.contains("audit-log-doc1.csv"),
                "Content-Disposition: " + disposition);

        final String body = response.getContentAsString();
        final String[] lines = body.split("\n");
        // Header + 2 rows.
        assertEquals(3, lines.length);
        assertEquals("timestamp,actor,action,resourceType,resourceId,"
                + "spanType,spanCharacterStart,spanCharacterEnd,spanPage,details", lines[0]);

        // Row 1 = the export event itself (top of the CSV).
        assertTrue(lines[1].contains("DOCUMENT_AUDIT_EXPORT"), "row 1 was: " + lines[1]);
        assertTrue(lines[1].contains("Document,doc1"), "row 1 was: " + lines[1]);

        // Row 2 = the span update — span location columns populated, no PII text.
        assertTrue(lines[2].contains("SPAN_UPDATE"), "row 2 was: " + lines[2]);
        assertTrue(lines[2].contains(",ssn,10,21,3,"), "row 2 was: " + lines[2]);
        assertFalse(body.contains("555-12-3456"),
                "PII text leaked into export: " + body);
    }

    // ---- documentHistory (JSON) admin-only enforcement ----

    @Test
    void documentHistoryJsonForbidsReviewer() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.documentHistory("doc1", false, user()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(documentRepository, never()).findById(any());
    }

    @Test
    void documentHistoryJsonForbidsUnauthenticated() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.documentHistory("doc1", false, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(documentRepository, never()).findById(any());
    }

    @Test
    void documentHistoryJsonAllowsAdmin() {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "report.txt")));
        when(spanRepository.findByDocumentId("doc1")).thenReturn(List.of());
        when(auditLogQueryService.findForDocument(eq("doc1"), any())).thenReturn(List.of());

        final List<Map<String, Object>> result = controller.documentHistory("doc1", false, admin());

        assertTrue(result.isEmpty());
        verify(documentRepository).findById("doc1");
    }

    // ---- resolveActors parameter ----

    private static AuditLog entryWithUserId(final String action, final String email,
                                            final String userId, final Instant ts) {
        final AuditLog e = new AuditLog();
        e.setAction(action);
        e.setResourceType("Document");
        e.setResourceId("doc1");
        e.setTimestamp(ts);
        e.setUserEmail(email);
        e.setUserId(userId);
        e.setOutcome("SUCCESS");
        return e;
    }

    @Test
    void documentHistoryDefaultsToUserId() {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "x.txt")));
        when(spanRepository.findByDocumentId("doc1")).thenReturn(List.of());
        final AuditLog e = entryWithUserId("SPAN_UPDATE", "reviewer@x.com", "user-id-123",
                Instant.now());
        when(auditLogQueryService.findForDocument(eq("doc1"), any())).thenReturn(List.of(e));

        final List<Map<String, Object>> result = controller.documentHistory("doc1", false, admin());

        assertEquals(1, result.size());
        assertEquals("user-id-123", result.get(0).get("actor"),
                "actor must be userId by default");
    }

    @Test
    void documentHistoryResolveActorsTrueReturnsEmail() {
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "x.txt")));
        when(spanRepository.findByDocumentId("doc1")).thenReturn(List.of());
        final AuditLog e = entryWithUserId("SPAN_UPDATE", "reviewer@x.com", "user-id-123",
                Instant.now());
        when(auditLogQueryService.findForDocument(eq("doc1"), any())).thenReturn(List.of(e));

        final List<Map<String, Object>> result = controller.documentHistory("doc1", true, admin());

        assertEquals("reviewer@x.com", result.get(0).get("actor"),
                "resolveActors=true must return email for admin");
    }

    @Test
    void spanHistoryDefaultsToUserId() {
        final ai.philterd.workspace.model.Span span = new ai.philterd.workspace.model.Span();
        span.setId("span1");
        span.setDocumentId("doc1");
        when(spanRepository.findById("span1")).thenReturn(Optional.of(span));
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "x.txt")));
        final AuditLog e = entryWithUserId("SPAN_UPDATE", "reviewer@x.com", "user-id-456",
                Instant.now());
        e.setResourceType("Span");
        e.setResourceId("span1");
        when(auditLogRepository.findByResourceTypeAndResourceIdOrderByTimestampAsc("Span", "span1"))
                .thenReturn(List.of(e));

        final List<Map<String, Object>> result = controller.history("span1", false, admin());

        assertEquals(1, result.size());
        assertEquals("user-id-456", result.get(0).get("actor"),
                "actor must be userId by default");
    }

    @Test
    void spanHistoryResolveActorsTrueReturnsEmail() {
        final ai.philterd.workspace.model.Span span = new ai.philterd.workspace.model.Span();
        span.setId("span1");
        span.setDocumentId("doc1");
        when(spanRepository.findById("span1")).thenReturn(Optional.of(span));
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "x.txt")));
        final AuditLog e = entryWithUserId("SPAN_UPDATE", "reviewer@x.com", "user-id-456",
                Instant.now());
        e.setResourceType("Span");
        e.setResourceId("span1");
        when(auditLogRepository.findByResourceTypeAndResourceIdOrderByTimestampAsc("Span", "span1"))
                .thenReturn(List.of(e));

        final List<Map<String, Object>> result = controller.history("span1", true, admin());

        assertEquals("reviewer@x.com", result.get(0).get("actor"),
                "resolveActors=true must return email for admin");
    }

    @Test
    void spanHistoryResolveActorsForbidsNonAdmin() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.history("span1", true, user()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(spanRepository, never()).findById(any());
    }

    @Test
    void spanHistoryResolveActorsForbidsUnauthenticated() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.history("span1", true, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void actorIsEmptyStringWhenUserIdNull() {
        final ai.philterd.workspace.model.Span span = new ai.philterd.workspace.model.Span();
        span.setId("span1");
        span.setDocumentId("doc1");
        when(spanRepository.findById("span1")).thenReturn(Optional.of(span));
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc("doc1", "x.txt")));
        // System-generated entry: no userId
        final AuditLog e = entryWithUserId("DOCUMENT_STATUS_CHANGED", null, null, Instant.now());
        e.setResourceType("Span");
        e.setResourceId("span1");
        when(auditLogRepository.findByResourceTypeAndResourceIdOrderByTimestampAsc("Span", "span1"))
                .thenReturn(List.of(e));

        final List<Map<String, Object>> result = controller.history("span1", false, admin());

        assertEquals("", result.get(0).get("actor"),
                "null userId must produce empty string, not NPE");
    }

    @Test
    void filenameOnlyRetainsSafeCharsInContentDisposition() throws IOException {
        when(documentRepository.findById("../etc/passwd"))
                .thenReturn(Optional.of(doc("../etc/passwd", "x.txt")));
        when(spanRepository.findByDocumentId("../etc/passwd")).thenReturn(List.of());
        when(auditLogQueryService.findForDocument(eq("../etc/passwd"), any()))
                .thenReturn(List.of());

        final MockHttpServletResponse response = new MockHttpServletResponse();
        controller.documentHistoryCsv("../etc/passwd", admin(), response);

        final String disposition = response.getHeader("Content-Disposition");
        // Path separators / dots in the document id must be sanitized for the filename.
        assertFalse(disposition.contains("../"),
                "filename leaked path separators: " + disposition);
        assertTrue(disposition.contains("audit-log-"));
    }
}
