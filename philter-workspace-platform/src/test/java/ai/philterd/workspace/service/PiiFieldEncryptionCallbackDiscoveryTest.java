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

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Span;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.mapping.callback.EntityCallbacks;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertCallback;
import org.springframework.data.mongodb.core.mapping.event.BeforeSaveCallback;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MongoDB finds the encryption callbacks by their generic entity type. This checks that
 * Spring Data resolves each one to its entity, as it does when saving and loading.
 */
class PiiFieldEncryptionCallbackDiscoveryTest {

    @Test
    void mongoCallbacksAreFoundForTheirEntityOnly() {
        final SymmetricCipher cipher = new SymmetricCipher(Base64.getEncoder().encodeToString(new byte[32]));
        final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("documentCallbacks", new PiiFieldEncryption.DocumentCallbacks(cipher));
        beans.registerSingleton("spanCallbacks", new PiiFieldEncryption.SpanCallbacks(cipher));
        final EntityCallbacks callbacks = EntityCallbacks.create(beans);

        // Saving encrypts the BSON being written (#74), leaving the entity in plaintext.
        final Document d = new Document();
        d.setOriginalText("SSN 123-45-6789");
        final org.bson.Document stored = new org.bson.Document("originalText", d.getOriginalText());
        callbacks.callback(BeforeSaveCallback.class, d, stored, "documents");
        assertTrue(stored.getString("originalText").startsWith(SymmetricCipher.FIELD_PREFIX));
        assertEquals("SSN 123-45-6789", d.getOriginalText());

        final Document read = new Document();
        read.setOriginalText(stored.getString("originalText"));
        final Document loaded = callbacks.callback(AfterConvertCallback.class, read, stored, "documents");
        assertEquals("SSN 123-45-6789", loaded.getOriginalText());

        final Span s = new Span();
        s.setText("123-45-6789");
        final org.bson.Document storedSpan = new org.bson.Document("text", s.getText());
        callbacks.callback(BeforeSaveCallback.class, s, storedSpan, "spans");
        assertTrue(storedSpan.getString("text").startsWith(SymmetricCipher.FIELD_PREFIX));

        final Batch b = new Batch();
        b.setName("unchanged");
        final org.bson.Document storedBatch = new org.bson.Document("name", "unchanged");
        callbacks.callback(BeforeSaveCallback.class, b, storedBatch, "batches");
        assertEquals("unchanged", storedBatch.getString("name"));
    }
}
