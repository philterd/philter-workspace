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

import ai.philterd.workspace.model.CaseMetadata;
import ai.philterd.workspace.model.DetectionRule;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.DocumentComment;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.model.PendingUpload;
import ai.philterd.workspace.model.RedactionCertificate;
import ai.philterd.workspace.model.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MongoDB lifecycle callbacks that encrypt PII fields at rest. A save is driven as Spring Data drives it: the
 * entity is converted to BSON with a real {@link MappingMongoConverter}, then {@code onBeforeSave} encrypts that
 * BSON; a load converts BSON back and {@code onAfterConvert} decrypts it.
 */
class PiiFieldEncryptionTest {

    private SymmetricCipher cipher;
    private MappingMongoConverter converter;

    @BeforeEach
    void setUp() {
        final byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        cipher = new SymmetricCipher(Base64.getEncoder().encodeToString(key));
        // Configured as Spring Boot configures it, so java.time values are simple types.
        final org.springframework.data.mongodb.core.convert.MongoCustomConversions conversions =
                new org.springframework.data.mongodb.core.convert.MongoCustomConversions(List.of());
        final MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
    }

    /** What MongoDB would store for {@code entity}. */
    private org.bson.Document save(final Object entity) {
        final org.bson.Document stored = new org.bson.Document();
        converter.write(entity, stored);
        PiiFieldEncryption.encryptStored(cipher, entity, stored);
        return stored;
    }

    /** {@code stored} read back as an entity. */
    private <T> T load(final Class<T> type, final org.bson.Document stored) {
        final T entity = converter.read(type, stored);
        PiiFieldEncryption.decrypt(cipher, entity);
        return entity;
    }

    // ---------- saving ----------

    @Test
    void aSaveEncryptsWhatIsStoredAndLeavesTheEntityInPlaintext() {
        final Document d = new Document();
        d.setId("doc-1");
        d.setStatus("REVIEW_REQUIRED");
        d.setOriginalText("Patient: alice@example.com");
        d.setRedactedText("Patient: <<EMAIL_ADDRESS>>");
        d.setFailureMessage("LLM call failed: alice@example.com");
        final CaseMetadata metadata = new CaseMetadata();
        metadata.setCaseNumber("1:26-cv-00042");
        metadata.setFiler("Jane Roe");
        metadata.setCaseType("civil");
        d.setCaseMetadata(metadata);

        final org.bson.Document stored = save(d);

        assertEncrypted(stored.getString("originalText"), "alice@example.com");
        assertEncrypted(stored.getString("redactedText"), "EMAIL_ADDRESS");
        assertEncrypted(stored.getString("failureMessage"), "alice@example.com");
        final org.bson.Document storedMetadata = stored.get("caseMetadata", org.bson.Document.class);
        assertEncrypted(storedMetadata.getString("caseNumber"), "00042");
        assertEncrypted(storedMetadata.getString("filer"), "Jane Roe");
        assertEquals("civil", storedMetadata.getString("caseType"), "only the listed fields are encrypted");
        assertEquals("REVIEW_REQUIRED", stored.getString("status"));
        // The entity is never changed, so a failed save cannot leave it holding ciphertext (#74).
        assertEquals("Patient: alice@example.com", d.getOriginalText());
        assertEquals("Jane Roe", d.getCaseMetadata().getFiler());

        final Document loaded = load(Document.class, stored);
        assertEquals("Patient: alice@example.com", loaded.getOriginalText());
        assertEquals("Patient: <<EMAIL_ADDRESS>>", loaded.getRedactedText());
        assertEquals("1:26-cv-00042", loaded.getCaseMetadata().getCaseNumber());
    }

    @Test
    void nullAndEmptyValuesAreLeftAlone() {
        final Document d = new Document();
        d.setOriginalText("");
        final org.bson.Document stored = save(d);
        assertEquals("", stored.getString("originalText"));
        assertNull(stored.get("redactedText"));
        assertNull(load(Document.class, stored).getRedactedText());
    }

    @Test
    void everyStoredPiiFieldIsEncrypted() {
        // The paths encryptStored uses must cover every field decrypt does, or a value is stored in plaintext.
        final String secret = "S3cretValue";
        final Document d = new Document();
        d.setOriginalText(secret);
        d.setRedactedText(secret);
        d.setFailureMessage(secret);
        final CaseMetadata metadata = new CaseMetadata();
        metadata.setCaseNumber(secret);
        metadata.setFiler(secret);
        d.setCaseMetadata(metadata);
        final Span span = new Span();
        span.setText(secret);
        final GeneralSettings settings = new GeneralSettings();
        settings.setOpensearchPassword(secret);
        final DocumentComment comment = new DocumentComment();
        comment.setText(secret);
        final DetectionRule rule = new DetectionRule();
        rule.setText(secret);
        final RedactionCertificate certificate = new RedactionCertificate();
        certificate.setDocumentFilename(secret);
        final RedactionCertificate.OverturnEntry overturn = new RedactionCertificate.OverturnEntry();
        overturn.setSpanText(secret);
        certificate.setOverturns(List.of(overturn, overturn));

        for (Object entity : List.of(d, span, settings, comment, rule, certificate)) {
            final org.bson.Document stored = save(entity);
            assertFalse(stored.toJson().contains(secret), entity.getClass().getSimpleName() + ": " + stored.toJson());
            final Object loaded = load(entity.getClass(), stored);
            final org.bson.Document again = new org.bson.Document();
            converter.write(loaded, again);
            assertTrue(again.toJson().contains(secret), "read back: " + again.toJson());
        }
    }

    // ---------- values that look encrypted (#74) ----------

    @Test
    void aValueSomeoneSuppliesThatIsGenuineCiphertextIsEncryptedAndReadBackAsWritten() {
        // Ciphertext taken from a backup, posted as a comment, must not be decrypted for whoever posted it.
        final String fromBackup = cipher.encryptField("123-45-6789");
        final DocumentComment comment = new DocumentComment();
        comment.setText(fromBackup);

        final org.bson.Document stored = save(comment);
        assertNotEquals(fromBackup, stored.getString("text"), "encrypted again, not stored as it is");
        assertEquals(fromBackup, load(DocumentComment.class, stored).getText(), "and read back exactly as written");
    }

    @Test
    void anIngestedTextThatStartsWithTheMarkerIsEncryptedAndReadBackAsWritten() {
        final Document d = new Document();
        d.setOriginalText(SymmetricCipher.FIELD_PREFIX + "not ciphertext at all");

        final org.bson.Document stored = save(d);
        assertEncrypted(stored.getString("originalText"), "not ciphertext");
        assertEquals(SymmetricCipher.FIELD_PREFIX + "not ciphertext at all",
                load(Document.class, stored).getOriginalText());
    }

    @Test
    void anUploadThatIsGenuineCiphertextIsEncryptedAgain() {
        final byte[] fromBackup = cipher.encryptBytes("Jane Roe".getBytes());
        final PendingUpload upload = new PendingUpload();
        upload.setContent(fromBackup);

        final org.bson.Document stored = save(upload);
        final Object content = stored.get("content");
        final byte[] written = content instanceof org.bson.types.Binary b ? b.getData() : (byte[]) content;
        assertFalse(java.util.Arrays.equals(fromBackup, written));
        assertArrayEquals(fromBackup, cipher.decryptBytes(written));
    }

    // ---------- values that do not decrypt (#74) ----------

    @Test
    void aValueThatDoesNotDecryptReadsAsAPlaceholderAndTheRecordCannotBeSaved() {
        final DocumentComment comment = new DocumentComment();
        comment.setId("c-1");
        comment.setText("readable");
        final org.bson.Document stored = save(comment);
        // As a comment starting with the marker was stored before #74, or a value under another key.
        stored.put("text", SymmetricCipher.FIELD_PREFIX + "aGVsbG8gd29ybGQ=");

        final DocumentComment loaded = load(DocumentComment.class, stored);
        assertEquals(PiiFieldEncryption.UNDECRYPTABLE, loaded.getText());
        assertEquals("c-1", loaded.getId(), "the rest of the record still reads");

        assertThrows(PiiFieldEncryption.UndecryptableRecordException.class, () -> save(loaded));
        assertThrows(PiiFieldEncryption.UndecryptableRecordException.class,
                () -> PiiFieldEncryption.encrypt(cipher, loaded));

        // A value that is not base64 at all reads the same way.
        stored.put("text", SymmetricCipher.FIELD_PREFIX + "not base64!");
        assertEquals(PiiFieldEncryption.UNDECRYPTABLE, load(DocumentComment.class, stored).getText());
    }

    @Test
    void legacyPlaintextReadsUnchanged() {
        final org.bson.Document stored = new org.bson.Document("text", "legacy plaintext span");
        assertEquals("legacy plaintext span", load(Span.class, stored).getText());
    }

    // ---------- SQLite's path: encrypt and decrypt the entity in place ----------

    @Test
    void encryptingAndDecryptingAnEntityInPlaceRoundTrips() {
        final DocumentComment comment = new DocumentComment();
        comment.setText(SymmetricCipher.FIELD_PREFIX + "looks encrypted");
        PiiFieldEncryption.encrypt(cipher, comment);
        assertEncrypted(comment.getText(), "looks encrypted");
        PiiFieldEncryption.decrypt(cipher, comment);
        assertEquals(SymmetricCipher.FIELD_PREFIX + "looks encrypted", comment.getText());
    }

    private static void assertEncrypted(final String storedValue, final String shouldNotContain) {
        assertTrue(storedValue != null && storedValue.startsWith(SymmetricCipher.FIELD_PREFIX),
                "Expected ciphertext-with-prefix; got: " + storedValue);
        assertFalse(storedValue.contains(shouldNotContain),
                "Ciphertext must not leak the plaintext substring '" + shouldNotContain + "'");
    }
}
