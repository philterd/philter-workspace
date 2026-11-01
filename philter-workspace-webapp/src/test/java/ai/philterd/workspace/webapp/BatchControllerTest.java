/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp;

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Group;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.GroupRepository;
import ai.philterd.workspace.repository.ComplianceProfileRepository;
import ai.philterd.workspace.repository.FinalizationPolicyRepository;
import ai.philterd.workspace.repository.WeightSetRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.UserGroupsService;
import ai.philterd.workspace.webapp.controllers.BatchController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchControllerTest {

    private BatchRepository batchRepository;
    private DocumentRepository documentRepository;
    private GroupRepository groupRepository;
    private UserGroupsService userGroupsService;
    private AuditLogService auditLogService;
    private WeightSetRepository weightSetRepository;
    private FinalizationPolicyRepository finalizationPolicyRepository;
    private ComplianceProfileRepository complianceProfileRepository;
    private ai.philterd.workspace.repository.LocalDirectoryDestinationRepository localDirectoryDestinationRepository;
    private ai.philterd.workspace.repository.S3DestinationRepository s3DestinationRepository;
    private ai.philterd.workspace.service.BatchExportService batchExportService;
    private BatchController controller;

    @BeforeEach
    void setUp() {
        batchRepository = mock(BatchRepository.class);
        documentRepository = mock(DocumentRepository.class);
        groupRepository = mock(GroupRepository.class);
        userGroupsService = mock(UserGroupsService.class);
        auditLogService = mock(AuditLogService.class);
        weightSetRepository = mock(WeightSetRepository.class);
        finalizationPolicyRepository = mock(FinalizationPolicyRepository.class);
        complianceProfileRepository = mock(ComplianceProfileRepository.class);
        localDirectoryDestinationRepository = mock(ai.philterd.workspace.repository.LocalDirectoryDestinationRepository.class);
        s3DestinationRepository = mock(ai.philterd.workspace.repository.S3DestinationRepository.class);
        batchExportService = mock(ai.philterd.workspace.service.BatchExportService.class);
        // The "fp1" finalization policy id and "cp1" compliance profile id are used by the happy-path test below.
        when(finalizationPolicyRepository.existsById("fp1")).thenReturn(true);
        when(complianceProfileRepository.existsById("cp1")).thenReturn(true);
        controller = controller(ai.philterd.workspace.service.BatchDefaults.standard());
    }

    private BatchController controller(final ai.philterd.workspace.service.BatchDefaults defaults) {
        return new BatchController(batchRepository, documentRepository, groupRepository,
                userGroupsService,
                new ai.philterd.workspace.service.BatchAccessService(batchRepository, userGroupsService),
                auditLogService, weightSetRepository,
                finalizationPolicyRepository,
                complianceProfileRepository,
                localDirectoryDestinationRepository, s3DestinationRepository,
                batchExportService, defaults);
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication user() {
        return new UsernamePasswordAuthenticationToken("user@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }
    private static String success(final RedirectAttributes ra) {
        final Object s = ra.getFlashAttributes().get("success"); return s == null ? null : s.toString();
    }

    // ---------- destination (#24) ----------

    @Test
    void anAdminChoosesAndClearsABatchDestination() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Filings");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        final ai.philterd.workspace.model.S3Destination s3 = new ai.philterd.workspace.model.S3Destination();
        s3.setId("s1");
        s3.setName("staging");
        when(s3DestinationRepository.findById("s1")).thenReturn(Optional.of(s3));

        final RedirectAttributes ra = flash();
        controller.changeDestination("b1", "S3:s1", admin(), ra);
        assertEquals("S3", b.getDestinationType());
        assertEquals("s1", b.getDestinationId());
        assertEquals("Batch \"Filings\" delivers finalized documents to staging (Amazon S3).", success(ra));
        verify(auditLogService).log(eq("BATCH_DESTINATION_CHANGE"), eq("Batch"), eq("b1"),
                eq(java.util.Map.of("previousDestinationType", "", "previousDestinationId", "",
                        "destinationType", "S3", "destinationId", "s1")));

        final RedirectAttributes cleared = flash();
        controller.changeDestination("b1", "", admin(), cleared);
        assertNull(b.getDestinationType());
        assertNull(b.getDestinationId());
        assertEquals("Batch \"Filings\" no longer delivers finalized documents.", success(cleared));
    }

    @Test
    void aDestinationIsRefusedForANonAdminOrWhenItDoesNotExist() {
        final Batch b = new Batch();
        b.setId("b1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(localDirectoryDestinationRepository.findById("gone")).thenReturn(Optional.empty());

        final RedirectAttributes nonAdmin = flash();
        controller.changeDestination("b1", "LOCAL:gone", user(), nonAdmin);
        assertEquals("Only administrators can choose a batch's destination.", error(nonAdmin));
        for (String value : List.of("LOCAL:gone", "FTP:x", "nonsense")) {
            final RedirectAttributes ra = flash();
            controller.changeDestination("b1", value, admin(), ra);
            assertEquals("Selected destination no longer exists.", error(ra), value);
        }
        assertNull(b.getDestinationType());
        verify(batchRepository, never()).save(any());
    }

    // ---------- create ----------

    @Test
    void createRefusesUserWhoDoesNotLeadTheGroup() {
        // After the "create requires admin" rule was relaxed to also admit team leads,
        // a plain USER who isn't a lead of the chosen group must still be refused — but
        // the refusal now comes from the per-group lead check, not a blanket admin gate.
        when(groupRepository.existsById("g")).thenReturn(true);
        when(userGroupsService.leadGroupIdsForEmail("user@x.com")).thenReturn(Set.of());

        final RedirectAttributes ra = flash();
        controller.create("b", null, null, null, null, "g", null, false, "Financial", "", null, null, null, null, null, user(), ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("create batches in groups you lead"),
                "expected the lead-required error, got: " + error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createSucceedsForTeamLeadOfTheGroup() {
        // A USER who leads group g1 can create a batch there even though they aren't admin.
        when(groupRepository.existsById("g1")).thenReturn(true);
        when(userGroupsService.leadGroupIdsForEmail("user@x.com")).thenReturn(Set.of("g1"));

        final RedirectAttributes ra = flash();
        final String view = controller.create("Sample", null, 0.5, 0.2, null, "g1", null, false, "Healthcare", "",
                "fp1", "cp1", null, null, null, user(), ra);

        assertEquals("redirect:/batches", view);
        assertNull(error(ra));
        verify(batchRepository).save(any(Batch.class));
    }

    @Test
    void createRefusesLeadOfDifferentGroup() {
        // Per-group authority: a lead of g2 cannot create a batch in g1. This is the
        // central invariant of the role.
        when(groupRepository.existsById("g1")).thenReturn(true);
        when(userGroupsService.leadGroupIdsForEmail("user@x.com")).thenReturn(Set.of("g2"));

        final RedirectAttributes ra = flash();
        controller.create("b", null, null, null, null, "g1", null, false, "Financial", "", null, null, null, null, null, user(), ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("create batches in groups you lead"),
                "expected the lead-required error, got: " + error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsBlankName() {
        final RedirectAttributes ra = flash();
        controller.create(" ", null, null, null, null, "g", null, false, "Financial", "", null, null, null, null, null, admin(), ra);
        assertEquals("Batch name is required.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsThresholdOutOfRange() {
        final RedirectAttributes ra = flash();
        controller.create("b", null, 1.5, null, null, "g", null, false, "Financial", "", null, null, null, null, null, admin(), ra);
        assertEquals("PII threshold must be between 0 and 1.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsMissingGroup() {
        final RedirectAttributes ra = flash();
        when(groupRepository.existsById("missing")).thenReturn(false);
        controller.create("b", null, null, null, null, "missing", null, false, "Financial", "", null, null, null, null, null, admin(), ra);
        assertEquals("A valid group must be selected.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsBlankDomain() {
        when(groupRepository.existsById("g1")).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.create("b", null, null, null, null, "g1", null, false, "  ", "", null, null, null, null, null, admin(), ra);
        assertEquals("Domain is required.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsUnknownDomain() {
        when(groupRepository.existsById("g1")).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.create("b", null, null, null, null, "g1", null, false, "Aerospace", "", null, null, null, null, null, admin(), ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("not a valid choice"));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsANameTheGroupAlreadyUses() {
        when(groupRepository.existsById("g1")).thenReturn(true);
        when(batchRepository.existsByGroupIdAndName("g1", "Sample")).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.create("Sample", null, null, null, null, "g1", null, false, "Financial", "", null, null, null, null, null, admin(), ra);
        assertEquals("This group already has a batch named \"Sample\".", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void aTeamLeadMayUseANameAnotherGroupsBatchHas() {
        // g2 has a batch named "Sample"; the lead of g1 is neither refused nor told so (#83).
        when(groupRepository.existsById("g1")).thenReturn(true);
        when(batchRepository.existsByGroupIdAndName("g2", "Sample")).thenReturn(true);
        when(userGroupsService.leadGroupIdsForEmail("user@x.com")).thenReturn(Set.of("g1"));

        final RedirectAttributes ra = flash();
        controller.create("Sample", null, null, null, null, "g1", null, false, "Healthcare", "",
                "fp1", "cp1", null, null, null, user(), ra);

        assertNull(error(ra));
        verify(batchRepository).existsByGroupIdAndName("g1", "Sample");
        verify(batchRepository, never()).existsByGroupIdAndName(org.mockito.ArgumentMatchers.eq("g2"), anyString());
        verify(batchRepository).save(any(Batch.class));
    }

    @Test
    void aConcurrentCreateOfTheSameNameInTheGroupIsRefusedWithTheSameMessage() {
        when(groupRepository.existsById("g1")).thenReturn(true);
        when(batchRepository.save(any(Batch.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("Duplicate key in batches"));

        final RedirectAttributes ra = flash();
        controller.create("Sample", null, null, null, null, "g1", null, false, "Healthcare", "",
                "fp1", "cp1", null, null, null, admin(), ra);
        assertEquals("This group already has a batch named \"Sample\".", error(ra));
    }

    @Test
    void createSucceedsWithValidDomain() {
        when(groupRepository.existsById("g1")).thenReturn(true);

        final RedirectAttributes ra = flash();
        final String view = controller.create("Sample", null, 0.5, 0.2, null, "g1", null, false, "Healthcare", "", "fp1", "cp1", null, null, null, admin(), ra);
        assertEquals("redirect:/batches", view);
        assertNull(error(ra));
        assertEquals("Batch \"Sample\" created.", success(ra));
        verify(batchRepository).save(any(Batch.class));
    }

    // ---------- create with Blind Double Review parameters ----------

    @Test
    void createDefaultsBlindDoubleReviewDisabledWhenCheckboxAbsent() {
        when(groupRepository.existsById("g1")).thenReturn(true);

        final RedirectAttributes ra = flash();
        final String view = controller.create("Sample", null, null, null, null, "g1", null, false,
                "Healthcare", "", "fp1", "cp1", null, /*blindEnabled*/ null, /*blindPct*/ null, admin(), ra);
        assertEquals("redirect:/batches", view);
        assertNull(error(ra));

        final ArgumentCaptor<Batch> saved = ArgumentCaptor.forClass(Batch.class);
        verify(batchRepository).save(saved.capture());
        assertFalse(saved.getValue().isBlindDoubleReviewEnabled(),
                "Missing blindDoubleReviewEnabled checkbox must default to false.");
        assertEquals(10, saved.getValue().getBlindDoubleReviewPercentage(),
                "When the feature is disabled, the model default percentage (10) is preserved.");
    }

    @Test
    void createUsesPercentageOnlyWhenFeatureIsEnabled() {
        // Even if a percentage is sent, an unchecked checkbox means the feature is off and
        // the stored percentage is the model default — the submitted value is ignored.
        when(groupRepository.existsById("g1")).thenReturn(true);

        controller.create("Sample", null, null, null, null, "g1", null, false, "Healthcare", "",
                "fp1", "cp1", null, /*blindEnabled*/ null, /*blindPct*/ 75, admin(), flash());

        final ArgumentCaptor<Batch> saved = ArgumentCaptor.forClass(Batch.class);
        verify(batchRepository).save(saved.capture());
        assertFalse(saved.getValue().isBlindDoubleReviewEnabled());
        assertEquals(10, saved.getValue().getBlindDoubleReviewPercentage(),
                "A percentage submitted alongside an unchecked checkbox must be ignored.");
    }

    @Test
    void createPersistsExplicitPercentageWhenFeatureIsEnabled() {
        when(groupRepository.existsById("g1")).thenReturn(true);

        controller.create("Sample", null, null, null, null, "g1", null, false, "Healthcare", "",
                "fp1", "cp1", null, /*blindEnabled*/ true, /*blindPct*/ 25, admin(), flash());

        final ArgumentCaptor<Batch> saved = ArgumentCaptor.forClass(Batch.class);
        verify(batchRepository).save(saved.capture());
        assertTrue(saved.getValue().isBlindDoubleReviewEnabled());
        assertEquals(25, saved.getValue().getBlindDoubleReviewPercentage());
    }

    @Test
    void createDefaultsToTenPercentWhenEnabledWithoutValue() {
        // The HTML checkbox can submit on without an explicit percentage if the input were
        // disabled in markup; the controller must fall back to 10% rather than refuse.
        when(groupRepository.existsById("g1")).thenReturn(true);

        controller.create("Sample", null, null, null, null, "g1", null, false, "Healthcare", "",
                "fp1", "cp1", null, /*blindEnabled*/ true, /*blindPct*/ null, admin(), flash());

        final ArgumentCaptor<Batch> saved = ArgumentCaptor.forClass(Batch.class);
        verify(batchRepository).save(saved.capture());
        assertTrue(saved.getValue().isBlindDoubleReviewEnabled());
        assertEquals(10, saved.getValue().getBlindDoubleReviewPercentage(),
                "Default percentage when the feature is enabled but no explicit value is supplied is 10.");
    }

    @Test
    void createRejectsBlindDoubleReviewPercentageBelowOne() {
        when(groupRepository.existsById("g1")).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.create("Sample", null, null, null, null, "g1", null, false, "Healthcare", "",
                "fp1", "cp1", null, /*blindEnabled*/ true, /*blindPct*/ 0, admin(), ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("between 1 and 100"),
                "Zero is not a valid percentage; expected user-facing error mentioning the range.");
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsBlindDoubleReviewPercentageAbove100() {
        when(groupRepository.existsById("g1")).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.create("Sample", null, null, null, null, "g1", null, false, "Healthcare", "",
                "fp1", "cp1", null, /*blindEnabled*/ true, /*blindPct*/ 101, admin(), ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("between 1 and 100"));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void createRejectsNegativeBlindDoubleReviewPercentage() {
        when(groupRepository.existsById("g1")).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.create("Sample", null, null, null, null, "g1", null, false, "Healthcare", "",
                "fp1", "cp1", null, /*blindEnabled*/ true, /*blindPct*/ -5, admin(), ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("between 1 and 100"));
        verify(batchRepository, never()).save(any());
    }

    // ---------- changePolicy ----------

    @Test
    void changePolicyRefusesNonLead() {
        // A USER who isn't a lead of the batch's group is refused. To avoid leaking
        // existence to a probing caller, the refusal uses the same generic
        // "Batch not found." message as a real lookup miss.
        final Batch b = new Batch();
        b.setId("b1");
        b.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(userGroupsService.leadGroupIdsForEmail("user@x.com")).thenReturn(Set.of());

        final RedirectAttributes ra = flash();
        controller.changePolicy("b1", null, false, user(), ra);
        assertEquals("Batch not found.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void changePolicyAllowsLeadOfBatchsGroup() {
        // A USER who leads the batch's group can edit the batch even though they aren't admin.
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("B");
        b.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(userGroupsService.leadGroupIdsForEmail("user@x.com")).thenReturn(Set.of("g1"));

        final RedirectAttributes ra = flash();
        final String view = controller.changePolicy("b1", "", false, user(), ra);
        assertEquals("redirect:/batches", view);
        assertNull(error(ra));
        verify(batchRepository).save(b);
    }

    @Test
    void changePolicyMissingBatch() {
        when(batchRepository.findById("b1")).thenReturn(Optional.empty());

        final RedirectAttributes ra = flash();
        controller.changePolicy("b1", "policy", false, admin(), ra);
        assertEquals("Batch not found.", error(ra));
    }

    @Test
    void changePolicyToPhiltersDefault() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("B");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));

        final RedirectAttributes ra = flash();
        final String view = controller.changePolicy("b1", "", false, admin(), ra);
        assertEquals("redirect:/batches", view);
        assertTrue(success(ra).contains("Philter's default policy"));
        verify(batchRepository).save(b);
        assertNull(b.getPolicyName());
    }

    @Test
    void aBatchCanChooseItsPolicyByCaseType() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Filings");
        b.setPolicyName("general");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));

        final RedirectAttributes ra = flash();
        controller.changePolicy("b1", "general", true, admin(), ra);

        assertTrue(b.isPolicyByCaseType());
        assertEquals("general", b.getPolicyName(), "kept, for when the switch is turned off");
        assertTrue(success(ra).contains("the policy mapped to each document's case type"), success(ra));
        verify(auditLogService).log(org.mockito.ArgumentMatchers.eq("BATCH_POLICY_CHANGE"), any(), any(),
                org.mockito.ArgumentMatchers.eq(java.util.Map.of("previousPolicyName", "general",
                        "newPolicyName", "general", "previousPolicyByCaseType", false, "policyByCaseType", true)));

        controller.changePolicy("b1", "general", false, admin(), flash());
        assertFalse(b.isPolicyByCaseType());
    }

    // ---------- changeGroup ----------

    @Test
    void changeGroupRequiresAdminEvenForTeamLead() {
        // Reassigning a batch to a different group is admin-only — even a team lead of
        // the source group cannot transfer batches in or out, since that would let them
        // sidestep the per-group authority boundary.
        when(userGroupsService.leadGroupIdsForEmail("user@x.com")).thenReturn(Set.of("g1"));

        final RedirectAttributes ra = flash();
        controller.changeGroup("b1", "g1", user(), ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("administrators"),
                "expected admin-only refusal even for team leads, got: " + error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void changeGroupRejectsInvalidGroup() {
        final Batch b = new Batch();
        b.setId("b1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(groupRepository.existsById(anyString())).thenReturn(false);

        final RedirectAttributes ra = flash();
        controller.changeGroup("b1", "ghost", admin(), ra);
        assertEquals("A valid group must be selected.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void changeGroupRefusesAGroupWithABatchOfTheSameName() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Sample");
        b.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(groupRepository.existsById("g2")).thenReturn(true);
        final Group g2 = new Group();
        g2.setId("g2");
        g2.setName("Litigation");
        when(groupRepository.findById("g2")).thenReturn(Optional.of(g2));
        when(batchRepository.existsByGroupIdAndName("g2", "Sample")).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.changeGroup("b1", "g2", admin(), ra);
        assertEquals("Group \"Litigation\" already has a batch named \"Sample\".", error(ra));
        assertEquals("g1", b.getGroupId());
        verify(batchRepository, never()).save(any());
    }

    @Test
    void changeGroupToTheBatchsOwnGroupIsNotTakenForAClash() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Sample");
        b.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(groupRepository.existsById("g1")).thenReturn(true);
        when(batchRepository.existsByGroupIdAndName("g1", "Sample")).thenReturn(true);

        final RedirectAttributes ra = flash();
        controller.changeGroup("b1", "g1", admin(), ra);
        assertNull(error(ra));
        verify(batchRepository).save(b);
    }

    // ---------- close ----------

    @Test
    void closeRefusesNonLead() {
        // Same generic body as a real lookup miss — see BatchControllerExistenceLeakTest
        // for the cross-endpoint indistinguishability contract this preserves.
        final Batch b = new Batch();
        b.setId("b1");
        b.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(userGroupsService.leadGroupIdsForEmail("user@x.com")).thenReturn(Set.of());

        final RedirectAttributes ra = flash();
        controller.close("b1", user(), ra);
        assertEquals("Batch not found.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void closeAllowsLeadOfBatchsGroup() {
        // A USER who leads the batch's group can close it once all its documents are
        // rejected or finalized. The "all docs ready" precondition still applies.
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Sample");
        b.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(userGroupsService.leadGroupIdsForEmail("user@x.com")).thenReturn(Set.of("g1"));
        when(documentRepository.countByBatchId("b1")).thenReturn(2L);
        when(documentRepository.countByBatchIdAndStatusIn("b1", Set.of("REJECTED", "FINALIZED"))).thenReturn(2L);

        final RedirectAttributes ra = flash();
        controller.close("b1", user(), ra);
        assertNull(error(ra));
        assertTrue(b.isClosed());
        verify(batchRepository).save(b);
    }

    @Test
    void closeRejectsMissingBatch() {
        when(batchRepository.findById("b1")).thenReturn(Optional.empty());

        final RedirectAttributes ra = flash();
        controller.close("b1", admin(), ra);
        assertEquals("Batch not found.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void closeRejectsAlreadyClosed() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("B");
        b.setClosed(true);
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));

        final RedirectAttributes ra = flash();
        controller.close("b1", admin(), ra);
        assertEquals("Batch is already closed.", error(ra));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void closeRejectsWhenDocumentsAreNotAllRejectedOrFinalized() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Sample");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(documentRepository.countByBatchId("b1")).thenReturn(5L);
        when(documentRepository.countByBatchIdAndStatusIn("b1", Set.of("REJECTED", "FINALIZED"))).thenReturn(3L);

        final RedirectAttributes ra = flash();
        controller.close("b1", admin(), ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("cannot be closed"));
        assertTrue(error(ra).contains("2 documents are"));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void closeUsesSingularWhenOneDocumentRemains() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Sample");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(documentRepository.countByBatchId("b1")).thenReturn(2L);
        when(documentRepository.countByBatchIdAndStatusIn("b1", Set.of("REJECTED", "FINALIZED"))).thenReturn(1L);

        final RedirectAttributes ra = flash();
        controller.close("b1", admin(), ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("1 document is"));
        verify(batchRepository, never()).save(any());
    }

    @Test
    void closeSucceedsWhenAllDocumentsAreRejectedOrFinalized() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Sample");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(documentRepository.countByBatchId("b1")).thenReturn(4L);
        when(documentRepository.countByBatchIdAndStatusIn("b1", Set.of("REJECTED", "FINALIZED"))).thenReturn(4L);

        final RedirectAttributes ra = flash();
        final String view = controller.close("b1", admin(), ra);
        assertEquals("redirect:/batches", view);
        assertNull(error(ra));
        assertEquals("Batch \"Sample\" closed.", success(ra));
        assertTrue(b.isClosed());
        assertNotNull(b.getClosedAt());
        verify(batchRepository).save(b);
    }

    @Test
    void closeSucceedsForEmptyBatch() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Empty");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        when(documentRepository.countByBatchId("b1")).thenReturn(0L);
        when(documentRepository.countByBatchIdAndStatusIn("b1", Set.of("REJECTED", "FINALIZED"))).thenReturn(0L);

        final RedirectAttributes ra = flash();
        final String view = controller.close("b1", admin(), ra);
        assertEquals("redirect:/batches", view);
        assertEquals("Batch \"Empty\" closed.", success(ra));
        verify(batchRepository).save(b);
    }

    @Test
    void exportsLeaveOutRedactionsNoPersonDecidedUnlessAskedToIncludeThem() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Claims");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        final ai.philterd.workspace.service.BatchExportService.Format jsonl =
                ai.philterd.workspace.service.BatchExportService.Format.JSONL;
        final ai.philterd.workspace.service.BatchExportService.DestinationKind local =
                ai.philterd.workspace.service.BatchExportService.DestinationKind.LOCAL;
        when(batchExportService.enqueueExport(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(ai.philterd.workspace.service.BatchExportService.Result.queued("job", 3));

        final RedirectAttributes excluded = flash();
        controller.export("b1", "LOCAL:dest", "JSONL", "exclude", admin(), excluded);
        verify(batchExportService).enqueueExport("b1", jsonl, local, "dest", true, "admin@x.com");
        assertTrue(success(excluded).contains("leaving out any with redactions no person decided"), success(excluded));

        controller.export("b1", "LOCAL:dest", "JSONL", "include", admin(), flash());
        verify(batchExportService).enqueueExport("b1", jsonl, local, "dest", false, "admin@x.com");
    }

    // ---- configured defaults and the minimum audit sampling rate (#37)

    private Batch createWith(final BatchController c, final Double pii, final Double doc, final Double rate,
                             final RedirectAttributes ra) {
        when(groupRepository.existsById("g1")).thenReturn(true);
        c.create("Sample", null, pii, doc, rate, "g1", null, false, "Healthcare", "", "fp1", "cp1", null, null, null, admin(), ra);
        final ArgumentCaptor<Batch> saved = ArgumentCaptor.forClass(Batch.class);
        verify(batchRepository, org.mockito.Mockito.atMost(1)).save(saved.capture());
        return saved.getAllValues().isEmpty() ? null : saved.getValue();
    }

    @Test
    void aNewBatchGetsTheConfiguredDefaults() {
        final Batch batch = createWith(controller(ai.philterd.workspace.service.BatchDefaults.of(0.9, 0.3, 1.0, 0)),
                null, null, null, flash());
        assertEquals(0.9, batch.getConfidenceThreshold());
        assertEquals(0.3, batch.getDocumentThreshold());
        assertEquals(1.0, batch.getAuditSamplingRate());
    }

    @Test
    void withoutConfigurationANewBatchGetsTodaysDefaults() {
        final Batch batch = createWith(controller, null, null, null, flash());
        assertEquals(0.8, batch.getConfidenceThreshold());
        assertEquals(0.25, batch.getDocumentThreshold());
        assertEquals(0.10, batch.getAuditSamplingRate());
    }

    @Test
    void theCreateFormSetsTheAuditSamplingRate() {
        final Batch batch = createWith(controller, 0.7, 0.2, 0.5, flash());
        assertEquals(0.7, batch.getConfidenceThreshold());
        assertEquals(0.5, batch.getAuditSamplingRate());

        final RedirectAttributes ra = flash();
        createWith(controller(ai.philterd.workspace.service.BatchDefaults.standard()), null, null, 1.5, ra);
        assertEquals("Audit sampling rate must be between 0 and 1.", error(ra));
    }

    @Test
    void aRateBelowTheMinimumIsRefusedAndAuditedOnCreate() {
        final RedirectAttributes ra = flash();
        final Batch batch = createWith(controller(ai.philterd.workspace.service.BatchDefaults.of(0.8, 0.25, 1.0, 1.0)),
                null, null, 0.5, ra);

        assertNull(batch);
        assertEquals("The audit sampling rate cannot be below 1.0 in this deployment.", error(ra));
        verify(auditLogService).log(org.mockito.ArgumentMatchers.eq("BATCH_AUDIT_SAMPLING_RATE_REFUSED"),
                org.mockito.ArgumentMatchers.eq("Batch"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(java.util.Map.of("name", "Sample",
                        "requestedAuditSamplingRate", 0.5, "minimumAuditSamplingRate", 1.0)));
    }

    @Test
    void aRateBelowTheMinimumIsRefusedAndAuditedInBatchSettings() {
        final Batch b = new Batch();
        b.setId("b1");
        b.setName("Claims");
        b.setAuditSamplingRate(1.0);
        when(batchRepository.findById("b1")).thenReturn(Optional.of(b));
        final BatchController strict = controller(ai.philterd.workspace.service.BatchDefaults.of(0.8, 0.25, 1.0, 1.0));

        final RedirectAttributes ra = flash();
        strict.editThresholds("b1", 0.8, 0.25, 0.2, null, null, null, admin(), ra);

        assertEquals("The audit sampling rate cannot be below 1.0 in this deployment.", error(ra));
        assertEquals(1.0, b.getAuditSamplingRate());
        verify(batchRepository, never()).save(any(Batch.class));
        verify(auditLogService).log(org.mockito.ArgumentMatchers.eq("BATCH_AUDIT_SAMPLING_RATE_REFUSED"),
                org.mockito.ArgumentMatchers.eq("Batch"), org.mockito.ArgumentMatchers.eq("b1"),
                org.mockito.ArgumentMatchers.eq(java.util.Map.of("name", "Claims",
                        "requestedAuditSamplingRate", 0.2, "minimumAuditSamplingRate", 1.0)));

        // At the minimum, the change is saved.
        final RedirectAttributes ok = flash();
        strict.editThresholds("b1", 0.9, 0.25, 1.0, null, null, null, admin(), ok);
        assertNull(error(ok));
        assertEquals(0.9, b.getConfidenceThreshold());
    }

    @Test
    void someoneWhoMayNotCreateHereGetsTheAccessRefusalNotARateAudit() {
        when(groupRepository.existsById("g1")).thenReturn(true);
        when(userGroupsService.leadGroupIdsForEmail("user@x.com")).thenReturn(Set.of("g2"));
        final RedirectAttributes ra = flash();

        controller(ai.philterd.workspace.service.BatchDefaults.of(0.8, 0.25, 1.0, 1.0)).create("Sample", null, null, null,
                0.5, "g1", null, false, "Healthcare", "", "fp1", "cp1", null, null, null, user(), ra);

        assertTrue(error(ra).startsWith("You can only create batches in groups you lead."), error(ra));
        verify(auditLogService, never()).log(org.mockito.ArgumentMatchers.eq("BATCH_AUDIT_SAMPLING_RATE_REFUSED"),
                any(), any(), any());
    }
}
