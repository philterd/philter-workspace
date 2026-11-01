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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the field-level encryption helpers on {@link SymmetricCipher}. These
 * are the building blocks used by {@link PiiFieldEncryption} to wrap MongoDB save/load
 * with transparent encryption — every contract here matters because a regression would
 * either expose plaintext PII at rest or render legacy rows unreadable.
 */
class SymmetricCipherFieldEncryptionTest {

    private SymmetricCipher cipher;

    @BeforeEach
    void setUp() {
        // Real 32-byte key — encryption is deterministic-shape but uses real AES-GCM.
        final byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        cipher = new SymmetricCipher(Base64.getEncoder().encodeToString(key));
    }

    // ---------- encryptField ----------

    @Test
    void encryptFieldRoundTripsThroughDecryptField() {
        final String pii = "Patient: alice@example.com — DOB 1985-04-12 — SSN 123-45-6789";
        final String encrypted = cipher.encryptField(pii);
        assertTrue(encrypted.startsWith(SymmetricCipher.FIELD_PREFIX),
                "Encrypted field must carry the marker prefix so the read path can recognise it.");
        assertNotEquals(pii, encrypted,
                "Encrypted form must not equal the plaintext; otherwise nothing was encrypted.");
        assertFalse(encrypted.contains(pii),
                "PII must not appear anywhere inside the encrypted blob.");
        assertEquals(pii, cipher.decryptField(encrypted),
                "decryptField must recover the original plaintext.");
    }

    @Test
    void encryptFieldIsNullSafe() {
        assertNull(cipher.encryptField(null),
                "Null input must round-trip as null — nothing to encrypt.");
    }

    @Test
    void encryptFieldEmptyStringPassesThroughUnchanged() {
        assertEquals("", cipher.encryptField(""),
                "Empty strings carry no PII; serializing them as ciphertext just makes the database "
                        + "harder to inspect. They must round-trip unchanged.");
    }

    @Test
    void encryptFieldEncryptsAValueThatIsAlreadyCiphertext() {
        // #74: the marker is not trusted on the way in. Ciphertext someone supplies is encrypted like any other
        // value, so reading it back gives what they wrote, never what it decrypts to.
        final String once = cipher.encryptField("hello world");
        final String twice = cipher.encryptField(once);
        assertNotEquals(once, twice);
        assertEquals(once, cipher.decryptField(twice));
    }

    @Test
    void encryptFieldProducesDifferentCiphertextEachCall() {
        // AES-GCM uses a fresh random IV per call, so two encryptions of the same
        // plaintext produce different ciphertexts. This frustrates frequency analysis
        // of the at-rest data — a determined attacker with just the database cannot
        // infer that two documents have the same content.
        final String first = cipher.encryptField("alice@example.com");
        final String second = cipher.encryptField("alice@example.com");
        assertNotEquals(first, second,
                "AES-GCM with a fresh IV per call must produce different ciphertexts each time.");
        assertEquals("alice@example.com", cipher.decryptField(first));
        assertEquals("alice@example.com", cipher.decryptField(second));
    }

    @Test
    void encryptFieldHandlesUnicodeAndLongInputs() {
        // Defensive: the byte-buffer arithmetic in SymmetricCipher must correctly handle
        // multi-byte UTF-8 sequences and substantial document text.
        final String mixed = "Patient 阿丽斯 was seen at clínica — phone: +52 (55) 1234-5678";
        assertEquals(mixed, cipher.decryptField(cipher.encryptField(mixed)));

        final StringBuilder long0 = new StringBuilder(64_000);
        for (int i = 0; i < 4_000; i++) long0.append("alice@example.com lives in Boston. ");
        assertEquals(long0.toString(), cipher.decryptField(cipher.encryptField(long0.toString())));
    }

    // ---------- decryptField ----------

    @Test
    void decryptFieldPassesThroughLegacyPlaintext() {
        // Existing rows written before encryption was switched on do NOT carry the
        // marker prefix. The read path must return them unchanged so the rollout is
        // backwards compatible.
        assertEquals("legacy plaintext document", cipher.decryptField("legacy plaintext document"));
    }

    @Test
    void decryptFieldIsNullSafe() {
        assertNull(cipher.decryptField(null));
    }

    @Test
    void decryptFieldPassesThroughEmptyString() {
        assertEquals("", cipher.decryptField(""));
    }

    @Test
    void decryptFieldRejectsTamperedCiphertext() {
        // GCM has authenticated encryption — flipping any bit of the ciphertext (or its
        // tag) must cause the decrypt to throw rather than silently returning garbage.
        final String encrypted = cipher.encryptField("real PII");
        // Modify the ciphertext after the prefix. Ensure the result is still valid base64
        // so that the failure happens at the GCM authentication step, not at decoding.
        final String body = encrypted.substring(SymmetricCipher.FIELD_PREFIX.length());
        // Flip one base64 character — replace the last character with a different valid one.
        final char last = body.charAt(body.length() - 1);
        final char replacement = last == 'A' ? 'B' : 'A';
        final String tampered = SymmetricCipher.FIELD_PREFIX + body.substring(0, body.length() - 1) + replacement;

        assertThrows(IllegalStateException.class, () -> cipher.decryptField(tampered),
                "Tampering with the ciphertext must produce an authentication failure.");
    }

    @Test
    void decryptFieldPrefixIsCaseSensitive() {
        // The marker prefix is treated as a literal — variations like "ENC:V1:" or
        // "Enc:V1:" must NOT trigger the decrypt path. Otherwise a hostile user-supplied
        // string starting with that case variant could trick the read path into trying
        // to decrypt arbitrary bytes (still safe, but a wasted error path).
        assertEquals("ENC:V1:abc", cipher.decryptField("ENC:V1:abc"));
        assertEquals("Enc:v1:abc", cipher.decryptField("Enc:v1:abc"));
    }

    // ---------- defenses against accidental leakage ----------

    @Test
    void encryptFieldOutputContainsNoPlaintextSubstring() {
        // The acceptance test for "is the field actually encrypted at rest?" — the stored
        // form must not contain the plaintext as a substring. Catches a regression where
        // the prefix is added but encryption was somehow no-op'd.
        final String pii = "Bob Smith / 555-12-3456";
        final String encrypted = cipher.encryptField(pii);
        assertFalse(encrypted.contains(pii));
        assertFalse(encrypted.contains("Bob Smith"));
        assertFalse(encrypted.contains("555-12-3456"));
    }

    @Test
    void fieldPrefixIsExposedAsAPublicConstant() {
        // The marker is part of the persisted format and is referenced by tests and by
        // tooling that may need to inspect or migrate stored data. Keep it on the public
        // surface so a future change is loudly visible.
        assertNotNull(SymmetricCipher.FIELD_PREFIX);
        assertTrue(SymmetricCipher.FIELD_PREFIX.startsWith("enc:"),
                "The marker must remain self-describing — 'enc:' identifies the family.");
    }

    // ---- binary content (queued uploads)

    @Test
    void bytesRoundTripIncludingEveryByteValue() {
        final byte[] all = new byte[256];
        for (int i = 0; i < all.length; i++) all[i] = (byte) i;
        final byte[] stored = cipher.encryptBytes(all);
        assertTrue(new String(stored, 0, 7, java.nio.charset.StandardCharsets.US_ASCII).equals(SymmetricCipher.FIELD_PREFIX));
        assertEquals(SymmetricCipher.FIELD_PREFIX.length() + 12 + all.length + 16, stored.length);
        org.junit.jupiter.api.Assertions.assertArrayEquals(all, cipher.decryptBytes(stored));
        assertFalse(java.util.Arrays.equals(stored, cipher.encryptBytes(all)), "a fresh IV each time");
    }

    @Test
    void nullAndEmptyBytesPassThrough() {
        assertNull(cipher.encryptBytes(null));
        assertNull(cipher.decryptBytes(null));
        assertEquals(0, cipher.encryptBytes(new byte[0]).length);
        assertEquals(0, cipher.decryptBytes(new byte[0]).length);
    }

    @Test
    void bytesStoredBeforeEncryptionReadAsTheyAre() {
        final byte[] legacy = "%PDF-1.7 plain".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        org.junit.jupiter.api.Assertions.assertArrayEquals(legacy, cipher.decryptBytes(legacy));
    }

    @Test
    void encryptingThisKeysCiphertextAgainEncryptsIt() {
        // #74, as for fields: an upload that is this key's ciphertext is not decrypted for whoever uploaded it.
        final byte[] stored = cipher.encryptBytes("Jane Roe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final byte[] again = cipher.encryptBytes(stored);
        assertNotEquals(stored.length, again.length);
        org.junit.jupiter.api.Assertions.assertArrayEquals(stored, cipher.decryptBytes(again));
    }

    @Test
    void contentThatOnlyLooksEncryptedIsEncrypted() {
        final byte[] lookalike = (SymmetricCipher.FIELD_PREFIX + "not really ciphertext, Jane Roe")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] stored = cipher.encryptBytes(lookalike);
        assertNotEquals(lookalike.length, stored.length);
        org.junit.jupiter.api.Assertions.assertArrayEquals(lookalike, cipher.decryptBytes(stored));
    }

    @Test
    void bytesUnderAnotherKeyFailToDecrypt() {
        final byte[] other = new byte[32];
        new SecureRandom().nextBytes(other);
        final byte[] stored = new SymmetricCipher(Base64.getEncoder().encodeToString(other))
                .encryptBytes("Jane Roe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> cipher.decryptBytes(stored));
    }
}
