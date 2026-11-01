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

import ai.philterd.workspace.model.LocalDirectoryDestination;
import ai.philterd.workspace.model.S3Destination;
import ai.philterd.workspace.repository.LocalDirectoryDestinationRepository;
import ai.philterd.workspace.repository.S3DestinationRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.DestinationTester;
import ai.philterd.workspace.service.SymmetricCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AdminDestinationController}: list view, local-directory
 * create/delete, and S3 create/delete. Covers happy paths and every key
 * negative case (validation, paired credentials, duplicate-name races).
 */
class AdminDestinationControllerTest {

    /** An existing folder under the allowed root these tests use: Local Directory paths must exist under one (#72). */
    private static final String TMP = System.getProperty("java.io.tmpdir");


    private LocalDirectoryDestinationRepository localRepository;
    private S3DestinationRepository s3Repository;
    private AuditLogService auditLogService;
    private SymmetricCipher cipher;
    private DestinationTester destinationTester;
    private ai.philterd.workspace.repository.BatchRepository batchRepository;
    private AdminDestinationController controller;

    @BeforeEach
    void setUp() {
        localRepository = mock(LocalDirectoryDestinationRepository.class);
        s3Repository = mock(S3DestinationRepository.class);
        auditLogService = mock(AuditLogService.class);
        cipher = mock(SymmetricCipher.class);
        destinationTester = mock(DestinationTester.class);
        batchRepository = mock(ai.philterd.workspace.repository.BatchRepository.class);
        when(cipher.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));

        // Default: no destinations.
        when(localRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(s3Repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller = new AdminDestinationController(
                localRepository, s3Repository, auditLogService, cipher, destinationTester, batchRepository, new ai.philterd.workspace.service.LocalDirectoryRoots(System.getProperty("java.io.tmpdir")));
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }
    private static String success(final RedirectAttributes ra) {
        final Object s = ra.getFlashAttributes().get("success"); return s == null ? null : s.toString();
    }

    // ====================================================================
    // List
    // ====================================================================

    @Test
    void listExposesAllCollectionsToTheModel() {
        final LocalDirectoryDestination l = new LocalDirectoryDestination();
        l.setId("l1"); l.setName("local-1");
        final S3Destination s = new S3Destination();
        s.setId("s1"); s.setName("s3-1");
        when(localRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(l)));
        when(s3Repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s)));

        final Model model = new ConcurrentModel();
        final String view = controller.list(model);

        assertEquals("admin-destinations", view);
        @SuppressWarnings("unchecked")
        final List<LocalDirectoryDestination> locals =
                (List<LocalDirectoryDestination>) model.getAttribute("localDestinations");
        @SuppressWarnings("unchecked")
        final List<S3Destination> s3s =
                (List<S3Destination>) model.getAttribute("s3Destinations");
        assertEquals(1, locals.size());
        assertSame(l, locals.get(0));
        assertEquals(1, s3s.size());
        assertSame(s, s3s.get(0));
    }

    // ====================================================================
    // Local Directory — create
    // ====================================================================

    @Test
    void localCreateHappyPathPersistsAndAudits() {
        when(localRepository.findFirstByNameIgnoreCase("archive")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        final String view = controller.createLocal("archive", TMP, ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("Local directory destination \"archive\" added.", success(ra));
        assertNull(error(ra));

        final ArgumentCaptor<LocalDirectoryDestination> captor =
                ArgumentCaptor.forClass(LocalDirectoryDestination.class);
        verify(localRepository).save(captor.capture());
        final LocalDirectoryDestination saved = captor.getValue();
        assertNotNull(saved.getId());
        assertEquals("archive", saved.getName());
        assertEquals(TMP, saved.getDirectoryPath());
        assertNotNull(saved.getCreatedAt());

        verify(auditLogService).log(eq("LOCAL_DESTINATION_CREATE"),
                eq("LocalDirectoryDestination"), eq(saved.getId()),
                eq(Map.of("name", "archive", "directoryPath", TMP)));
    }

    @Test
    void localCreateTrimsNameAndPath() {
        when(localRepository.findFirstByNameIgnoreCase("archive")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createLocal("  archive  ", "  " + TMP + "  ", ra);

        final ArgumentCaptor<LocalDirectoryDestination> captor =
                ArgumentCaptor.forClass(LocalDirectoryDestination.class);
        verify(localRepository).save(captor.capture());
        assertEquals("archive", captor.getValue().getName());
        assertEquals(TMP, captor.getValue().getDirectoryPath());
    }

    @Test
    void localCreateRejectsBlankName() {
        final RedirectAttributes ra = flash();

        controller.createLocal("   ", TMP, ra);

        assertEquals("Name is required.", error(ra));
        verify(localRepository, never()).save(any());
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    @Test
    void localCreateRejectsBlankPath() {
        final RedirectAttributes ra = flash();

        controller.createLocal("archive", "  ", ra);

        assertEquals("Directory path is required.", error(ra));
        verify(localRepository, never()).save(any());
    }

    @Test
    void localCreateRejectsDuplicateNameCaseInsensitive() {
        final LocalDirectoryDestination existing = new LocalDirectoryDestination();
        existing.setId("ex"); existing.setName("Archive");
        when(localRepository.findFirstByNameIgnoreCase("archive")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.createLocal("archive", TMP, ra);

        assertEquals("A local directory destination named \"archive\" already exists.", error(ra));
        verify(localRepository, never()).save(any());
    }

    @Test
    void localCreateRecoversFromDuplicateKeyRace() {
        when(localRepository.findFirstByNameIgnoreCase("archive")).thenReturn(Optional.empty());
        when(localRepository.save(any(LocalDirectoryDestination.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        final RedirectAttributes ra = flash();

        final String view = controller.createLocal("archive", TMP, ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("A local directory destination named \"archive\" already exists.", error(ra));
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    // ====================================================================
    // Local Directory — edit
    // ====================================================================

    @Test
    void localEditUpdatesPathAndAuditsButLeavesNameUntouched() {
        final LocalDirectoryDestination existing = new LocalDirectoryDestination();
        existing.setId("l1"); existing.setName("archive"); existing.setDirectoryPath("/old/path");
        when(localRepository.findById("l1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        final String view = controller.editLocal("l1", TMP, ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("Local directory destination \"archive\" updated.", success(ra));

        final ArgumentCaptor<LocalDirectoryDestination> captor =
                ArgumentCaptor.forClass(LocalDirectoryDestination.class);
        verify(localRepository).save(captor.capture());
        assertEquals("archive", captor.getValue().getName(), "name must be immutable");
        assertEquals(TMP, captor.getValue().getDirectoryPath());

        verify(auditLogService).log(eq("LOCAL_DESTINATION_UPDATE"),
                eq("LocalDirectoryDestination"), eq("l1"),
                eq(Map.of("name", "archive", "directoryPath", TMP)));
    }

    @Test
    void localEditTrimsPath() {
        final LocalDirectoryDestination existing = new LocalDirectoryDestination();
        existing.setId("l1"); existing.setName("archive"); existing.setDirectoryPath("/old");
        when(localRepository.findById("l1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editLocal("l1", "  " + TMP + "  ", ra);

        final ArgumentCaptor<LocalDirectoryDestination> captor =
                ArgumentCaptor.forClass(LocalDirectoryDestination.class);
        verify(localRepository).save(captor.capture());
        assertEquals(TMP, captor.getValue().getDirectoryPath());
    }

    @Test
    void localEditRejectsBlankPath() {
        final LocalDirectoryDestination existing = new LocalDirectoryDestination();
        existing.setId("l1"); existing.setName("archive"); existing.setDirectoryPath("/old");
        when(localRepository.findById("l1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editLocal("l1", "   ", ra);

        assertEquals("Directory path is required.", error(ra));
        verify(localRepository, never()).save(any());
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    @Test
    void localEditOfMissingIdSetsError() {
        when(localRepository.findById("nope")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.editLocal("nope", "/some/path", ra);

        assertEquals("Local directory destination not found.", error(ra));
        verify(localRepository, never()).save(any());
    }

    // ====================================================================
    // Local Directory — delete
    // ====================================================================

    @Test
    void localDeleteRemovesAndAudits() {
        final LocalDirectoryDestination existing = new LocalDirectoryDestination();
        existing.setId("l1"); existing.setName("archive");
        when(localRepository.findById("l1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        final String view = controller.deleteLocal("l1", ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("Local directory destination \"archive\" removed.", success(ra));
        verify(localRepository).deleteById("l1");
        verify(auditLogService).log(eq("LOCAL_DESTINATION_DELETE"),
                eq("LocalDirectoryDestination"), eq("l1"),
                eq(Map.of("name", "archive")));
    }

    @Test
    void localDeleteOfMissingIdSetsErrorAndDoesNotDelete() {
        when(localRepository.findById("nope")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.deleteLocal("nope", ra);

        assertEquals("Local directory destination not found.", error(ra));
        verify(localRepository, never()).deleteById(anyString());
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    // ====================================================================
    // S3 — create
    // ====================================================================

    @Test
    void s3CreateWithCredentialsEncryptsAndAudits() {
        when(s3Repository.findFirstByNameIgnoreCase("s3-archive")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        final String view = controller.createS3("s3-archive", "my-bucket", "finalized/",
                "AKIAEXAMPLE", "supersecret", ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("S3 destination \"s3-archive\" added.", success(ra));
        assertNull(error(ra));

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        final S3Destination saved = captor.getValue();
        assertEquals("s3-archive", saved.getName());
        assertEquals("my-bucket", saved.getBucketName());
        assertEquals("finalized/", saved.getBucketKey());
        assertEquals("enc:AKIAEXAMPLE", saved.getEncryptedAccessKey());
        assertEquals("enc:supersecret", saved.getEncryptedSecretKey());
        assertNotNull(saved.getCreatedAt());

        verify(auditLogService).log(eq("S3_DESTINATION_CREATE"),
                eq("S3Destination"), eq(saved.getId()),
                eq(Map.of("name", "s3-archive",
                        "bucketName", "my-bucket",
                        "bucketKey", "finalized/",
                        "credentialsSet", true)));
    }

    @Test
    void s3CreateWithoutCredentialsLeavesEncryptedFieldsNull() {
        when(s3Repository.findFirstByNameIgnoreCase("ambient")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createS3("ambient", "bucket", "k/", "", "", ra);

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());

        verify(auditLogService).log(eq("S3_DESTINATION_CREATE"),
                eq("S3Destination"), anyString(),
                eq(Map.of("name", "ambient",
                        "bucketName", "bucket",
                        "bucketKey", "k/",
                        "credentialsSet", false)));
    }

    @Test
    void s3CreateRejectsBlankName() {
        final RedirectAttributes ra = flash();

        controller.createS3("  ", "bucket", "k/", null, null, ra);

        assertEquals("Name is required.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRejectsBlankBucket() {
        final RedirectAttributes ra = flash();

        controller.createS3("name", "  ", "k/", null, null, ra);

        assertEquals("Bucket name is required.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRejectsBlankBucketKey() {
        final RedirectAttributes ra = flash();

        controller.createS3("name", "bucket", "  ", null, null, ra);

        assertEquals("Bucket key is required.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRejectsAccessKeyWithoutSecret() {
        final RedirectAttributes ra = flash();

        controller.createS3("name", "bucket", "k/", "AKIA", "", ra);

        assertEquals("Provide both Access key and Secret key, or leave both blank.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRejectsSecretWithoutAccessKey() {
        final RedirectAttributes ra = flash();

        controller.createS3("name", "bucket", "k/", "", "secret", ra);

        assertEquals("Provide both Access key and Secret key, or leave both blank.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRejectsDuplicateNameCaseInsensitive() {
        final S3Destination existing = new S3Destination();
        existing.setId("ex"); existing.setName("S3-Archive");
        when(s3Repository.findFirstByNameIgnoreCase("s3-archive")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.createS3("s3-archive", "bucket", "k/", "AKIA", "secret", ra);

        assertEquals("An S3 destination named \"s3-archive\" already exists.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRecoversFromDuplicateKeyRace() {
        when(s3Repository.findFirstByNameIgnoreCase("s3-archive")).thenReturn(Optional.empty());
        when(s3Repository.save(any(S3Destination.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        final RedirectAttributes ra = flash();

        controller.createS3("s3-archive", "bucket", "k/", null, null, ra);

        assertEquals("An S3 destination named \"s3-archive\" already exists.", error(ra));
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    // ====================================================================
    // S3 — edit
    // ====================================================================

    @Test
    void s3EditWithBlankCredentialsKeepsExistingCredentials() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("old-bucket"); existing.setBucketKey("old/");
        existing.setEncryptedAccessKey("enc:OLDAK");
        existing.setEncryptedSecretKey("enc:OLDSK");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("s1", "new-bucket", "new/", "", "", null, ra);

        assertEquals("S3 destination \"s3-archive\" updated.", success(ra));
        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        assertEquals("s3-archive", captor.getValue().getName(), "name must be immutable");
        assertEquals("new-bucket", captor.getValue().getBucketName());
        assertEquals("new/", captor.getValue().getBucketKey());
        assertEquals("enc:OLDAK", captor.getValue().getEncryptedAccessKey(), "credentials must be preserved");
        assertEquals("enc:OLDSK", captor.getValue().getEncryptedSecretKey(), "credentials must be preserved");

        verify(auditLogService).log(eq("S3_DESTINATION_UPDATE"),
                eq("S3Destination"), eq("s1"),
                eq(Map.of("name", "s3-archive",
                        "bucketName", "new-bucket",
                        "bucketKey", "new/",
                        "credentialsChanged", false,
                        "credentialsCleared", false)));
    }

    @Test
    void s3EditWithNewCredentialsEncryptsAndAuditsChange() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        existing.setEncryptedAccessKey("enc:OLDAK");
        existing.setEncryptedSecretKey("enc:OLDSK");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("s1", "bucket", "k/", "NEWAK", "NEWSK", null, ra);

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        assertEquals("enc:NEWAK", captor.getValue().getEncryptedAccessKey());
        assertEquals("enc:NEWSK", captor.getValue().getEncryptedSecretKey());

        verify(auditLogService).log(eq("S3_DESTINATION_UPDATE"),
                eq("S3Destination"), eq("s1"),
                eq(Map.of("name", "s3-archive",
                        "bucketName", "bucket",
                        "bucketKey", "k/",
                        "credentialsChanged", true,
                        "credentialsCleared", false)));
    }

    @Test
    void s3EditWithClearCredentialsNullsThemOut() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        existing.setEncryptedAccessKey("enc:OLDAK");
        existing.setEncryptedSecretKey("enc:OLDSK");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("s1", "bucket", "k/", "", "", "true", ra);

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());

        verify(auditLogService).log(eq("S3_DESTINATION_UPDATE"),
                eq("S3Destination"), eq("s1"),
                eq(Map.of("name", "s3-archive",
                        "bucketName", "bucket",
                        "bucketKey", "k/",
                        "credentialsChanged", true,
                        "credentialsCleared", true)));
    }

    @Test
    void s3EditClearWinsOverProvidedCredentials() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        existing.setEncryptedAccessKey("enc:OLDAK");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        // Even with credentials supplied, clear=true should win and null them out.
        controller.editS3("s1", "bucket", "k/", "AKIA", "secret", "true", ra);

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());
    }

    @Test
    void s3EditRejectsHalfFilledCredentialsWithoutClear() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("s1", "bucket", "k/", "AKIA", "", null, ra);

        assertEquals("Provide both Access key and Secret key, leave both blank to keep existing, or check Clear credentials.",
                error(ra));
        verify(s3Repository, never()).save(any());
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    @Test
    void s3EditRejectsBlankBucket() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("s1", "  ", "k/", "", "", null, ra);

        assertEquals("Bucket name is required.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3EditRejectsBlankBucketKey() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("s1", "bucket", "  ", "", "", null, ra);

        assertEquals("Bucket key is required.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3EditOfMissingIdSetsError() {
        when(s3Repository.findById("nope")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.editS3("nope", "bucket", "k/", "", "", null, ra);

        assertEquals("S3 destination not found.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void aDestinationABatchDeliversToCannotBeRemoved() {
        final LocalDirectoryDestination local = new LocalDirectoryDestination();
        local.setId("l1"); local.setName("archive");
        when(localRepository.findById("l1")).thenReturn(Optional.of(local));
        final S3Destination s3 = new S3Destination();
        s3.setId("s1"); s3.setName("s3-archive");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(s3));
        when(batchRepository.existsByDestinationTypeAndDestinationId("LOCAL", "l1")).thenReturn(true);
        when(batchRepository.existsByDestinationTypeAndDestinationId("S3", "s1")).thenReturn(true);

        final RedirectAttributes localRa = flash();
        controller.deleteLocal("l1", localRa);
        assertEquals("Local directory destination \"archive\" is used by a batch. Choose another destination for "
                + "that batch first.", error(localRa));
        final RedirectAttributes s3Ra = flash();
        controller.deleteS3("s1", s3Ra);
        assertEquals("S3 destination \"s3-archive\" is used by a batch. Choose another destination for that batch "
                + "first.", error(s3Ra));
        verify(localRepository, never()).deleteById(anyString());
        verify(s3Repository, never()).deleteById(anyString());
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    // ====================================================================
    // S3 — delete
    // ====================================================================

    @Test
    void s3DeleteRemovesAndAudits() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("s3-archive");
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        final String view = controller.deleteS3("s1", ra);

        assertEquals("redirect:/admin/destinations", view);
        assertEquals("S3 destination \"s3-archive\" removed.", success(ra));
        verify(s3Repository).deleteById("s1");
        verify(auditLogService).log(eq("S3_DESTINATION_DELETE"),
                eq("S3Destination"), eq("s1"),
                eq(Map.of("name", "s3-archive")));
    }

    @Test
    void s3DeleteOfMissingIdSetsErrorAndDoesNotDelete() {
        when(s3Repository.findById("nope")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.deleteS3("nope", ra);

        assertEquals("S3 destination not found.", error(ra));
        verify(s3Repository, never()).deleteById(anyString());
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), any());
    }

    // ====================================================================
    // Test endpoints — JSON delegations to DestinationTester
    // ====================================================================

    @Test
    void localTestEndpointReturnsSuccessJson() {
        when(destinationTester.testLocalDirectory("/tmp/out"))
                .thenReturn(DestinationTester.TestResult.success("Test file written to /tmp/out/philter-workspace-test-1.txt."));

        final org.springframework.http.ResponseEntity<java.util.Map<String, Object>> resp =
                controller.testLocal("/tmp/out");

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(Boolean.TRUE, resp.getBody().get("ok"));
        assertEquals("Test file written to /tmp/out/philter-workspace-test-1.txt.", resp.getBody().get("message"));
        assertNull(resp.getBody().get("error"));
    }

    @Test
    void localTestEndpointReturnsFailureJson() {
        when(destinationTester.testLocalDirectory("/no/such/dir"))
                .thenReturn(DestinationTester.TestResult.failure("Directory does not exist: /no/such/dir"));

        final org.springframework.http.ResponseEntity<java.util.Map<String, Object>> resp =
                controller.testLocal("/no/such/dir");

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(Boolean.FALSE, resp.getBody().get("ok"));
        assertEquals("Directory does not exist: /no/such/dir", resp.getBody().get("error"));
        assertNull(resp.getBody().get("message"));
    }

    @Test
    void s3TestEndpointPassesAllParamsAndReturnsSuccess() {
        when(destinationTester.testS3("bucket", "k/", "AKIA", "secret"))
                .thenReturn(DestinationTester.TestResult.success("Test object written to s3://bucket/k/philter-workspace-test-1.txt."));

        final org.springframework.http.ResponseEntity<java.util.Map<String, Object>> resp =
                controller.testS3("bucket", "k/", "AKIA", "secret");

        assertEquals(Boolean.TRUE, resp.getBody().get("ok"));
        assertEquals("Test object written to s3://bucket/k/philter-workspace-test-1.txt.", resp.getBody().get("message"));
        verify(destinationTester).testS3("bucket", "k/", "AKIA", "secret");
    }

    @Test
    void s3TestEndpointReturnsFailureJson() {
        when(destinationTester.testS3("bucket", "k/", null, null))
                .thenReturn(DestinationTester.TestResult.failure("Could not write to S3: AccessDenied"));

        final org.springframework.http.ResponseEntity<java.util.Map<String, Object>> resp =
                controller.testS3("bucket", "k/", null, null);

        assertEquals(Boolean.FALSE, resp.getBody().get("ok"));
        assertEquals("Could not write to S3: AccessDenied", resp.getBody().get("error"));
    }

}
