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
import ai.philterd.workspace.model.LocalDirectoryDestination;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.LocalDirectoryDestinationRepository;
import ai.philterd.workspace.repository.S3DestinationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Delivering finalized output to a batch's destination (#24). */
class DocumentDeliveryServiceTest {

    @TempDir
    Path dir;

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final BatchRepository batches = mock(BatchRepository.class);
    private final LocalDirectoryDestinationRepository locals = mock(LocalDirectoryDestinationRepository.class);
    private final S3DestinationRepository s3s = mock(S3DestinationRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-31T12:00:00Z"));
    private DocumentDeliveryService service;
    private Batch batch;
    private Document document;
    private LocalDirectoryDestination destination;

    @BeforeEach
    void setUp() throws Exception {
        final Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(final java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        service = new DocumentDeliveryService(documents, batches, locals, s3s,
                new DestinationWriter(mock(SymmetricCipher.class), new LocalDirectoryRoots(System.getProperty("java.io.tmpdir"))), audit, clock);
        when(audit.hashForAudit(anyString())).thenAnswer(inv -> "hash:" + inv.getArgument(0).toString().length());

        destination = new LocalDirectoryDestination();
        destination.setId("l1");
        destination.setName("staging");
        destination.setDirectoryPath(Files.createDirectory(dir.resolve("out")).toString());
        when(locals.findById("l1")).thenReturn(Optional.of(destination));

        batch = new Batch();
        batch.setId("b1");
        batch.setDestinationType(DocumentDeliveryService.TYPE_LOCAL);
        batch.setDestinationId("l1");
        when(batches.findById("b1")).thenReturn(Optional.of(batch));

        document = new Document();
        document.setId("d1");
        document.setBatchId("b1");
        document.setFilename("Roe v. Doe motion.pdf");
        document.setOriginalText("Jane Roe, SSN 123-45-6789, filed.");
        document.setRedactedText("<<PERSON>>, SSN <<SSN>>, filed.");
        when(documents.findByDeliveryStatus(DocumentDeliveryService.STATUS_PENDING))
                .thenAnswer(inv -> DocumentDeliveryService.STATUS_PENDING.equals(document.getDeliveryStatus())
                        ? List.of(document) : List.of());
    }

    private Map<String, Object> lastAudit(final String outcome) {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).logForUser(any(), eq("DOCUMENT_DELIVER"), eq("Document"),
                eq("d1"), eq(outcome), details.capture());
        return details.getValue();
    }

    @Test
    void aBatchWithoutADestinationQueuesNothing() {
        assertFalse(service.queue(document, new Batch(), "lead@example.com"));
        assertFalse(service.queue(document, null, "lead@example.com"));
        assertNull(document.getDeliveryStatus());
    }

    @Test
    void theRedactedTextIsWrittenAndAuditedAndTheOriginalNeverIs() throws Exception {
        assertTrue(service.queue(document, batch, "lead@example.com"));
        assertEquals("PENDING", document.getDeliveryStatus());
        assertEquals("Local directory: staging", document.getDeliveryDestination());

        service.deliverDue();

        final Path written = dir.resolve("out/d1/Roe v. Doe motion_redacted.txt");
        assertEquals("<<PERSON>>, SSN <<SSN>>, filed.", Files.readString(written));
        try (Stream<Path> all = Files.walk(dir.resolve("out"))) {
            for (Path f : all.filter(Files::isRegularFile).toList()) {
                final String content = Files.readString(f);
                assertFalse(content.contains("Jane Roe") || content.contains("123-45-6789"), f.toString());
            }
        }
        assertEquals("DELIVERED", document.getDeliveryStatus());
        assertEquals(now.get(), document.getDeliveredAt());
        assertEquals(1, document.getDeliveryAttempts());

        final Map<String, Object> details = lastAudit(AuditLogService.OUTCOME_SUCCESS);
        assertEquals("LOCAL", details.get("destinationType"));
        assertEquals("l1", details.get("destinationId"));
        assertEquals("staging", details.get("destinationName"));
        assertEquals("hash:" + "d1/Roe v. Doe motion_redacted.txt".length(), details.get("filenameHash"));
        final byte[] bytes = "<<PERSON>>, SSN <<SSN>>, filed.".getBytes(StandardCharsets.UTF_8);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                details.get("sha256"));
        assertEquals(bytes.length, details.get("bytes"));
        verify(audit).logForUser(eq("lead@example.com"), eq("DOCUMENT_DELIVER"), any(), any(), any(), anyMap());
    }

    @Test
    void aDocumentWithAValueThatDidNotDecryptIsNotDelivered() throws Exception {
        // #74: its output could be the placeholder, and it cannot be saved to record an attempt.
        assertTrue(service.queue(document, batch, "lead@example.com"));
        document.setRedactedText(PiiFieldEncryption.UNDECRYPTABLE);
        org.mockito.Mockito.clearInvocations(documents);

        service.deliverDue();

        try (Stream<Path> all = Files.walk(dir)) {
            assertTrue(all.filter(Files::isRegularFile).noneMatch(f -> f.toString().contains("_redacted")),
                    "nothing is written");
        }
        assertEquals("PENDING", document.getDeliveryStatus());
        verify(documents, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void aFailedWriteIsRetriedWithGrowingDelaysThenReportedAndTheOutputIsKept() throws Exception {
        destination.setDirectoryPath(dir.resolve("missing").toString());
        service.queue(document, batch, "lead@example.com");

        service.deliverDue();
        assertEquals("PENDING", document.getDeliveryStatus());
        assertEquals(1, document.getDeliveryAttempts());
        assertTrue(document.getDeliveryError().startsWith("Directory does not exist"), document.getDeliveryError());
        assertEquals(now.get().plus(Duration.ofMinutes(1)), document.getDeliveryNextAttemptAt());

        service.deliverDue();
        assertEquals(1, document.getDeliveryAttempts(), "not due yet");

        for (Duration delay : DocumentDeliveryService.RETRY_DELAYS) {
            now.set(document.getDeliveryNextAttemptAt());
            service.deliverDue();
        }
        assertEquals(DocumentDeliveryService.MAX_ATTEMPTS, document.getDeliveryAttempts());
        assertEquals("FAILED", document.getDeliveryStatus());
        assertNull(document.getDeliveryNextAttemptAt());
        assertEquals("<<PERSON>>, SSN <<SSN>>, filed.", document.getRedactedText(), "the finalized output is kept");
        assertEquals(true, lastAudit(AuditLogService.OUTCOME_FAILURE).get("finalAttempt"));
        verify(audit, times(4)).logForUser(any(), eq("DOCUMENT_DELIVER"), any(), any(),
                eq(AuditLogService.OUTCOME_FAILURE), anyMap());

        now.set(now.get().plus(Duration.ofHours(1)));
        service.deliverDue();
        assertEquals(DocumentDeliveryService.MAX_ATTEMPTS, document.getDeliveryAttempts(), "no more attempts");

        // Fixed and retried by hand: a fresh set of attempts, which succeeds.
        destination.setDirectoryPath(dir.resolve("out").toString());
        assertNull(service.retry(document, "admin@example.com"));
        assertEquals("PENDING", document.getDeliveryStatus());
        assertEquals(0, document.getDeliveryAttempts());
        service.deliverDue();
        assertEquals("DELIVERED", document.getDeliveryStatus());
        assertNull(document.getDeliveryError());
        verify(audit).logForUser(eq("admin@example.com"), eq("DOCUMENT_DELIVERY_RETRY"), any(), eq("d1"), any(),
                anyMap());
    }

    @Test
    void withoutFinalizedOutputNothingIsWrittenAndTheOriginalIsNotUsedInstead() throws Exception {
        document.setRedactedText(null);
        service.queue(document, batch, null);
        service.deliverDue();

        assertEquals("FAILED", document.getDeliveryStatus(), "no point retrying");
        assertEquals("The document has no finalized output to deliver.", document.getDeliveryError());
        try (Stream<Path> all = Files.list(dir.resolve("out"))) {
            assertEquals(0, all.count());
        }
    }

    @Test
    void aRemovedDestinationFailsAtOnce() {
        service.queue(document, batch, null);
        when(locals.findById("l1")).thenReturn(Optional.empty());
        service.deliverDue();

        assertEquals("FAILED", document.getDeliveryStatus());
        assertEquals(1, document.getDeliveryAttempts());
        assertEquals("The batch has no destination, or it was removed.", document.getDeliveryError());
    }

    @Test
    void onlyAFailedDeliveryCanBeRetriedAndOnlyToADestination() {
        assertEquals("Only a failed delivery can be retried.", service.retry(document, "a@example.com"));
        document.setDeliveryStatus("FAILED");
        batch.setDestinationType(null);
        batch.setDestinationId(null);
        assertEquals("The document's batch has no destination.", service.retry(document, "a@example.com"));
        verify(documents, never()).save(any());
    }

    @Test
    void theDeliveredNameIsOneSafePathSegmentUnderTheDocumentId() {
        final Document d = new Document();
        d.setId("d9");
        d.setFilename("../../etc/pass:wd.txt");
        assertEquals("d9/pass_wd_redacted.txt", DocumentDeliveryService.deliveredFilename(d));
        d.setFilename("C:\\filings\\.hidden");
        assertEquals("d9/hidden_redacted.txt", DocumentDeliveryService.deliveredFilename(d));
        d.setFilename(null);
        assertEquals("d9/document_redacted.txt", DocumentDeliveryService.deliveredFilename(d));
        d.setFilename("..");
        assertEquals("d9/document_redacted.txt", DocumentDeliveryService.deliveredFilename(d));
    }
}
