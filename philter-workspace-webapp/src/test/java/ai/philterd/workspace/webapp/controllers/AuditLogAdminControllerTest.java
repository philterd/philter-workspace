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
import ai.philterd.workspace.service.AuditLogQueryService;
import ai.philterd.workspace.service.AuditLogService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the new {@code /admin/audit/preview} endpoint that backs the Preview button on the
 * audit-log export form. The preview must use the same query as the export (so the user sees
 * an honest sample of what the download will contain), capped at 10 rows.
 */
class AuditLogAdminControllerTest {

    private AuditLogQueryService auditLogQueryService;
    private AuditLogService auditLogService;
    private AuditLogAdminController controller;

    @BeforeEach
    void setUp() {
        auditLogQueryService = mock(AuditLogQueryService.class);
        auditLogService = mock(AuditLogService.class);
        when(auditLogService.hashForAudit(anyString()))
                .thenAnswer(i -> ((String) i.getArgument(0)).isEmpty() ? "" : "hash:" + i.getArgument(0));
        controller = new AuditLogAdminController(auditLogQueryService, new ObjectMapper(), auditLogService);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportDetails() {
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("AUDIT_LOG_EXPORT"), eq("AuditLog"), eq(null), details.capture());
        return details.getValue();
    }

    @Test
    void aCsvExportIsAuditedWithItsFiltersAndCount() throws Exception {
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), any(Integer.class))).thenReturn(List.of(
                entry("DOCUMENT_APPROVAL", Instant.parse("2026-05-05T10:00:00Z")),
                entry("DOCUMENT_REJECT", Instant.parse("2026-05-05T09:00:00Z"))));

        controller.export("2026-05-05T09:00", "2026-05-05T11:00", " reviewer@example.com ", "Document", "doc-1",
                "CSV", new org.springframework.mock.web.MockHttpServletResponse());

        final Map<String, Object> d = exportDetails();
        assertEquals("csv", d.get("format"));
        assertEquals(AuditLogAdminController.parseInstant("2026-05-05T09:00", "startTime").toString(),
                d.get("startTime"));
        assertEquals(AuditLogAdminController.parseInstant("2026-05-05T11:00", "endTime").toString(), d.get("endTime"));
        assertEquals("hash:reviewer@example.com", d.get("userEmailHash"));
        assertEquals("Document", d.get("resourceType"));
        assertEquals("hash:doc-1", d.get("resourceIdHash"));
        assertEquals(2, d.get("entries"));
        assertEquals(false, d.get("truncated"));
        org.junit.jupiter.api.Assertions.assertFalse(d.containsValue("reviewer@example.com"));
        org.junit.jupiter.api.Assertions.assertFalse(d.containsValue("doc-1"));
    }

    @Test
    void aJsonExportWithNoRowsIsStillAudited() throws Exception {
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), any(Integer.class))).thenReturn(List.of());

        controller.export(null, null, null, null, null, "json", new org.springframework.mock.web.MockHttpServletResponse());

        final Map<String, Object> d = exportDetails();
        assertEquals("json", d.get("format"));
        assertEquals("", d.get("startTime"));
        assertEquals("", d.get("endTime"));
        assertEquals("", d.get("userEmailHash"));
        assertEquals("", d.get("resourceType"));
        assertEquals("", d.get("resourceIdHash"));
        assertEquals(0, d.get("entries"));
    }

    @Test
    void anExportThatReachesTheCapIsMarkedTruncated() throws Exception {
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), eq(100_000))).thenReturn(
                java.util.Collections.nCopies(100_000, entry("LOGIN", Instant.parse("2026-05-05T10:00:00Z"))));

        controller.export(null, null, null, null, null, "json", new org.springframework.mock.web.MockHttpServletResponse());

        assertEquals(100_000, exportDetails().get("entries"));
        assertEquals(true, exportDetails().get("truncated"));
    }

    @Test
    void anExportRefusedForItsFormatIsNotAuditedOrQueried() {
        assertThrows(ResponseStatusException.class, () -> controller.export(null, null, null, null, null, "xml",
                new org.springframework.mock.web.MockHttpServletResponse()));
        verify(auditLogQueryService, never()).find(any(), any(), any(), any(), any(), any(Integer.class));
        verify(auditLogService, never()).log(anyString(), anyString(), any(), any());
    }

    @Test
    void thePreviewIsNotAuditedAsAnExport() {
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), eq(10))).thenReturn(List.of());

        controller.preview(null, null, "reviewer@example.com", null, null);

        verify(auditLogService, never()).log(anyString(), anyString(), any(), any());
    }

    private static AuditLog entry(final String action, final Instant ts) {
        final AuditLog e = new AuditLog();
        e.setAction(action);
        e.setTimestamp(ts);
        e.setOutcome("SUCCESS");
        return e;
    }

    @Test
    void entriesOfActionsNoLongerWrittenAreStillExported() throws Exception {
        // Philter records these now (issue #46), but entries written before are kept and exported as before.
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), any(Integer.class))).thenReturn(List.of(
                entry("LOGOUT", Instant.parse("2026-05-05T10:00:00Z")),
                entry("PASSWORD_CHANGE", Instant.parse("2026-05-05T09:00:00Z")),
                entry("DOCUMENT_APPROVAL", Instant.parse("2026-05-05T08:00:00Z"))));
        final org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();

        controller.export(null, null, null, null, null, "csv", response);

        final String csv = response.getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("LOGOUT") && csv.contains("PASSWORD_CHANGE")
                && csv.contains("DOCUMENT_APPROVAL"), csv);
    }

    @Test
    void previewDelegatesToQueryServiceWithLimitOf10() {
        final List<AuditLog> stub = List.of(entry("LOGIN", Instant.parse("2026-05-05T10:00:00Z")));
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), eq(10))).thenReturn(stub);

        final List<AuditLog> result = controller.preview(
                "2026-05-05T09:00", "2026-05-05T11:00",
                "alice@example.com", "Document", "doc1");

        assertSame(stub, result);
    }

    @Test
    void previewPassesAllFiltersThrough() {
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), eq(10))).thenReturn(List.of());

        controller.preview("2026-05-05T09:00", "2026-05-05T11:00",
                "alice@example.com", "Document", "doc1");

        final ArgumentCaptor<Instant> startCap = ArgumentCaptor.forClass(Instant.class);
        final ArgumentCaptor<Instant> endCap = ArgumentCaptor.forClass(Instant.class);
        verify(auditLogQueryService).find(startCap.capture(), endCap.capture(),
                eq("alice@example.com"), eq("Document"), eq("doc1"), eq(10));
        // Times are interpreted in the server's local zone — both ends are populated.
        assertEquals(true, startCap.getValue().isBefore(endCap.getValue()));
    }

    @Test
    void previewWithNoFiltersPassesNullsToQueryService() {
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), eq(10))).thenReturn(List.of());

        controller.preview(null, null, null, null, null);

        verify(auditLogQueryService).find(eq(null), eq(null), eq(null), eq(null), eq(null), eq(10));
    }

    @Test
    void previewBlankTimeStringsAreTreatedAsNotPresent() {
        when(auditLogQueryService.find(any(), any(), any(), any(), any(), eq(10))).thenReturn(List.of());

        controller.preview("  ", "", "  ", "", "");

        final ArgumentCaptor<Instant> startCap = ArgumentCaptor.forClass(Instant.class);
        final ArgumentCaptor<Instant> endCap = ArgumentCaptor.forClass(Instant.class);
        verify(auditLogQueryService).find(startCap.capture(), endCap.capture(),
                any(), any(), any(), eq(10));
        assertNull(startCap.getValue());
        assertNull(endCap.getValue());
    }

    @Test
    void previewRejectsInvertedTimeRange() {
        // start strictly after end → 400 BAD_REQUEST so the user sees a clear error, not an empty preview.
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.preview("2026-05-05T11:00", "2026-05-05T09:00", null, null, null));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void previewRejectsUnparseableTimestamp() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.preview("not a date", "2026-05-05T11:00", null, null, null));
        assertEquals(400, ex.getStatusCode().value());
    }
}
