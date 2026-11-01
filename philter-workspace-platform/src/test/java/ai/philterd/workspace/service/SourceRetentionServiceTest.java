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

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.FinalizationPolicy;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.FinalizationPolicyRepository;
import ai.philterd.workspace.repository.PendingUploadRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The delete-after-X-days and delete-after-48-hours finalization policies (#68). */
class SourceRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final SpanRepository spans = mock(SpanRepository.class);
    private final PendingUploadRepository pendingUploads = mock(PendingUploadRepository.class);
    private final BatchRepository batches = mock(BatchRepository.class);
    private final FinalizationPolicyRepository policies = mock(FinalizationPolicyRepository.class);
    private final DocumentSearchService search = mock(DocumentSearchService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private SourceRetentionService service;

    @BeforeEach
    void setUp() {
        service = new SourceRetentionService(documents, spans, pendingUploads, batches, policies, search, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(spans.deleteByDocumentId(anyString())).thenReturn(3L);
        when(search.removeDocument(anyString())).thenReturn(true);
    }

    private static FinalizationPolicy policy(final String id, final String option, final long days) {
        final FinalizationPolicy p = new FinalizationPolicy();
        p.setId(id);
        p.setName(id);
        p.setOption(option);
        p.setDeleteAfterDays(days);
        return p;
    }

    private static Batch batch(final String id) {
        final Batch b = new Batch();
        b.setId(id);
        return b;
    }

    private static Document finalized(final String id, final Instant at) {
        final Document d = new Document();
        d.setId(id);
        d.setStatus("FINALIZED");
        d.setOriginalText("Jane Roe, SSN 123-45-6789");
        d.setRedactedText("<<PERSON>>, SSN <<SSN>>");
        d.setStoragePath("/data/" + id);
        d.setFinalizedAt(at);
        return d;
    }

    private void batchUnder(final FinalizationPolicy policy, final String batchId, final Document... docs) {
        when(batches.findByFinalizationPolicyId(policy.getId())).thenReturn(List.of(batch(batchId)));
        when(documents.findByBatchIdAndStatusAndSourceClearedAtIsNull(batchId, "FINALIZED")).thenReturn(List.of(docs));
    }

    @Test
    void aDocumentPast48HoursIsClearedAndOneNotYetDueIsKept() {
        final FinalizationPolicy p48 = policy("p48", FinalizationPolicy.OPTION_DELETE_AFTER_48H, 0);
        when(policies.findAll()).thenReturn(List.of(p48));
        final Document due = finalized("due", NOW.minus(Duration.ofHours(49)));
        final Document notYet = finalized("not-yet", NOW.minus(Duration.ofHours(47)));
        batchUnder(p48, "b1", due, notYet);

        assertEquals(1, service.clearDue());

        assertNull(due.getOriginalText());
        assertNull(due.getStoragePath());
        assertEquals(NOW, due.getSourceClearedAt());
        assertEquals("<<PERSON>>, SSN <<SSN>>", due.getRedactedText(), "Download keeps working");
        verify(documents).save(due);
        verify(spans).deleteByDocumentId("due");
        verify(pendingUploads).deleteById("due");
        verify(search).removeDocument("due");
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("FINALIZATION_POLICY_APPLIED"), eq("Document"), eq("due"), details.capture());
        assertEquals("SOURCE_CLEARED", details.getValue().get("action"));
        assertEquals(FinalizationPolicy.OPTION_DELETE_AFTER_48H, details.getValue().get("option"));
        assertEquals(3L, details.getValue().get("spansDeleted"));
        assertEquals(true, details.getValue().get("indexCleared"));

        assertEquals("Jane Roe, SSN 123-45-6789", notYet.getOriginalText());
        verify(spans, never()).deleteByDocumentId("not-yet");
    }

    @Test
    void deleteAfterXDaysCountsItsDays() {
        final FinalizationPolicy days = policy("p3", FinalizationPolicy.OPTION_DELETE_AFTER_X_DAYS, 3);
        when(policies.findAll()).thenReturn(List.of(days));
        final Document due = finalized("due", NOW.minus(Duration.ofDays(3)).minusSeconds(1));
        final Document notYet = finalized("not-yet", NOW.minus(Duration.ofDays(2)));
        batchUnder(days, "b1", due, notYet);

        assertEquals(1, service.clearDue());
        assertNull(due.getOriginalText());
        assertNotNull(notYet.getOriginalText());
    }

    @Test
    void aBatchMovedToLegalHoldIsNotCleared() {
        // The batch's policy is read when the job runs: its documents were finalized under a delete policy, but the
        // batch is now under Legal hold, so the job never looks at them.
        final FinalizationPolicy hold = policy("hold", FinalizationPolicy.OPTION_LEGAL_HOLD, 0);
        final FinalizationPolicy p48 = policy("p48", FinalizationPolicy.OPTION_DELETE_AFTER_48H, 0);
        when(policies.findAll()).thenReturn(List.of(hold, p48));
        when(batches.findByFinalizationPolicyId("p48")).thenReturn(List.of());
        final Document old = finalized("old", NOW.minus(Duration.ofDays(30)));
        batchUnder(hold, "b-held", old);

        assertEquals(0, service.clearDue());

        assertEquals("Jane Roe, SSN 123-45-6789", old.getOriginalText());
        verify(documents, never()).findByBatchIdAndStatusAndSourceClearedAtIsNull(eq("b-held"), anyString());
        verify(spans, never()).deleteByDocumentId(anyString());
    }

    @Test
    void aDocumentFinalizedBeforeFinalizedAtWasRecordedCountsFromItsLastStatusChange() {
        final FinalizationPolicy p48 = policy("p48", FinalizationPolicy.OPTION_DELETE_AFTER_48H, 0);
        when(policies.findAll()).thenReturn(List.of(p48));
        final Document legacy = finalized("legacy", null);
        legacy.setStatusChangedAt(NOW.minus(Duration.ofDays(3)).atZone(ZoneId.systemDefault()).toLocalDateTime());
        batchUnder(p48, "b1", legacy);

        assertEquals(1, service.clearDue());
        assertNull(legacy.getOriginalText());
    }

    @Test
    void anotherRunClearingTheSameDocumentFirstIsSkipped() {
        final FinalizationPolicy p48 = policy("p48", FinalizationPolicy.OPTION_DELETE_AFTER_48H, 0);
        when(policies.findAll()).thenReturn(List.of(p48));
        final Document raced = finalized("raced", NOW.minus(Duration.ofDays(3)));
        batchUnder(p48, "b1", raced);
        when(documents.save(any(Document.class))).thenThrow(new OptimisticLockingFailureException("changed"));

        assertEquals(0, service.clearDue());

        // Deleting the spans again is harmless; only the run whose save wins audits the deletion.
        verify(audit, never()).log(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aRunThatFailsPartwayLeavesTheDocumentToBeTriedAgain() {
        final FinalizationPolicy p48 = policy("p48", FinalizationPolicy.OPTION_DELETE_AFTER_48H, 0);
        when(policies.findAll()).thenReturn(List.of(p48));
        final Document doc = finalized("doc", NOW.minus(Duration.ofDays(3)));
        batchUnder(p48, "b1", doc);
        when(search.removeDocument("doc")).thenThrow(new IllegalStateException("index unavailable"));

        assertEquals(0, service.clearDue());

        assertNull(doc.getSourceClearedAt(), "not marked cleared, so the next run tries again");
        assertEquals("Jane Roe, SSN 123-45-6789", doc.getOriginalText());
        verify(documents, never()).save(any(Document.class));
    }

    @Test
    void aDocumentClearedBeforeSourceClearedAtWasRecordedIsMarkedNotAuditedAgain() {
        final FinalizationPolicy p48 = policy("p48", FinalizationPolicy.OPTION_DELETE_AFTER_48H, 0);
        when(policies.findAll()).thenReturn(List.of(p48));
        final Document already = finalized("already", NOW.minus(Duration.ofDays(3)));
        already.setOriginalText(null);
        already.setStoragePath(null);
        batchUnder(p48, "b1", already);

        assertEquals(0, service.clearDue());

        assertNotNull(already.getSourceClearedAt());
        verify(documents).save(already);
        verify(audit, never()).log(anyString(), anyString(), anyString(), any());
    }

    @Test
    void retentionByOption() {
        assertEquals(Duration.ZERO, SourceRetentionService.retention(
                policy("i", FinalizationPolicy.OPTION_DELETE_IMMEDIATELY, 0)));
        assertEquals(Duration.ofHours(48), SourceRetentionService.retention(
                policy("h", FinalizationPolicy.OPTION_DELETE_AFTER_48H, 0)));
        assertEquals(Duration.ofDays(7), SourceRetentionService.retention(
                policy("d", FinalizationPolicy.OPTION_DELETE_AFTER_X_DAYS, 7)));
        assertNull(SourceRetentionService.retention(policy("d0", FinalizationPolicy.OPTION_DELETE_AFTER_X_DAYS, 0)),
                "a policy without a number of days never clears");
        assertNull(SourceRetentionService.retention(policy("l", FinalizationPolicy.OPTION_LEGAL_HOLD, 0)));
        assertNull(SourceRetentionService.retention(
                        policy("huge", FinalizationPolicy.OPTION_DELETE_AFTER_X_DAYS, Long.MAX_VALUE)),
                "more days than a duration holds never clears, and does not stop the job");
    }
}
