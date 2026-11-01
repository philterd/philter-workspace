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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM cipher for encrypting at-rest secrets such as data-source credentials.
 * The AES-256 key is loaded from {@code philter-workspace.crypto.secret},
 * which must be a <strong>base64-encoded value of exactly 32 random bytes</strong>.
 *
 * <p>Generate a key with one of:
 * <pre>
 *   openssl rand -base64 32
 *   head -c 32 /dev/urandom | base64
 * </pre>
 *
 * <p>Anything else — an unset property, a passphrase, base64 of the wrong length —
 * fails fast at bean construction with a descriptive message. The application will not
 * start until a properly-formed key is provided. This rules out two prior failure
 * modes: silently using a public deterministic dev key when the property is missing,
 * and using a low-entropy passphrase that can be brute-forced from a leaked database.
 *
 * <p>Output format for {@link #encrypt(String)} is {@code base64(iv || ciphertext+tag)}:
 * a 12-byte random IV concatenated with the GCM ciphertext (which already includes its
 * 16-byte auth tag).
 */
@Service
public class SymmetricCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    /**
     * Marker prefix on PII-bearing fields that have been encrypted at rest. Stored on the
     * value itself so the read path can transparently distinguish a ciphertext blob from
     * a legacy plaintext value written before encryption was switched on. The {@code v1}
     * version segment leaves room for a future re-keying or algorithm upgrade.
     */
    public static final String FIELD_PREFIX = "enc:v1:";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SymmetricCipher(@Value("${philter-workspace.crypto.secret:}") final String configured) {
        // Validation lives in CryptoSecretLoader; the typed CryptoSecretConfigurationException it throws
        // is caught by CryptoSecretFailureAnalyzer to render the startup banner instead
        // of a stack trace.
        this.key = new SecretKeySpec(CryptoSecretLoader.load(configured), "AES");
    }

    public String encrypt(final String plaintext) {
        if (plaintext == null) return null;
        try {
            final byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            final Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            final byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            final ByteBuffer bb = ByteBuffer.allocate(iv.length + ct.length);
            bb.put(iv).put(ct);
            return Base64.getEncoder().encodeToString(bb.array());
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(final String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        try {
            final byte[] in = Base64.getDecoder().decode(ciphertext);
            if (in.length <= IV_BYTES) {
                throw new IllegalStateException("Ciphertext too short");
            }
            final byte[] iv = new byte[IV_BYTES];
            final byte[] ct = new byte[in.length - IV_BYTES];
            System.arraycopy(in, 0, iv, 0, IV_BYTES);
            System.arraycopy(in, IV_BYTES, ct, 0, ct.length);
            final Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    /**
     * Encrypt a free-form PII-bearing field for at-rest storage. The output carries a
     * {@link #FIELD_PREFIX} marker so {@link #decryptField(String)} can distinguish a
     * value written by the encrypted path from a legacy plaintext value written before
     * field encryption was turned on. Returns the input unchanged when it is null or
     * empty (an empty value carries no PII to protect, and serializing it as ciphertext
     * just makes the database harder to reason about).
     *
     * <p>Any other value is encrypted, whatever it starts with (#74). A value is never taken to be encrypted
     * already because it carries the marker: the value may be someone's input, and storing it as it is would have
     * this key decrypt it for them on the next read. The stores encrypt a copy of what they write, so the values
     * passed here are always plaintext.
     */
    public String encryptField(final String plaintext) {
        if (plaintext == null) return null;
        if (plaintext.isEmpty()) return plaintext;
        return FIELD_PREFIX + encrypt(plaintext);
    }

    /**
     * Decrypt a field value previously written with {@link #encryptField(String)}. Values
     * without the {@link #FIELD_PREFIX} marker are returned as-is so legacy plaintext rows
     * (and the empty / null cases) keep working transparently. This is what makes the
     * roll-out backwards compatible: existing data continues to read correctly while
     * newly-written rows are encrypted.
     */
    public String decryptField(final String stored) {
        if (stored == null) return null;
        if (!stored.startsWith(FIELD_PREFIX)) return stored;
        return decrypt(stored.substring(FIELD_PREFIX.length()));
    }

    private static final byte[] BYTES_PREFIX = FIELD_PREFIX.getBytes(StandardCharsets.US_ASCII);

    /**
     * Encrypt binary content, such as an uploaded file, for at-rest storage: the
     * {@link #FIELD_PREFIX} bytes, then the IV and the GCM ciphertext, as raw bytes. Null and
     * empty input are returned unchanged. Any other input is encrypted, even one that is already this key's
     * ciphertext, for the reason {@link #encryptField(String)} gives (#74).
     */
    public byte[] encryptBytes(final byte[] plaintext) {
        if (plaintext == null || plaintext.length == 0) return plaintext;
        try {
            final byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            final Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            final byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(BYTES_PREFIX.length + iv.length + ct.length)
                    .put(BYTES_PREFIX).put(iv).put(ct).array();
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypt content written by {@link #encryptBytes(byte[])}. Content without the marker
     * was stored before encryption and is returned as-is. Content with the marker that does not
     * decrypt (a different key, or tampering) fails, as {@link #decryptField(String)} does.
     */
    public byte[] decryptBytes(final byte[] stored) {
        if (stored == null || !hasBytesPrefix(stored)) return stored;
        final byte[] plaintext = tryDecryptBytes(stored);
        if (plaintext == null) {
            throw new IllegalStateException("Decryption failed");
        }
        return plaintext;
    }

    private static boolean hasBytesPrefix(final byte[] value) {
        return value.length >= BYTES_PREFIX.length
                && java.util.Arrays.equals(value, 0, BYTES_PREFIX.length, BYTES_PREFIX, 0, BYTES_PREFIX.length);
    }

    /** The plaintext of {@code stored} under this key, or null when it is not this key's ciphertext. */
    private byte[] tryDecryptBytes(final byte[] stored) {
        final int offset = BYTES_PREFIX.length;
        if (stored.length <= offset + IV_BYTES) return null;
        try {
            final Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, offset, IV_BYTES));
            return c.doFinal(stored, offset + IV_BYTES, stored.length - offset - IV_BYTES);
        } catch (java.security.GeneralSecurityException e) {
            return null;
        }
    }

    /**
     * A separate AES-256 key for one purpose, derived from the configured secret with HMAC-SHA-256 over the purpose,
     * so a key used elsewhere (such as for the embedded search index's files) is never the field-encryption key.
     */
    public SecretKey deriveKey(final String purpose) {
        try {
            final javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal(purpose.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "AES");
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
