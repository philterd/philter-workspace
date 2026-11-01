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

import ai.philterd.workspace.model.S3Destination;
import ai.philterd.workspace.repository.LocalDirectoryDestinationRepository;
import ai.philterd.workspace.repository.S3DestinationRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.DestinationTester;
import ai.philterd.workspace.service.SymmetricCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end check that S3 destination credentials are AES-GCM encrypted
 * <em>before</em> being handed to the repository for persistence. Unlike
 * {@link AdminDestinationControllerTest}, this suite wires in the real
 * {@link SymmetricCipher} and asserts that the persisted value:
 * <ol>
 *     <li>is not the plaintext access/secret key (different from input),</li>
 *     <li>differs across two encryptions of the same plaintext (because AES-GCM uses
 *         a random IV per call), and</li>
 *     <li>round-trips back to the exact plaintext when decrypted.</li>
 * </ol>
 * Together those properties prove the controller is not accidentally bypassing
 * encryption or using a no-op cipher in any code path.
 */
class AdminDestinationCredentialEncryptionTest {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    private LocalDirectoryDestinationRepository localRepository;
    private S3DestinationRepository s3Repository;
    private AuditLogService auditLogService;
    private SymmetricCipher cipher;
    private AdminDestinationController controller;

    @BeforeEach
    void setUp() {
        localRepository = mock(LocalDirectoryDestinationRepository.class);
        s3Repository = mock(S3DestinationRepository.class);
        auditLogService = mock(AuditLogService.class);
        // A real cipher with a fixed test key — produces real AES-GCM ciphertext.
        // The cipher requires base64 of exactly 32 bytes; build one inline.
        final byte[] keyBytes = new byte[32];
        java.util.Arrays.fill(keyBytes, (byte) 0x37);
        cipher = new SymmetricCipher(java.util.Base64.getEncoder().encodeToString(keyBytes));

        controller = new AdminDestinationController(
                localRepository, s3Repository,
                auditLogService, cipher,
                mock(DestinationTester.class),
                mock(ai.philterd.workspace.repository.BatchRepository.class), new ai.philterd.workspace.service.LocalDirectoryRoots(System.getProperty("java.io.tmpdir")));
    }

    private static RedirectAttributes flash() { return new RedirectAttributesModelMap(); }

    // ====================================================================
    // S3 — create
    // ====================================================================

    @Test
    void s3CreatePersistsRealCiphertext() {
        when(s3Repository.findFirstByNameIgnoreCase(any())).thenReturn(Optional.empty());

        controller.createS3("archive", "bucket", "k/", ACCESS_KEY, SECRET_KEY, flash());

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        final S3Destination saved = captor.getValue();

        assertNotNull(saved.getEncryptedAccessKey());
        assertNotNull(saved.getEncryptedSecretKey());
        // Ciphertext must not equal the plaintext.
        assertNotEquals(ACCESS_KEY, saved.getEncryptedAccessKey(),
                "access key was stored unencrypted");
        assertNotEquals(SECRET_KEY, saved.getEncryptedSecretKey(),
                "secret key was stored unencrypted");
        // Ciphertext must not even contain the plaintext as a substring.
        // (Catches naive "fake encryption" like base64-of-plaintext.)
        assertNoPlaintextLeak(saved.getEncryptedAccessKey(), ACCESS_KEY);
        assertNoPlaintextLeak(saved.getEncryptedSecretKey(), SECRET_KEY);
        // Decryption must round-trip.
        assertEquals(ACCESS_KEY, cipher.decrypt(saved.getEncryptedAccessKey()));
        assertEquals(SECRET_KEY, cipher.decrypt(saved.getEncryptedSecretKey()));
    }

    @Test
    void s3CreateProducesDifferentCiphertextEachTimeForSamePlaintext() {
        when(s3Repository.findFirstByNameIgnoreCase(any())).thenReturn(Optional.empty());

        controller.createS3("a", "bucket", "k/", ACCESS_KEY, SECRET_KEY, flash());
        controller.createS3("b", "bucket", "k/", ACCESS_KEY, SECRET_KEY, flash());

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository, org.mockito.Mockito.times(2)).save(captor.capture());
        final S3Destination first = captor.getAllValues().get(0);
        final S3Destination second = captor.getAllValues().get(1);

        // GCM uses a random 12-byte IV per encryption; identical plaintext must
        // produce different ciphertext. If these match, encryption is broken or
        // the IV is being reused.
        assertNotEquals(first.getEncryptedAccessKey(), second.getEncryptedAccessKey(),
                "two encryptions of the same access key produced identical ciphertext — IV reuse?");
        assertNotEquals(first.getEncryptedSecretKey(), second.getEncryptedSecretKey(),
                "two encryptions of the same secret key produced identical ciphertext — IV reuse?");
    }

    // ====================================================================
    // S3 — edit (replace credentials)
    // ====================================================================

    @Test
    void s3EditReplaceCredentialsPersistsRealCiphertext() {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        existing.setEncryptedAccessKey(cipher.encrypt("OLD-AK"));
        existing.setEncryptedSecretKey(cipher.encrypt("OLD-SK"));
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));

        controller.editS3("s1", "bucket", "k/", ACCESS_KEY, SECRET_KEY, null, flash());

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        final S3Destination saved = captor.getValue();

        assertNotEquals(ACCESS_KEY, saved.getEncryptedAccessKey());
        assertNotEquals(SECRET_KEY, saved.getEncryptedSecretKey());
        assertNoPlaintextLeak(saved.getEncryptedAccessKey(), ACCESS_KEY);
        assertNoPlaintextLeak(saved.getEncryptedSecretKey(), SECRET_KEY);
        assertEquals(ACCESS_KEY, cipher.decrypt(saved.getEncryptedAccessKey()));
        assertEquals(SECRET_KEY, cipher.decrypt(saved.getEncryptedSecretKey()));
    }

    /**
     * Asserts that the stored ciphertext does not contain the plaintext as a
     * substring. Catches naive "fake encryption" like base64-of-plaintext that
     * round-trips correctly but leaks the secret to anyone who can read the row.
     */
    private static void assertNoPlaintextLeak(final String ciphertext, final String plaintext) {
        assertNotNull(ciphertext, "expected non-null ciphertext");
        if (ciphertext.contains(plaintext)) {
            throw new AssertionError("ciphertext leaks the plaintext as a substring: " + plaintext);
        }
    }
}
