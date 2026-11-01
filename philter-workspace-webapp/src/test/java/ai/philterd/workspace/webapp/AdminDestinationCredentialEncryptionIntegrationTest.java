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

import ai.philterd.workspace.model.S3Destination;
import ai.philterd.workspace.repository.LocalDirectoryDestinationRepository;
import ai.philterd.workspace.repository.S3DestinationRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.DestinationTester;
import ai.philterd.workspace.service.SymmetricCipher;
import ai.philterd.workspace.webapp.controllers.AdminDestinationController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the AdminDestinationController credential-encryption
 * contract. Drives the controller through Spring MVC's {@link MockMvc} (so
 * binding, parameter resolution, and the redirect view are all real) with the
 * <em>real</em> {@link SymmetricCipher} wired in (no mock cipher). Repositories
 * are mocked at the persistence boundary so an {@link ArgumentCaptor} can read
 * what would have been written to MongoDB.
 *
 * <p>The contract verified here: by the time a destination object reaches the
 * repository, its access and secret keys are AES-GCM ciphertext — never the
 * plaintext that arrived in the HTTP form. Catches the regression where a
 * future refactor accidentally bypasses the cipher (e.g. swaps
 * {@code cipher.encrypt(x)} for the raw {@code x}).</p>
 *
 * <p>Uses {@code MockMvcBuilders.standaloneSetup} rather than
 * {@code @WebMvcTest} so the test owns exactly the components that matter for
 * encryption — the encryption guarantee shouldn't depend on the rest of the
 * application context being constructible.</p>
 */
class AdminDestinationCredentialEncryptionIntegrationTest {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    private MockMvc mockMvc;
    private SymmetricCipher cipher;
    private LocalDirectoryDestinationRepository localRepository;
    private S3DestinationRepository s3Repository;
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        // Real cipher with a deterministic test key — produces real AES-GCM ciphertext.
        // The cipher requires base64 of exactly 32 bytes; build one inline.
        final byte[] keyBytes = new byte[32];
        java.util.Arrays.fill(keyBytes, (byte) 0x42);
        cipher = new SymmetricCipher(java.util.Base64.getEncoder().encodeToString(keyBytes));

        localRepository = mock(LocalDirectoryDestinationRepository.class);
        s3Repository = mock(S3DestinationRepository.class);
        auditLogService = mock(AuditLogService.class);

        final AdminDestinationController controller = new AdminDestinationController(
                localRepository, s3Repository,
                auditLogService, cipher, mock(DestinationTester.class),
                mock(ai.philterd.workspace.repository.BatchRepository.class), new ai.philterd.workspace.service.LocalDirectoryRoots(System.getProperty("java.io.tmpdir")));

        // Standalone setup wires this single controller through the real Spring MVC
        // dispatcher. Form binding, @RequestParam resolution, and view-name redirect
        // resolution are all real — exactly what runs in production.
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ====================================================================
    // S3 — create
    // ====================================================================

    @Test
    void s3CreateViaHttpPersistsCiphertextNotPlaintext() throws Exception {
        when(s3Repository.findFirstByNameIgnoreCase(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/destinations/s3")
                        .param("name", "archive")
                        .param("bucketName", "my-bucket")
                        .param("bucketKey", "finalized/")
                        .param("accessKey", ACCESS_KEY)
                        .param("secretKey", SECRET_KEY))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/destinations"));

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        final S3Destination saved = captor.getValue();

        assertEncrypted(saved.getEncryptedAccessKey(), ACCESS_KEY);
        assertEncrypted(saved.getEncryptedSecretKey(), SECRET_KEY);
    }

    @Test
    void s3CreateWithBlankCredentialsPersistsNullEncryptedFields() throws Exception {
        when(s3Repository.findFirstByNameIgnoreCase(any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/destinations/s3")
                        .param("name", "ambient")
                        .param("bucketName", "bucket")
                        .param("bucketKey", "k/")
                        .param("accessKey", "")
                        .param("secretKey", ""))
                .andExpect(status().is3xxRedirection());

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        // Both fields are null when no credentials were supplied — there is nothing to
        // encrypt, and null is the right "use ambient AWS credentials" sentinel.
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());
    }

    @Test
    void s3CreateTwiceProducesDistinctCiphertextForIdenticalCredentials() throws Exception {
        when(s3Repository.findFirstByNameIgnoreCase(any())).thenReturn(Optional.empty());

        for (final String name : new String[]{"first", "second"}) {
            mockMvc.perform(post("/admin/destinations/s3")
                            .param("name", name)
                            .param("bucketName", "bucket")
                            .param("bucketKey", "k/")
                            .param("accessKey", ACCESS_KEY)
                            .param("secretKey", SECRET_KEY))
                    .andExpect(status().is3xxRedirection());
        }

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository, times(2)).save(captor.capture());
        final S3Destination first = captor.getAllValues().get(0);
        final S3Destination second = captor.getAllValues().get(1);

        // Unique IV per call must produce distinct ciphertext.
        assertNotEquals(first.getEncryptedAccessKey(), second.getEncryptedAccessKey(),
                "two HTTP-driven encryptions of the same access key produced identical ciphertext");
        assertNotEquals(first.getEncryptedSecretKey(), second.getEncryptedSecretKey(),
                "two HTTP-driven encryptions of the same secret key produced identical ciphertext");
    }

    // ====================================================================
    // S3 — edit
    // ====================================================================

    @Test
    void s3EditReplaceCredentialsViaHttpPersistsCiphertextNotPlaintext() throws Exception {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        existing.setEncryptedAccessKey(cipher.encrypt("OLD-AK"));
        existing.setEncryptedSecretKey(cipher.encrypt("OLD-SK"));
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));

        mockMvc.perform(post("/admin/destinations/s3/s1/edit")
                        .param("bucketName", "bucket")
                        .param("bucketKey", "k/")
                        .param("accessKey", ACCESS_KEY)
                        .param("secretKey", SECRET_KEY))
                .andExpect(status().is3xxRedirection());

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        final S3Destination saved = captor.getValue();

        assertEncrypted(saved.getEncryptedAccessKey(), ACCESS_KEY);
        assertEncrypted(saved.getEncryptedSecretKey(), SECRET_KEY);
    }

    @Test
    void s3EditClearCredentialsViaHttpPersistsNullEncryptedFields() throws Exception {
        final S3Destination existing = new S3Destination();
        existing.setId("s1"); existing.setName("archive");
        existing.setBucketName("bucket"); existing.setBucketKey("k/");
        existing.setEncryptedAccessKey(cipher.encrypt("OLD-AK"));
        existing.setEncryptedSecretKey(cipher.encrypt("OLD-SK"));
        when(s3Repository.findById("s1")).thenReturn(Optional.of(existing));

        mockMvc.perform(post("/admin/destinations/s3/s1/edit")
                        .param("bucketName", "bucket")
                        .param("bucketKey", "k/")
                        .param("clearCredentials", "true"))
                .andExpect(status().is3xxRedirection());

        final ArgumentCaptor<S3Destination> captor = ArgumentCaptor.forClass(S3Destination.class);
        verify(s3Repository).save(captor.capture());
        assertNull(captor.getValue().getEncryptedAccessKey());
        assertNull(captor.getValue().getEncryptedSecretKey());
    }

    /**
     * Asserts that {@code ciphertext} is non-null, not equal to the plaintext,
     * does not contain the plaintext as a substring, and round-trips back to
     * the plaintext via the real cipher. Catches naive "fake encryption" like
     * base64-of-plaintext that decrypts correctly but leaks via substring.
     */
    private void assertEncrypted(final String ciphertext, final String plaintext) {
        assertNotNull(ciphertext, "expected non-null ciphertext");
        assertNotEquals(plaintext, ciphertext, "ciphertext equals plaintext — encryption bypassed");
        if (ciphertext.contains(plaintext)) {
            throw new AssertionError("ciphertext leaks the plaintext as a substring: " + plaintext);
        }
        assertEquals(plaintext, cipher.decrypt(ciphertext),
                "decrypt did not round-trip back to the original plaintext");
    }
}
