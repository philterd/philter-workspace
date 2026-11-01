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

import ai.philterd.philter.PhilterClient;
import ai.philterd.philter.model.RedactList;
import ai.philterd.philter.model.RedactListTerms;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.ErrorReasons;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.DetectionRule;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DetectionRuleRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rules made from reviewers' decisions and kept on Philter's never-redact and always-redact lists (#39). */
class DetectionRuleServiceTest {

    private static final Authentication ADMIN = auth("admin@example.com", "ROLE_ADMIN");
    private static final Authentication REVIEWER = auth("reviewer@example.com", "ROLE_USER");
    private static final Authentication OUTSIDER = auth("outsider@example.com", "ROLE_USER");
    private static final Authentication AUDITOR = auth("auditor@example.com", "ROLE_AUDITOR");

    private final Map<String, DetectionRule> stored = new LinkedHashMap<>();
    private final DetectionRuleRepository rules = mock(DetectionRuleRepository.class);
    private final SpanRepository spans = mock(SpanRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final BatchRepository batches = mock(BatchRepository.class);
    private final PhilterSettings philterSettings = mock(PhilterSettings.class);
    private final PhilterClient philter = mock(PhilterClient.class);
    private final GeneralSettingsService settingsService = mock(GeneralSettingsService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final GeneralSettings settings = new GeneralSettings();

    /** Philter's two lists, with their revisions. */
    private final Map<RedactList, List<String>> lists = new EnumMap<>(RedactList.class);
    private final Map<RedactList, Long> revisions = new EnumMap<>(RedactList.class);
    private int conflictsToThrow;

    private DetectionRuleService service;
    private Document document;

    private static Authentication auth(final String name, final String role) {
        return new UsernamePasswordAuthenticationToken(name, null, List.of(new SimpleGrantedAuthority(role)));
    }

    @BeforeEach
    void setUp() throws Exception {
        when(audit.hashForAudit(anyString())).thenAnswer(i -> "h:" + i.getArgument(0));
        when(settingsService.load()).thenReturn(settings);
        when(philterSettings.client()).thenReturn(philter);

        when(rules.save(any())).thenAnswer(i -> {
            final DetectionRule r = i.getArgument(0);
            stored.put(r.getId(), r);
            return r;
        });
        when(rules.findByTextKey(anyString())).thenAnswer(i -> stored.values().stream()
                .filter(r -> r.getTextKey().equals(i.getArgument(0))).toList());
        when(rules.findAll()).thenAnswer(i -> new ArrayList<>(stored.values()));
        doAnswer(i -> stored.remove((String) i.getArgument(0))).when(rules).deleteById(anyString());

        for (RedactList list : RedactList.values()) {
            lists.put(list, new ArrayList<>());
            revisions.put(list, 0L);
        }
        when(philter.getRedactList(any(RedactList.class))).thenAnswer(i -> {
            final RedactList list = i.getArgument(0);
            final RedactListTerms terms = new RedactListTerms();
            terms.setTerms(new ArrayList<>(lists.get(list)));
            terms.setRevision(revisions.get(list));
            return terms;
        });
        when(philter.replaceRedactList(any(RedactList.class), any(), any())).thenAnswer(i -> {
            final RedactList list = i.getArgument(0);
            if (conflictsToThrow > 0 || !revisions.get(list).equals(i.getArgument(2))) {
                conflictsToThrow--;
                revisions.put(list, revisions.get(list) + 1);
                throw new ClientException("Conflict", 409, null, ErrorReasons.REDACT_LIST_CHANGED);
            }
            lists.put(list, new ArrayList<>(i.<List<String>>getArgument(1)));
            revisions.put(list, revisions.get(list) + 1);
            return null;
        });

        document = new Document();
        document.setId("d1");
        document.setBatchId("b1");
        when(documents.findById("d1")).thenReturn(Optional.of(document));
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setGroupId("g1");
        when(batches.findById("b1")).thenReturn(Optional.of(batch));

        final UserGroupsService groups = new UserGroupsService(mock(ai.philterd.workspace.repository.UserRepository.class),
                mock(ai.philterd.workspace.repository.GroupRepository.class)) {
            @Override
            public Set<String> groupIdsForEmail(final String email) {
                return "reviewer@example.com".equals(email) ? Set.of("g1") : Set.of();
            }
        };
        final DocumentAccessService access = new DocumentAccessService(batches, documents,
                new BatchAccessService(batches, groups));
        service = new DetectionRuleService(rules, spans, documents, access, philterSettings, settingsService, audit);
    }

    private static Span span(final String id, final String text, final String status, final boolean manual) {
        final Span s = new Span();
        s.setId(id);
        s.setDocumentId("d1");
        s.setType("person");
        s.setText(text);
        s.setStatus(status);
        s.setManuallyCreated(manual);
        return s;
    }

    @Test
    void anAdministratorsRuleGoesStraightIntoPhilterAndIsAudited() {
        lists.get(RedactList.NEVER).add("Someone Else's Term");

        final DetectionRuleService.Made made = service.makeFromSpan(DetectionRule.KIND_NEVER,
                span("s1", " Acme Dental ", "REJECTED", false), document, ADMIN);

        assertEquals(DetectionRule.STATUS_ACTIVE, made.rule().getStatus());
        assertTrue(made.message().startsWith("Rule confirmed"), made.message());
        assertEquals(List.of("Someone Else's Term", "Acme Dental"), lists.get(RedactList.NEVER),
                "added, trimmed, and nothing else changed");
        assertTrue(lists.get(RedactList.ALWAYS).isEmpty());
        final DetectionRule rule = made.rule();
        assertEquals("Acme Dental", rule.getText());
        assertEquals("h:acme dental", rule.getTextKey());
        assertEquals("admin@example.com", rule.getProposedBy());
        assertEquals("admin@example.com", rule.getDecidedBy());
        assertEquals("d1", rule.getDocumentId());
        assertTrue(rule.isAddedToPhilter());
        verify(audit).log(eq("DETECTION_RULE_CREATE"), eq("DetectionRule"), eq(rule.getId()), any());
        verify(audit).log(eq("DETECTION_RULE_CONFIRM"), eq("DetectionRule"), eq(rule.getId()), any());
    }

    @Test
    void auditDetailsCarryAKeyedHashAndNeverTheValue() {
        service.makeFromSpan(DetectionRule.KIND_ALWAYS, span("s1", "Project Phoenix", "APPROVED", true), document,
                ADMIN);
        final org.mockito.ArgumentCaptor<Map<String, Object>> details = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("DETECTION_RULE_CONFIRM"), any(), any(), details.capture());
        assertFalse(details.getValue().toString().contains("Project Phoenix"), details.getValue().toString());
        assertEquals("h:project phoenix", details.getValue().get("textHash"));
        assertEquals(List.of("Project Phoenix"), lists.get(RedactList.ALWAYS));
    }

    @Test
    void aReviewersRuleWaitsUntilSomeoneConfirmsIt() {
        final DetectionRuleService.Made made = service.makeFromSpan(DetectionRule.KIND_NEVER,
                span("s1", "Acme Dental", "REJECTED", false), document, REVIEWER);

        assertEquals(DetectionRule.STATUS_PROPOSED, made.rule().getStatus());
        assertTrue(made.message().startsWith("Rule proposed"), made.message());
        assertTrue(lists.get(RedactList.NEVER).isEmpty(), "nothing written to Philter");
        assertFalse(service.mayConfirm(REVIEWER, made.rule()));
        assertTrue(service.makeFromSpan(DetectionRule.KIND_NEVER, span("s2", "ACME DENTAL", "REJECTED", false), document,
                REVIEWER).message().contains("already waiting"), "asked again");
        assertEquals(1, stored.size());
        assertThrows(DetectionRuleService.RuleException.class, () -> service.confirm(made.rule(), REVIEWER));

        service.confirm(made.rule(), ADMIN);
        assertEquals(DetectionRule.STATUS_ACTIVE, made.rule().getStatus());
        assertEquals("admin@example.com", made.rule().getDecidedBy());
        assertEquals(List.of("Acme Dental"), lists.get(RedactList.NEVER));
    }

    @Test
    void reviewersMayConfirmOnlyWhenAllowedAndOnlyForDocumentsTheyCanSee() {
        settings.setReviewersConfirmRules(true);
        final DetectionRule rule = service.makeFromSpan(DetectionRule.KIND_NEVER,
                span("s1", "Acme Dental", "REJECTED", false), document, REVIEWER).rule();

        assertEquals(DetectionRule.STATUS_ACTIVE, rule.getStatus(), "the reviewer was allowed to confirm it");
        assertFalse(service.mayConfirm(OUTSIDER, rule), "not in the batch's group");
        assertFalse(service.canSee(OUTSIDER, rule));
        assertTrue(service.visible(OUTSIDER).isEmpty());
        assertFalse(service.mayConfirm(AUDITOR, rule), "auditors only read");
        assertTrue(service.canSee(AUDITOR, rule));
    }

    @Test
    void aRefusedWriteToPhilterIsExplainedAndLeavesNothingBehind() throws Exception {
        when(philter.getRedactList(any(RedactList.class)))
                .thenThrow(new ClientException("Forbidden", 403, null, "insufficient_scope"));

        final DetectionRuleService.RuleException e = assertThrows(DetectionRuleService.RuleException.class,
                () -> service.makeFromSpan(DetectionRule.KIND_NEVER, span("s1", "Acme Dental", "REJECTED", false),
                        document, ADMIN));

        assertEquals(DetectionRuleService.KEY_SCOPES, e.getMessage());
        assertTrue(stored.isEmpty(), "no rule saved");
        verify(audit, never()).log(anyString(), any(), any(), any());
    }

    @Test
    void philtersOtherRefusalsHaveTheirOwnMessages() {
        assertEquals(DetectionRuleService.KEY_REJECTED,
                DetectionRuleService.message(new ClientException("Unauthorized", 401, null)));
        assertEquals(DetectionRuleService.LIST_REFUSED,
                DetectionRuleService.message(new ClientException("Bad Request", 400, null)));
        assertEquals(DetectionRuleService.UNREACHABLE, DetectionRuleService.message(new java.io.IOException("x")));
    }

    @Test
    void aChangedListIsReadAgainAndTheChangeAppliedToWhatIsThere() {
        conflictsToThrow = 1;
        service.makeFromSpan(DetectionRule.KIND_NEVER, span("s1", "Acme Dental", "REJECTED", false), document, ADMIN);
        assertEquals(List.of("Acme Dental"), lists.get(RedactList.NEVER));

        conflictsToThrow = 3;
        final DetectionRuleService.RuleException e = assertThrows(DetectionRuleService.RuleException.class,
                () -> service.makeFromSpan(DetectionRule.KIND_ALWAYS, span("s2", "Project Phoenix", "APPROVED", true),
                        document, ADMIN));
        assertEquals(DetectionRuleService.KEPT_CHANGING, e.getMessage());
    }

    @Test
    void removingARuleTakesItsTermOffPhilterUnlessItWasThereBefore() {
        lists.get(RedactList.NEVER).add("ACME DENTAL");
        final DetectionRule existing = service.makeFromSpan(DetectionRule.KIND_NEVER,
                span("s1", "Acme Dental", "REJECTED", false), document, ADMIN).rule();
        assertFalse(existing.isAddedToPhilter(), "Philter already had it, ignoring case");
        assertEquals(List.of("ACME DENTAL"), lists.get(RedactList.NEVER));
        service.remove(existing, ADMIN);
        assertEquals(List.of("ACME DENTAL"), lists.get(RedactList.NEVER), "left as it was found");

        final DetectionRule added = service.makeFromSpan(DetectionRule.KIND_ALWAYS,
                span("s2", "Project Phoenix", "APPROVED", true), document, ADMIN).rule();
        lists.get(RedactList.ALWAYS).add(0, "Other");
        lists.get(RedactList.ALWAYS).add("PROJECT PHOENIX");
        service.remove(added, ADMIN);
        assertEquals(List.of("Other", "PROJECT PHOENIX"), lists.get(RedactList.ALWAYS),
                "only the term the rule added; someone else's variant stays");
        assertTrue(stored.isEmpty());
        verify(audit).log(eq("DETECTION_RULE_REMOVE"), eq("DetectionRule"), eq(added.getId()), any());
    }

    @Test
    void onlyARefusedSpanOrOneAddedByHandMakesARule() {
        assertThrows(DetectionRuleService.RuleException.class, () -> service.makeFromSpan(DetectionRule.KIND_NEVER,
                span("s1", "Acme", "APPROVED", false), document, ADMIN));
        assertThrows(DetectionRuleService.RuleException.class, () -> service.makeFromSpan(DetectionRule.KIND_NEVER,
                span("s1", "Acme", "REJECTED", true), document, ADMIN));
        assertThrows(DetectionRuleService.RuleException.class, () -> service.makeFromSpan(DetectionRule.KIND_ALWAYS,
                span("s1", "Acme", "APPROVED", false), document, ADMIN));
        assertThrows(DetectionRuleService.RuleException.class, () -> service.makeFromSpan("SOMETIMES",
                span("s1", "Acme", "REJECTED", false), document, ADMIN));
    }

    @Test
    void valuesPhilterWouldMisreadOrRefuseAreRefusedFirst() {
        final DetectionRuleService.RuleException tooLong = assertThrows(DetectionRuleService.RuleException.class,
                () -> service.makeFromSpan(DetectionRule.KIND_NEVER, span("s1", "x".repeat(101), "REJECTED", false),
                        document, ADMIN));
        assertTrue(tooLong.getMessage().contains("101"), tooLong.getMessage());
        assertThrows(DetectionRuleService.RuleException.class, () -> service.makeFromSpan(DetectionRule.KIND_NEVER,
                span("s1", "Acme:FUZZY", "REJECTED", false), document, ADMIN));
        assertThrows(DetectionRuleService.RuleException.class, () -> service.makeFromSpan(DetectionRule.KIND_NEVER,
                span("s1", "   ", "REJECTED", false), document, ADMIN));
        // #74: a value that did not decrypt never reaches Philter's list, where saving the rule would then fail.
        final DetectionRuleService.RuleException undecryptable = assertThrows(DetectionRuleService.RuleException.class,
                () -> service.makeFromSpan(DetectionRule.KIND_NEVER,
                        span("s1", PiiFieldEncryption.UNDECRYPTABLE, "REJECTED", false), document, ADMIN));
        assertTrue(undecryptable.getMessage().contains("could not be decrypted"), undecryptable.getMessage());
        assertTrue(stored.isEmpty());
        org.mockito.Mockito.verifyNoInteractions(philter);
    }

    @Test
    void aValueCannotBeBothNeverAndAlwaysFlagged() {
        service.makeFromSpan(DetectionRule.KIND_NEVER, span("s1", "Acme", "REJECTED", false), document, ADMIN);
        final DetectionRuleService.RuleException e = assertThrows(DetectionRuleService.RuleException.class,
                () -> service.makeFromSpan(DetectionRule.KIND_ALWAYS, span("s2", "acme", "APPROVED", true),
                        document, ADMIN));
        assertTrue(e.getMessage().contains("never flag"), e.getMessage());

        final DetectionRuleService.Made again = service.makeFromSpan(DetectionRule.KIND_NEVER,
                span("s3", "ACME", "REJECTED", false), document, ADMIN);
        assertTrue(again.message().startsWith("A rule already says"), again.message());
        assertEquals(1, stored.size());
        assertEquals(List.of("Acme"), lists.get(RedactList.NEVER));
    }

    @Test
    void aDismissedProposalCanBeMadeAgainByAReviewer() {
        final DetectionRule rule = service.makeFromSpan(DetectionRule.KIND_NEVER,
                span("s1", "Acme", "REJECTED", false), document, REVIEWER).rule();
        service.dismiss(rule, ADMIN);
        assertEquals(DetectionRule.STATUS_DISMISSED, rule.getStatus());
        assertThrows(DetectionRuleService.RuleException.class, () -> service.confirm(rule, ADMIN));

        final DetectionRule again = service.makeFromSpan(DetectionRule.KIND_NEVER,
                span("s2", "Acme", "REJECTED", false), document, ADMIN).rule();
        assertEquals(rule.getId(), again.getId());
        assertEquals(DetectionRule.STATUS_ACTIVE, again.getStatus());
        assertEquals(1, stored.size());
    }

    private void batchSpans(final Span... found) {
        when(documents.findByBatchId("b1")).thenReturn(List.of(document));
        when(spans.findByDocumentIdInAndStatusIn(eq(List.of("d1")), any())).thenReturn(List.of(found));
    }

    @Test
    void aValueRefusedEnoughTimesInABatchIsProposed() {
        settings.setRuleProposalRefusals(3);
        final Span refused = span("s3", "Acme Dental", "REJECTED", false);
        batchSpans(span("s1", "acme dental", "REJECTED", false), span("s2", "Acme Dental ", "REJECTED", false),
                refused, span("s4", "Other", "REJECTED", false));

        service.noteRefusal(refused, document);

        assertEquals(1, stored.size());
        final DetectionRule rule = stored.values().iterator().next();
        assertEquals(DetectionRule.STATUS_PROPOSED, rule.getStatus());
        assertEquals(DetectionRule.ORIGIN_REFUSALS, rule.getOrigin());
        assertEquals(DetectionRule.KIND_NEVER, rule.getKind());
        assertEquals(3, rule.getRefusals());
        assertEquals(null, rule.getProposedBy());
        assertTrue(lists.get(RedactList.NEVER).isEmpty(), "a person confirms it");
        verify(audit).log(eq("DETECTION_RULE_PROPOSE"), eq("DetectionRule"), eq(rule.getId()), any());

        service.noteRefusal(refused, document);
        assertEquals(1, stored.size(), "not proposed twice");
    }

    @Test
    void aValueAcceptedAnywhereInTheBatchOrRefusedTooFewTimesIsNotProposed() {
        settings.setRuleProposalRefusals(2);
        final Span refused = span("s1", "Acme", "REJECTED", false);
        batchSpans(refused, span("s2", "Acme", "REJECTED", false), span("s3", "ACME", "APPROVED", false));
        service.noteRefusal(refused, document);
        assertTrue(stored.isEmpty(), "accepted once");

        batchSpans(refused);
        service.noteRefusal(refused, document);
        assertTrue(stored.isEmpty(), "refused once");

        settings.setRuleProposalRefusals(0);
        batchSpans(refused, span("s2", "Acme", "REJECTED", false));
        service.noteRefusal(refused, document);
        assertTrue(stored.isEmpty(), "proposals off");
        verify(documents, org.mockito.Mockito.times(2)).findByBatchId("b1");
    }
}
