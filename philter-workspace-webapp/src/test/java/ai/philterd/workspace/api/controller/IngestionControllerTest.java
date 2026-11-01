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

import ai.philterd.workspace.dto.IngestRequest;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.service.GeneralSettingsService;
import ai.philterd.workspace.util.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionControllerTest {

    private DocumentRepository documentRepository;
    private BatchRepository batchRepository;
    private TestDoubles.FakeUserGroups userGroupsService;
    private TestDoubles.RecordingAuditLog auditLogService;
    private GeneralSettingsService generalSettingsService;
    private IngestionController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        batchRepository = mock(BatchRepository.class);
        userGroupsService = TestDoubles.userGroups();
        auditLogService = TestDoubles.auditLog();
        generalSettingsService = mock(GeneralSettingsService.class);
        final GeneralSettings settings = new GeneralSettings();
        settings.setMaxUploadFileSizeBytes(10L * 1024L * 1024L);
        when(generalSettingsService.load()).thenReturn(settings);
        controller = new IngestionController(documentRepository,
                batchRepository, TestDoubles.batchAccess(batchRepository, userGroupsService),
                auditLogService, generalSettingsService);
    }

    private static Batch openBatch(final String id, final String groupId, final String name) {
        final Batch b = new Batch();
        b.setId(id);
        b.setGroupId(groupId);
        b.setName(name);
        return b;
    }

    private static Batch closedBatch(final String id, final String groupId, final String name) {
        final Batch b = openBatch(id, groupId, name);
        b.setClosed(true);
        return b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(final ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    void unknownBatchReturns404WithGenericBody() {
        when(batchRepository.findById("missing")).thenReturn(Optional.empty());
        final IngestRequest req = new IngestRequest("doc.txt", "missing", "hello", null);

        final ResponseEntity<?> response = controller.ingest(req, TestAuth.user("alice@example.com"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        // Body must NOT echo the supplied id — that would leak existence on every probe.
        assertEquals("Batch not found.", body(response).get("error"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void nonAdminWithoutGroupAccessReturns404WithGenericBody() {
        final Batch batch = openBatch("b1", "g1", "Batch 1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g2"));

        final ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hello", null),
                TestAuth.user("alice@example.com"));

        // Same status + body as a genuine miss — caller can't distinguish "exists but
        // I can't see it" from "no such batch".
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Batch not found.", body(response).get("error"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void missAndAccessDeniedHaveByteIdenticalResponse() {
        // The whole point of the harmonization: two different probes produce the same
        // status code, body shape, and message — so an attacker can't enumerate batch
        // ids by reading the response.
        when(batchRepository.findById("ghost")).thenReturn(Optional.empty());
        final Batch batch = openBatch("b1", "g1", "Batch 1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g2"));

        final ResponseEntity<?> miss = controller.ingest(
                new IngestRequest("doc.txt", "ghost", "hi", null),
                TestAuth.user("alice@example.com"));
        final ResponseEntity<?> denied = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hi", null),
                TestAuth.user("alice@example.com"));

        assertEquals(miss.getStatusCode(), denied.getStatusCode());
        assertEquals(miss.getBody(), denied.getBody());
    }

    @Test
    void closedBatchReturns409WithDetails() {
        final Batch batch = closedBatch("b1", "g1", "Closed Batch");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));

        final ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hello", null),
                TestAuth.user("alice@example.com"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        final Map<String, Object> body = body(response);
        assertEquals("b1", body.get("batchId"));
        assertEquals(true, body.get("closed"));
        assertTrue(((String) body.get("error")).contains("Closed Batch"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void happyPathSavesDocumentAndReturns202() {
        final Batch batch = openBatch("b1", "g1", "Open");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));

        final ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hello world", null),
                TestAuth.user("alice@example.com"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        final Map<String, Object> body = body(response);
        final String taskId = (String) body.get("taskId");
        assertNotNull(taskId);

        final ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(docCaptor.capture());
        final Document saved = docCaptor.getValue();
        assertEquals(taskId, saved.getId());
        assertEquals("b1", saved.getBatchId());
        assertEquals("doc.txt", saved.getFilename());
        assertEquals("hello world", saved.getOriginalText());
        assertEquals("PENDING", saved.getStatus());

        assertTrue(auditLogService.hasAction("DOCUMENT_INGEST"));
        // Content SHA-512 is recorded for chain-of-custody.
        assertEquals(Hashing.sha512Hex("hello world"), saved.getContentSha512());
    }

    @Test
    void emptyTextStillGetsContentHash() {
        final Batch batch = openBatch("b1", "g1", "Open");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));

        controller.ingest(new IngestRequest("doc.txt", "b1", null, null),
                TestAuth.user("alice@example.com"));

        final ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(docCaptor.capture());
        // Null/missing text is hashed as empty string so every persisted document has a hash.
        assertEquals(Hashing.sha512Hex(""), docCaptor.getValue().getContentSha512());
    }

    @Test
    void adminCanIngestEvenWithoutGroupMembership() {
        final Batch batch = openBatch("b1", "g1", "Open");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        // admin not in any group

        final ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hi", null),
                TestAuth.admin("admin@example.com"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(documentRepository).save(any());
    }

    @Test
    void unauthenticatedRequestGetsTheSame404() {
        // Anonymous Bearer-less request: indistinguishable from a missing-batch lookup,
        // so an attacker can't differentiate "no auth" from "auth doesn't grant access".
        final Batch batch = openBatch("b1", "g1", "Open");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        final ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hi", null),
                (Authentication) null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Batch not found.", body(response).get("error"));
        verify(documentRepository, never()).save(any());
    }

    // ---------- case metadata (#16) ----------

    @Test
    void caseMetadataIsStoredWithTheDocumentAndItsCaseTypeAudited() {
        final Batch batch = openBatch("b1", "g1", "Batch 1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        final ai.philterd.workspace.model.CaseMetadata given = new ai.philterd.workspace.model.CaseMetadata(
                " 1:26-cv-01234 ", "Civil", "Acme Dental LLC", "Paid", false);

        final ResponseEntity<?> response = controller.ingest(
                new IngestRequest("doc.txt", "b1", "hello", null, given), TestAuth.admin("admin@example.com"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        final org.mockito.ArgumentCaptor<ai.philterd.workspace.model.Document> saved =
                org.mockito.ArgumentCaptor.forClass(ai.philterd.workspace.model.Document.class);
        verify(documentRepository).save(saved.capture());
        final ai.philterd.workspace.model.CaseMetadata stored = saved.getValue().getCaseMetadata();
        assertEquals("1:26-cv-01234", stored.getCaseNumber());
        assertEquals("civil", stored.getCaseType());
        assertEquals("paid", stored.getFeeStatus());
    }

    @Test
    void invalidCaseMetadataIsRefusedBeforeAnythingIsStored() {
        final Batch batch = openBatch("b1", "g1", "Batch 1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        final ResponseEntity<?> response = controller.ingest(new IngestRequest("doc.txt", "b1", "hello", null,
                new ai.philterd.workspace.model.CaseMetadata(null, "civil", null, "sometimes", null)),
                TestAuth.admin("admin@example.com"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("The fee status must be paid, unpaid, or waived.", body(response).get("error"));
        verify(documentRepository, never()).save(any());
    }
}
