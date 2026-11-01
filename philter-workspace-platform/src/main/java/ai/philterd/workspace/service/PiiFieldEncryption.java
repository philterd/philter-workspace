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

import ai.philterd.workspace.model.AuditLog;
import ai.philterd.workspace.model.BackgroundJob;
import ai.philterd.workspace.model.DataImportLogEntry;
import ai.philterd.workspace.model.DetectionRule;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.DocumentComment;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.model.NotificationSettings;
import ai.philterd.workspace.model.PendingUpload;
import ai.philterd.workspace.model.RedactionCertificate;
import ai.philterd.workspace.model.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertCallback;
import org.springframework.data.mongodb.core.mapping.event.BeforeSaveCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Field-level encryption for PII-bearing values stored in MongoDB. The classes in this
 * file plug into Spring Data Mongo's lifecycle callbacks so encryption and decryption
 * happen transparently around every {@code save} and {@code find} — call sites continue
 * to read and write plaintext on the in-memory entities.
 *
 * <h3>What gets encrypted</h3>
 * <ul>
 *     <li>{@link Document#getOriginalText() Document.originalText} — the source text of
 *         the document being redacted; carries the raw PII.</li>
 *     <li>{@link Document#getRedactedText() Document.redactedText} — the rendered output
 *         persisted at finalize time. Contains the surrounding context of redactions and
 *         is treated as sensitive.</li>
 *     <li>{@link Document#getFailureMessage() Document.failureMessage} — error messages
 *         can quote spans of the source document.</li>
 *     <li>{@link Document#getCaseMetadata() Document.caseMetadata}'s case number and filer, which can
 *         identify the people in a case (#16). Its case type, fee status, and IFP flag are not encrypted,
 *         so they can be matched and shown.</li>
 *     <li>{@link Span#getText() Span.text} — the literal PII string that was detected.</li>
 *     <li>{@link DocumentComment#getText() DocumentComment.text} — reviewer-supplied free
 *         text that may reference PII while documenting a decision.</li>
 *     <li>{@link PendingUpload#getContent() PendingUpload.content}: the bytes of an upload
 *         waiting in the ingest queue, with {@link SymmetricCipher#encryptBytes}.</li>
 *     <li>{@link DetectionRule#getText() DetectionRule.text}: a value reviewers decided Philter
 *         should never, or always, flag.</li>
 *     <li>{@link NotificationSettings#getPassword() NotificationSettings.password}: the SMTP password (#77).</li>
 *     <li>An {@link AuditLog} entry's {@code reason} detail, such as the reason for overturning another
 *         reviewer's decision, and each {@link RedactionCertificate.OverturnEntry#getReason() overturn's reason}
 *         on a certificate (#77).</li>
 *     <li>{@link BackgroundJob#getErrorMessage() BackgroundJob.errorMessage} and its
 *         {@link BackgroundJob#getFailureMessages() failureMessages}, and a
 *         {@link DataImportLogEntry#getMessage() DataImportLogEntry.message}: exception text that can quote
 *         values from the source (#77).</li>
 * </ul>
 *
 * <h3>How it works</h3>
 * <p>{@link #encrypt} and {@link #decrypt} hold the field list and are store-neutral. The SQLite
 * store encrypts the entity, serializes it, and decrypts it again in a {@code finally}. For
 * MongoDB, the callback classes below use Spring Data's lifecycle callbacks:
 * <ul>
 *     <li><strong>BeforeSaveCallback</strong> encrypts the fields in the BSON document about to
 *         be written ({@link #encryptStored}), so the caller's entity never holds ciphertext, even
 *         when the save fails.</li>
 *     <li><strong>AfterConvertCallback</strong> decrypts every entity read back.</li>
 * </ul>
 * Neither store ever passes ciphertext to {@link SymmetricCipher#encryptField}, which encrypts every
 * value it is given, so a value someone supplies that looks encrypted is encrypted like any other (#74).
 *
 * <h3>Values that do not decrypt</h3>
 * <p>A stored value with the marker that does not decrypt (a different key, tampering, or a value
 * stored as it was before #74) reads as {@link #UNDECRYPTABLE}, and a warning names the record, never
 * the value. One such value no longer makes the list it belongs to unreadable. A record holding
 * {@link #UNDECRYPTABLE} cannot be saved ({@link UndecryptableRecordException}), so the stored value is
 * never overwritten.
 *
 * <h3>Backwards compatibility</h3>
 * <p>Pre-existing rows that were written before field encryption was switched on do not
 * carry the {@link SymmetricCipher#FIELD_PREFIX enc:v1:} marker.
 * {@link SymmetricCipher#decryptField(String)} returns those values unchanged, so they
 * continue to read normally. The first save of such a row promotes the field to its
 * encrypted form transparently.
 */
public final class PiiFieldEncryption {

    private static final Logger LOGGER = LoggerFactory.getLogger(PiiFieldEncryption.class);

    /** What a field reads as when its stored value does not decrypt (#74). */
    public static final String UNDECRYPTABLE = "[This value could not be decrypted]";

    /** Refuses to save a record that holds {@link #UNDECRYPTABLE}, which would overwrite the value it stands for. */
    public static final class UndecryptableRecordException extends IllegalStateException {
        public UndecryptableRecordException(final Object entity) {
            super("This " + entity.getClass().getSimpleName() + " cannot be saved: one of its values could not be"
                    + " decrypted, and saving would overwrite it.");
        }
    }

    /**
     * The encrypted fields as paths in the stored document, for {@link #encryptStored}. {@code name[]} is each
     * element of a list. They are the fields {@link #apply} lists, as the stores name them.
     */
    static final Map<Class<?>, List<String>> STORED_PATHS = Map.of(
            Document.class, List.of("originalText", "redactedText", "failureMessage",
                    "caseMetadata.caseNumber", "caseMetadata.filer"),
            Span.class, List.of("text"),
            GeneralSettings.class, List.of("opensearchPassword"),
            DocumentComment.class, List.of("text"),
            DetectionRule.class, List.of("text"),
            RedactionCertificate.class, List.of("documentFilename", "overturns[].spanText", "overturns[].reason"),
            NotificationSettings.class, List.of("password"),
            AuditLog.class, List.of("details.reason"),
            BackgroundJob.class, List.of("errorMessage", "failureMessages[]"),
            DataImportLogEntry.class, List.of("message"));

    private PiiFieldEncryption() {}

    /**
     * Encrypt the PII fields of {@code entity} in place. Entities with no PII fields are left
     * alone. Every store calls this before writing an entity.
     */
    public static void encrypt(final SymmetricCipher cipher, final Object entity) {
        if (entity instanceof PendingUpload pu) {
            pu.setContent(cipher.encryptBytes(pu.getContent()));
            return;
        }
        refuseUndecryptable(entity);
        apply(entity, cipher::encryptField);
    }

    /**
     * Decrypt the PII fields of {@code entity} in place. Every store calls this after reading or writing an entity.
     * A field that does not decrypt reads as {@link #UNDECRYPTABLE}; an upload's content that does not decrypt
     * still fails, so its ingest fails rather than redacting a placeholder.
     */
    public static void decrypt(final SymmetricCipher cipher, final Object entity) {
        if (entity instanceof PendingUpload pu) {
            pu.setContent(cipher.decryptBytes(pu.getContent()));
            return;
        }
        apply(entity, stored -> {
            try {
                return cipher.decryptField(stored);
            } catch (IllegalStateException | IllegalArgumentException e) {
                // The record, never the value: it may be someone's input.
                LOGGER.warn("A value in {} {} could not be decrypted; it reads as a placeholder and the record cannot"
                        + " be saved.", entity.getClass().getSimpleName(), idOf(entity));
                return UNDECRYPTABLE;
            }
        });
    }

    /**
     * Whether a field of {@code entity} read as {@link #UNDECRYPTABLE}. Anything that copies a record's values out of
     * Philter Workspace, such as an export or a delivery, checks this first, so the placeholder is never written out.
     */
    public static boolean holdsUndecryptable(final Object entity) {
        final boolean[] found = {false};
        apply(entity, value -> {
            found[0] |= UNDECRYPTABLE.equals(value);
            return value;
        });
        return found[0];
    }

    /** Throws {@link UndecryptableRecordException} when {@code entity} holds {@link #UNDECRYPTABLE}. */
    private static void refuseUndecryptable(final Object entity) {
        apply(entity, value -> {
            if (UNDECRYPTABLE.equals(value)) {
                throw new UndecryptableRecordException(entity);
            }
            return value;
        });
    }

    private static String idOf(final Object entity) {
        if (entity instanceof Document d) return d.getId();
        if (entity instanceof Span sp) return sp.getId();
        if (entity instanceof DocumentComment c) return c.getId();
        if (entity instanceof DetectionRule r) return r.getId();
        if (entity instanceof RedactionCertificate rc) return rc.getId();
        if (entity instanceof GeneralSettings g) return g.getId();
        if (entity instanceof NotificationSettings n) return n.getId();
        if (entity instanceof AuditLog a) return a.getId();
        if (entity instanceof BackgroundJob j) return j.getId();
        if (entity instanceof DataImportLogEntry e) return e.getId();
        return "";
    }

    /**
     * Encrypt the PII fields of {@code stored}, the MongoDB document about to be written for an entity of
     * {@code type}, leaving the entity itself in plaintext. Refuses an entity holding {@link #UNDECRYPTABLE}.
     */
    public static void encryptStored(final SymmetricCipher cipher, final Object entity,
                                     final org.bson.Document stored) {
        if (entity instanceof PendingUpload) {
            final Object content = stored.get("content");
            if (content instanceof org.bson.types.Binary binary) {
                stored.put("content", new org.bson.types.Binary(cipher.encryptBytes(binary.getData())));
            } else if (content instanceof byte[] bytes) {
                stored.put("content", cipher.encryptBytes(bytes));
            }
            return;
        }
        final List<String> paths = STORED_PATHS.get(entity.getClass());
        if (paths == null) return;
        refuseUndecryptable(entity);
        for (String path : paths) {
            encryptPath(cipher, stored, path.split("\\."), 0);
        }
    }

    private static void encryptPath(final SymmetricCipher cipher, final org.bson.Document node, final String[] path,
                                    final int i) {
        final boolean each = path[i].endsWith("[]");
        final String key = each ? path[i].substring(0, path[i].length() - 2) : path[i];
        final Object value = node.get(key);
        if (i == path.length - 1) {
            if (value instanceof String text) {
                node.put(key, cipher.encryptField(text));
            } else if (each && value instanceof List<?> list) {
                node.put(key, list.stream().map(v -> v instanceof String text ? cipher.encryptField(text) : v)
                        .toList());
            }
        } else if (each && value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof org.bson.Document child) {
                    encryptPath(cipher, child, path, i + 1);
                }
            }
        } else if (value instanceof org.bson.Document child) {
            encryptPath(cipher, child, path, i + 1);
        }
    }

    private static void apply(final Object entity, final UnaryOperator<String> f) {
        if (entity instanceof Document d) {
            d.setOriginalText(f.apply(d.getOriginalText()));
            d.setRedactedText(f.apply(d.getRedactedText()));
            d.setFailureMessage(f.apply(d.getFailureMessage()));
            if (d.getCaseMetadata() != null) {
                d.getCaseMetadata().setCaseNumber(f.apply(d.getCaseMetadata().getCaseNumber()));
                d.getCaseMetadata().setFiler(f.apply(d.getCaseMetadata().getFiler()));
            }
        } else if (entity instanceof Span sp) {
            sp.setText(f.apply(sp.getText()));
        } else if (entity instanceof GeneralSettings g) {
            g.setOpensearchPassword(f.apply(g.getOpensearchPassword()));
        } else if (entity instanceof DocumentComment c) {
            c.setText(f.apply(c.getText()));
        } else if (entity instanceof DetectionRule r) {
            r.setText(f.apply(r.getText()));
        } else if (entity instanceof RedactionCertificate rc) {
            rc.setDocumentFilename(f.apply(rc.getDocumentFilename()));
            if (rc.getOverturns() != null) {
                for (RedactionCertificate.OverturnEntry o : rc.getOverturns()) {
                    o.setSpanText(f.apply(o.getSpanText()));
                    o.setReason(f.apply(o.getReason()));
                }
            }
        } else if (entity instanceof NotificationSettings n) {
            n.setPassword(f.apply(n.getPassword()));
        } else if (entity instanceof AuditLog a) {
            // Only a string reason: free text a reviewer typed, such as why they overturned a decision.
            if (a.getDetails() != null && a.getDetails().get("reason") instanceof String reason) {
                a.getDetails().put("reason", f.apply(reason));
            }
        } else if (entity instanceof BackgroundJob j) {
            j.setErrorMessage(f.apply(j.getErrorMessage()));
            if (j.getFailureMessages() != null) {
                j.setFailureMessages(new java.util.ArrayList<>(j.getFailureMessages().stream().map(f).toList()));
            }
        } else if (entity instanceof DataImportLogEntry e) {
            e.setMessage(f.apply(e.getMessage()));
        }
    }

    /**
     * MongoDB lifecycle callbacks: encrypt the BSON document about to be written
     * ({@link #encryptStored}), leaving the caller's entity in plaintext, and decrypt every
     * entity read back ({@link #decrypt}).
     */
    abstract static class MongoCallbacks<T> implements BeforeSaveCallback<T>, AfterConvertCallback<T> {

        private final SymmetricCipher cipher;

        MongoCallbacks(final SymmetricCipher cipher) {
            this.cipher = cipher;
        }

        @Override
        public T onBeforeSave(final T entity, final org.bson.Document document, final String collection) {
            encryptStored(cipher, entity, document);
            return entity;
        }

        @Override
        public T onAfterConvert(final T entity, final org.bson.Document document, final String collection) {
            decrypt(cipher, entity);
            return entity;
        }
    }

    /** Lifecycle callbacks that wrap Document save/load with PII encryption. */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class DocumentCallbacks extends MongoCallbacks<Document> {

        public DocumentCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /** Lifecycle callbacks that wrap PendingUpload save/load: the queued upload's bytes. */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class PendingUploadCallbacks extends MongoCallbacks<PendingUpload> {

        public PendingUploadCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /** Lifecycle callbacks that wrap Span save/load with PII encryption. */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class SpanCallbacks extends MongoCallbacks<Span> {

        public SpanCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /**
     * Lifecycle callbacks that encrypt the OpenSearch basic-auth password on the
     * {@link GeneralSettings} singleton row. Not strictly PII, but the same secret-at-rest
     * machinery applies — the password is treated like a credential and goes through the
     * same {@code enc:v1:} marker-prefix scheme.
     */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class GeneralSettingsCallbacks extends MongoCallbacks<GeneralSettings> {

        public GeneralSettingsCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /** Lifecycle callbacks that wrap DocumentComment save/load with PII encryption. */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class DocumentCommentCallbacks extends MongoCallbacks<DocumentComment> {

        public DocumentCommentCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /** Lifecycle callbacks that wrap DetectionRule save/load: the rule's value. */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class DetectionRuleCallbacks extends MongoCallbacks<DetectionRule> {

        public DetectionRuleCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /**
     * Lifecycle callbacks that wrap {@link RedactionCertificate} save/load with PII
     * encryption. The certificate denormalises two PII-bearing fields out of the
     * Document / Span tables — the document filename (often patient names, MRN-style
     * identifiers, etc.) and the literal span text on each overturn entry. Without
     * this callback those fields land in the {@code redaction_certificates} collection
     * as plaintext while their source tables are encrypted, defeating the at-rest
     * envelope for the denormalised copy. R2-F6 fix.
     */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class RedactionCertificateCallbacks extends MongoCallbacks<RedactionCertificate> {

        public RedactionCertificateCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /** Lifecycle callbacks for the SMTP password in {@link NotificationSettings} (#77). */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class NotificationSettingsCallbacks extends MongoCallbacks<NotificationSettings> {

        public NotificationSettingsCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /** Lifecycle callbacks for an {@link AuditLog} entry's {@code reason} detail (#77). */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class AuditLogCallbacks extends MongoCallbacks<AuditLog> {

        public AuditLogCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /** Lifecycle callbacks for a {@link BackgroundJob}'s error and failure messages (#77). */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class BackgroundJobCallbacks extends MongoCallbacks<BackgroundJob> {

        public BackgroundJobCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }

    /** Lifecycle callbacks for a {@link DataImportLogEntry}'s message (#77). */
    @Component
    @ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
    public static class DataImportLogEntryCallbacks extends MongoCallbacks<DataImportLogEntry> {

        public DataImportLogEntryCallbacks(final SymmetricCipher cipher) {
            super(cipher);
        }
    }
}
