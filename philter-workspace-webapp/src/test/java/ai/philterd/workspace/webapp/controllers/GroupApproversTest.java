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

import ai.philterd.workspace.model.ApprovalRule;
import ai.philterd.workspace.model.ApprovalRuleSet;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Group;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.GroupRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Approvers designated per group, and approval rule sets that reserve the second approval for one (#5). */
class GroupApproversTest {

    private final GroupRepository groups = mock(GroupRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminGroupController controller = new AdminGroupController(groups, users, audit);

    @Test
    void approversAreSavedAndMustBeMembers() {
        when(users.existsById(anyString())).thenReturn(true);
        when(groups.findByName("Clerks")).thenReturn(Optional.empty());

        controller.create("Clerks", List.of("u1", "u2"), List.of("u1"), List.of("u2", "u3"),
                new RedirectAttributesModelMap());

        final ArgumentCaptor<Group> saved = ArgumentCaptor.forClass(Group.class);
        verify(groups).save(saved.capture());
        assertEquals(Set.of("u2"), saved.getValue().getApproverUserIds(), "u3 is not a member");
        assertEquals(Set.of("u1"), saved.getValue().getLeaderUserIds());
        verify(audit).log(eq("GROUP_CREATE"), eq("Group"), any(), eq(Map.of("name", "Clerks", "memberCount", 2,
                "leaderCount", 1, "approverCount", 1)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void editingAGroupChangesItsApproversAndAuditsTheCounts() {
        when(users.existsById(anyString())).thenReturn(true);
        final Group g = new Group();
        g.setId("g1");
        g.setName("Clerks");
        g.setUserIds(Set.of("u1", "u2"));
        g.setApproverUserIds(Set.of("u1"));
        when(groups.findById("g1")).thenReturn(Optional.of(g));
        when(groups.findByName("Clerks")).thenReturn(Optional.of(g));

        controller.edit("g1", "Clerks", List.of("u1", "u2"), null, List.of("u2"), new RedirectAttributesModelMap());

        assertEquals(Set.of("u2"), g.getApproverUserIds());
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("GROUP_UPDATE"), eq("Group"), eq("g1"), details.capture());
        assertEquals(1, details.getValue().get("previousApproverCount"));
        assertEquals(1, details.getValue().get("approverCount"));

        controller.edit("g1", "Clerks", List.of("u1", "u2"), null, null, new RedirectAttributesModelMap());
        assertTrue(g.getApproverUserIds().isEmpty(), "unticking every approver clears them");
    }

    @Test
    void aRuleSetCanReserveTheSecondApprovalForAnApprover() {
        final BatchRepository batches = mock(BatchRepository.class);
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Filings");
        when(batches.findById("b1")).thenReturn(Optional.of(batch));
        final AdminRulesController rules = new AdminRulesController(batches, audit,
                new ai.philterd.workspace.webapp.ui.BatchLabels(org.mockito.Mockito.mock(ai.philterd.workspace.repository.GroupRepository.class)));
        final UsernamePasswordAuthenticationToken admin =
                new UsernamePasswordAuthenticationToken("admin@example.com", null, List.of());

        rules.create("b1", List.of(ApprovalRule.SENSITIVE_PII), null, null, null, null, null, null, true, admin,
                new RedirectAttributesModelMap());
        final ApprovalRuleSet set = batch.effectiveRuleSets().get(0);
        assertTrue(set.isRequireApprover());

        rules.update("b1", set.getId(), List.of(ApprovalRule.SENSITIVE_PII), null, null, null, null, null, null,
                false, admin, new RedirectAttributesModelMap());
        assertFalse(batch.effectiveRuleSets().get(0).isRequireApprover());
        verify(audit).log(eq("BATCH_RULES_UPDATE"), eq("Batch"), eq("b1"), any());
    }
}
