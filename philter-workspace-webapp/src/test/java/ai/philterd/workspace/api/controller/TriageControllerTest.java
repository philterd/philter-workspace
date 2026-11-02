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
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.service.ApprovalRuleEvaluator;
import ai.philterd.workspace.service.BatchAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TriageControllerTest {

    private DocumentRepository documentRepository;
    private BatchRepository batchRepository;
    private SpanRepository spanRepository;
    private TestDoubles.FakeUserGroups userGroupsService;
    private BatchAccessService batchAccessService;
    private TriageController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        batchRepository = mock(BatchRepository.class);
        spanRepository = mock(SpanRepository.class);
        when(spanRepository.findByDocumentId(anyString())).thenReturn(List.of());
        userGroupsService = TestDoubles.userGroups();
        batchAccessService = mock(BatchAccessService.class);
        when(batchAccessService.allowedBatchIds(any())).thenReturn(Set.of());
        controller = new TriageController(documentRepository, batchRepository, spanRepository,
                userGroupsService, new ApprovalRuleEvaluator(), batchAccessService,
                new ai.philterd.workspace.webapp.ui.BatchLabels(org.mockito.Mockito.mock(ai.philterd.workspace.repository.GroupRepository.class)),
                mock(ai.philterd.workspace.service.BlindDoubleReviewService.class));
    }

    private static Batch batch(final String id, final String groupId, final String name, final double documentThreshold) {
        final Batch b = new Batch();
        b.setId(id);
        b.setGroupId(groupId);
        b.setName(name);
        b.setDocumentThreshold(documentThreshold);
        return b;
    }

    private static Document doc(final String id, final String batchId, final String status, final double risk) {
        final Document d = new Document();
        d.setId(id);
        d.setBatchId(batchId);
        d.setStatus(status);
        d.setRiskScore(risk);
        d.setFilename(id + ".txt");
        return d;
    }

    @Test
    void unauthenticatedRequestWithRestrictReturnsEmpty() {
        // FakeUserGroups returns empty by default → restrict, allowed empty.
        final Page<Map<String, Object>> page = controller.getQueue(0, 10, null, null, null, false, "riskScore", "desc", null);

        assertTrue(page.getContent().isEmpty());
        verify(documentRepository, never()).findAll((PageRequest) any());
    }

    @Test
    void adminWithMyGroupsOnlyFalseSeesEverything() {
        final Document d = doc("d1", "b1", "REVIEW_REQUIRED", 0.5);
        when(documentRepository.findByStatusNotIn(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(d), PageRequest.of(0, 10), 1));
        when(batchRepository.findAllById(any()))
                .thenReturn(List.of(batch("b1", "g1", "Batch One", 0.25)));

        final Page<Map<String, Object>> page = controller.getQueue(0, 10, null, null, null, false, "riskScore", "desc", TestAuth.admin("admin@example.com"));

        assertEquals(1, page.getContent().size());
        assertEquals("Batch One", page.getContent().get(0).get("batchName"));
    }

    @Test
    void invalidSortFallsBackToRiskScore() {
        when(documentRepository.findByStatusNotIn(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        controller.getQueue(0, 10, null, null, null, false, "evil-injection", "asc",
                TestAuth.admin("admin@example.com"));

        final ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(documentRepository).findByStatusNotIn(any(), captor.capture());
        final Sort sort = captor.getValue().getSort();
        final Sort.Order order = sort.getOrderFor("riskScore");
        assertEquals(Sort.Direction.ASC, order.getDirection());
    }

    @Test
    void ascDirectionRespected() {
        when(documentRepository.findByStatusNotIn(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        controller.getQueue(0, 10, null, null, null, false, "filename", "asc",
                TestAuth.admin("admin@example.com"));

        final ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(documentRepository).findByStatusNotIn(any(), captor.capture());
        final Sort.Order order = captor.getValue().getSort().getOrderFor("filename");
        assertEquals(Sort.Direction.ASC, order.getDirection());
    }

    @Test
    void queueClampsCallerSuppliedSizeToMaxPageSize() {
        // ?size=99999 must be clamped — without the cap, an authenticated caller can force
        // Mongo to load a huge page into memory.
        when(documentRepository.findByStatusNotIn(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        controller.getQueue(0, 99_999, null, null, null, false, "riskScore", "desc",
                TestAuth.admin("admin@example.com"));

        final ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(documentRepository).findByStatusNotIn(any(), captor.capture());
        assertEquals(TriageController.MAX_QUEUE_PAGE_SIZE, captor.getValue().getPageSize());
    }

    @Test
    void queueClampsNonPositiveSizeToOne() {
        // ?size=0 (or negative) would otherwise trip PageRequest.of's IAE → 500.
        when(documentRepository.findByStatusNotIn(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

        controller.getQueue(0, 0, null, null, null, false, "riskScore", "desc",
                TestAuth.admin("admin@example.com"));

        final ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(documentRepository).findByStatusNotIn(any(), captor.capture());
        assertEquals(1, captor.getValue().getPageSize());
    }

    @Test
    void queueAcceptsSizeInRange() {
        // Sanity: a normal size passes through unchanged so the clamp doesn't over-fire.
        when(documentRepository.findByStatusNotIn(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        controller.getQueue(0, 50, null, null, null, false, "riskScore", "desc",
                TestAuth.admin("admin@example.com"));

        final ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(documentRepository).findByStatusNotIn(any(), captor.capture());
        assertEquals(50, captor.getValue().getPageSize());
    }

    @Test
    void nonAdminFiltersToAllowedBatches() {
        when(batchAccessService.allowedBatchIds(any())).thenReturn(Set.of("b1"));
        when(documentRepository.findByBatchIdInAndStatusNotIn(eq(Set.of("b1")), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(doc("d1", "b1", "REVIEW_REQUIRED", 0.1)),
                        PageRequest.of(0, 10), 1));
        when(batchRepository.findAllById(any()))
                .thenReturn(List.of(batch("b1", "g1", "Yours", 0.25)));

        final Page<Map<String, Object>> page = controller.getQueue(0, 10, null, null, null, false, "riskScore", "desc", TestAuth.user("alice@example.com"));

        assertEquals(1, page.getContent().size());
        verify(documentRepository).findByBatchIdInAndStatusNotIn(eq(Set.of("b1")), any(), any(PageRequest.class));
    }

    @Test
    void nonAdminAskingForBatchOutsideGroupsGetsEmpty() {
        when(batchAccessService.allowedBatchIds(any())).thenReturn(Set.of("b1"));

        final Page<Map<String, Object>> page = controller.getQueue(0, 10, "b2", null, null, false, "riskScore", "desc", TestAuth.user("alice@example.com"));

        assertTrue(page.getContent().isEmpty());
        verify(documentRepository, never()).findByBatchId(anyString(), any(PageRequest.class));
    }

    @Test
    void theDocumentThresholdMarksLowRiskWithoutChangingTheStatus() {
        final Document pendingSpans = doc("d1", "b1", "REVIEW_REQUIRED", 0.10);
        final Document atThreshold = doc("d2", "b1", "AUDIT_REQUIRED", 0.25);
        final Document above = doc("d3", "b1", "REVIEW_REQUIRED", 0.50);
        final Document failed = doc("d4", "b1", "FAILED", 0.0); // never scored
        final Document queued = doc("d5", "b1", "PENDING", 0.0); // not redacted yet
        when(documentRepository.findByStatusNotIn(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(pendingSpans, atThreshold, above, failed, queued), PageRequest.of(0, 10), 5));
        when(batchRepository.findAllById(any()))
                .thenReturn(List.of(batch("b1", "g1", "B", 0.25)));

        final Page<Map<String, Object>> page = controller.getQueue(0, 10, null, null, null, false, "riskScore", "desc", TestAuth.admin("admin@example.com"));

        // A document with undecided spans keeps its real status, however low its risk score.
        assertEquals("REVIEW_REQUIRED", page.getContent().get(0).get("status"));
        assertEquals(true, page.getContent().get(0).get("lowRisk"));
        assertEquals("AUDIT_REQUIRED", page.getContent().get(1).get("status"));
        assertEquals(true, page.getContent().get(1).get("lowRisk"));
        assertEquals(false, page.getContent().get(2).get("lowRisk"));
        assertEquals(false, page.getContent().get(3).get("lowRisk"));
        assertEquals(false, page.getContent().get(4).get("lowRisk"));
        assertTrue(page.getContent().stream().noneMatch(row -> row.containsKey("autoApproved")));
    }

    @Test
    void sourceAvailableReflectsOriginalText() {
        final Document withSource = doc("d1", "b1", "FINALIZED", 0.10);
        withSource.setOriginalText("hello world");
        final Document sourceCleared = doc("d2", "b1", "FINALIZED", 0.10);
        sourceCleared.setOriginalText(null);
        final Document emptySource = doc("d3", "b1", "FINALIZED", 0.10);
        emptySource.setOriginalText("");
        when(documentRepository.findByStatusNotIn(any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(withSource, sourceCleared, emptySource), PageRequest.of(0, 10), 3));
        when(batchRepository.findAllById(any()))
                .thenReturn(List.of(batch("b1", "g1", "B", 0.25)));

        final Page<Map<String, Object>> page = controller.getQueue(0, 10, null, null, null, false, "riskScore", "desc",
                TestAuth.admin("admin@example.com"));

        assertEquals(true, page.getContent().get(0).get("sourceAvailable"));
        assertEquals(false, page.getContent().get(1).get("sourceAvailable"));
        assertEquals(false, page.getContent().get(2).get("sourceAvailable"));
    }

    @Test
    void getBatchesAdminUnscoped() {
        when(batchRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(batch("b2", "g1", "Zebra", 0.25), batch("b1", "g1", "Alpha", 0.25))));

        final List<Map<String, String>> result = controller.getBatches(false, TestAuth.admin("admin@example.com"));

        // Sorted alphabetically by name.
        assertEquals(2, result.size());
        assertEquals("Alpha", result.get(0).get("name"));
        assertEquals("Zebra", result.get(1).get("name"));
    }

    @Test
    void getBatchesLabelsANameTwoGroupsShareWithTheGroup() {
        // Names are unique only within a group (#83).
        final ai.philterd.workspace.repository.GroupRepository groups =
                mock(ai.philterd.workspace.repository.GroupRepository.class);
        for (String[] g : new String[][] {{"g1", "Claims"}, {"g2", "Litigation"}}) {
            final ai.philterd.workspace.model.Group group = new ai.philterd.workspace.model.Group();
            group.setId(g[0]);
            group.setName(g[1]);
            when(groups.findById(g[0])).thenReturn(java.util.Optional.of(group));
        }
        controller = new TriageController(documentRepository, batchRepository, spanRepository,
                userGroupsService, new ApprovalRuleEvaluator(), batchAccessService,
                new ai.philterd.workspace.webapp.ui.BatchLabels(groups),
                mock(ai.philterd.workspace.service.BlindDoubleReviewService.class));
        when(batchRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(
                batch("b1", "g1", "Sample", 0.25), batch("b2", "g2", "Sample", 0.25), batch("b3", "g1", "Other", 0.25))));

        final Map<String, String> labels = new java.util.HashMap<>();
        for (Map<String, String> b : controller.getBatches(false, TestAuth.admin("admin@example.com"))) {
            assertEquals(b.get("id").equals("b3") ? "Other" : "Sample", b.get("name"), "name is unchanged");
            labels.put(b.get("id"), b.get("label"));
        }
        assertEquals(Map.of("b1", "Sample (Claims)", "b2", "Sample (Litigation)", "b3", "Other"), labels);
    }

    @Test
    void getBatchesNonAdminFilteredToGroups() {
        // Non-admins now query by group directly — the repository only returns rows whose
        // groupId is in the caller's group set, so the controller doesn't need to filter
        // again.
        userGroupsService.withMembership("alice@example.com", Set.of("g1"));
        when(batchRepository.findByGroupIdIn(Set.of("g1")))
                .thenReturn(List.of(batch("b1", "g1", "Mine", 0.25)));

        final List<Map<String, String>> result = controller.getBatches(false, TestAuth.user("alice@example.com"));

        assertEquals(1, result.size());
        assertEquals("Mine", result.get(0).get("name"));
        assertFalse(result.stream().anyMatch(m -> "NotMine".equals(m.get("name"))));
    }

    @Test
    void getBatchesNonAdminWithNoGroupsReturnsEmpty() {
        // Defensive: a non-admin with no group memberships short-circuits without hitting
        // the repository, so they can't be served any rows.
        userGroupsService.withMembership("alice@example.com", Set.of());

        final List<Map<String, String>> result = controller.getBatches(false, TestAuth.user("alice@example.com"));

        assertTrue(result.isEmpty());
        verify(batchRepository, never()).findByGroupIdIn(any());
    }
}
