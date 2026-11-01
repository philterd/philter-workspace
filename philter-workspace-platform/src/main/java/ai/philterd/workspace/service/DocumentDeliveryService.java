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
import ai.philterd.workspace.model.S3Destination;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.LocalDirectoryDestinationRepository;
import ai.philterd.workspace.repository.S3DestinationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers a finalized document's redacted output to its batch's destination (#24).
 *
 * <p>Finalizing queues the delivery on the document; {@link #deliverDue()} writes it shortly after, and a failed write
 * is retried with a growing delay until {@link #MAX_ATTEMPTS} attempts have failed, when the document is marked
 * FAILED with the reason. The finalized output stays on the document throughout, so a failed delivery can be retried
 * and the document can still be downloaded. Only the redacted text is written: never the original document, its
 * spans, or the certificate of redaction, which records span text.
 */
@Service
public class DocumentDeliveryService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentDeliveryService.class);

    public static final String TYPE_LOCAL = "LOCAL";
    public static final String TYPE_S3 = "S3";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_FAILED = "FAILED";

    public static final int MAX_ATTEMPTS = 4;
    /** The delay before each retry: the second attempt waits the first entry, and so on. */
    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15));

    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final LocalDirectoryDestinationRepository localRepository;
    private final S3DestinationRepository s3Repository;
    private final DestinationWriter writer;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentDeliveryService(final DocumentRepository documentRepository, final BatchRepository batchRepository,
                                   final LocalDirectoryDestinationRepository localRepository,
                                   final S3DestinationRepository s3Repository, final DestinationWriter writer,
                                   final AuditLogService auditLogService) {
        this(documentRepository, batchRepository, localRepository, s3Repository, writer, auditLogService,
                Clock.systemUTC());
    }

    DocumentDeliveryService(final DocumentRepository documentRepository, final BatchRepository batchRepository,
                            final LocalDirectoryDestinationRepository localRepository,
                            final S3DestinationRepository s3Repository, final DestinationWriter writer,
                            final AuditLogService auditLogService, final Clock clock) {
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.localRepository = localRepository;
        this.s3Repository = s3Repository;
        this.writer = writer;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /** A destination a batch can choose: {@code value} is {@code TYPE:id}. */
    public record DestinationOption(String value, String label) {
    }

    /** Every configured destination, local directories first, each sorted by name. */
    public List<DestinationOption> options() {
        final List<DestinationOption> out = new ArrayList<>();
        for (LocalDirectoryDestination d : localRepository.findAll(PageRequest.of(0, 500, Sort.by("name")))) {
            out.add(new DestinationOption(TYPE_LOCAL + ":" + d.getId(), label(TYPE_LOCAL, d.getName())));
        }
        for (S3Destination d : s3Repository.findAll(PageRequest.of(0, 500, Sort.by("name")))) {
            out.add(new DestinationOption(TYPE_S3 + ":" + d.getId(), label(TYPE_S3, d.getName())));
        }
        return out;
    }

    /** The batch's destination as "type: name", or null when it has none or the destination no longer exists. */
    public String describe(final Batch batch) {
        if (batch == null || batch.getDestinationType() == null || batch.getDestinationId() == null) return null;
        final String name = destinationName(batch.getDestinationType(), batch.getDestinationId());
        return name == null ? null : label(batch.getDestinationType(), name);
    }

    /** True when {@code type} and {@code id} name a configured destination. */
    public boolean exists(final String type, final String id) {
        return id != null && destinationName(type, id) != null;
    }

    /**
     * Queues delivery of a just-finalized document when its batch has a destination. Returns whether it did; the
     * caller saves the document.
     */
    public boolean queue(final Document document, final Batch batch, final String requestedBy) {
        if (batch == null || batch.getDestinationType() == null || batch.getDestinationId() == null) return false;
        final String described = describe(batch);
        document.setDeliveryStatus(STATUS_PENDING);
        document.setDeliveryAttempts(0);
        document.setDeliveryError(null);
        document.setDeliveredAt(null);
        document.setDeliveryNextAttemptAt(clock.instant());
        document.setDeliveryDestination(described == null ? label(batch.getDestinationType(), "(removed)") : described);
        document.setDeliveryRequestedBy(requestedBy);
        return true;
    }

    /**
     * Queues a failed delivery again, to the batch's current destination, with a fresh set of attempts. Returns an
     * error message, or null when it was queued.
     */
    public String retry(final Document document, final String requestedBy) {
        if (!STATUS_FAILED.equals(document.getDeliveryStatus())) {
            return "Only a failed delivery can be retried.";
        }
        final Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        if (!queue(document, batch, requestedBy)) {
            return "The document's batch has no destination.";
        }
        documentRepository.save(document);
        auditLogService.logForUser(requestedBy, "DOCUMENT_DELIVERY_RETRY", "Document", document.getId(),
                AuditLogService.OUTCOME_SUCCESS, Map.of("destination", document.getDeliveryDestination()));
        return null;
    }

    /** Attempts every delivery that is due. */
    @Scheduled(fixedDelayString = "${philter-workspace.delivery.poll-millis:10000}",
            initialDelayString = "${philter-workspace.delivery.poll-millis:10000}")
    public void deliverDue() {
        final Instant now = clock.instant();
        for (Document document : documentRepository.findByDeliveryStatus(STATUS_PENDING)) {
            final Instant next = document.getDeliveryNextAttemptAt();
            if (next != null && next.isAfter(now)) continue;
            try {
                attempt(document);
            } catch (OptimisticLockingFailureException e) {
                // Someone saved the document since it was read; it is picked up again on the next poll.
                LOG.debug("Document {} changed during delivery; retrying on the next poll.", document.getId());
            } catch (RuntimeException e) {
                LOG.warn("Delivery of document {} failed unexpectedly: {}", document.getId(), e.getClass().getName());
            }
        }
    }

    void attempt(final Document document) {
        if (PiiFieldEncryption.holdsUndecryptable(document)) {
            // Its output could be the placeholder, and the document cannot be saved to record a failure (#74), so it
            // stays pending, and is logged on each poll, until its values decrypt again.
            LOG.warn("Document {} has values that could not be decrypted; it is not delivered.", document.getId());
            return;
        }
        final Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        final int attempt = document.getDeliveryAttempts() + 1;
        document.setDeliveryAttempts(attempt);

        final String type = batch == null ? null : batch.getDestinationType();
        final String id = batch == null ? null : batch.getDestinationId();
        final String name = type == null || id == null ? null : destinationName(type, id);
        if (name == null) {
            // Nothing to retry against: the batch no longer has a destination, or it was removed.
            fail(document, type, id, null, attempt, "The batch has no destination, or it was removed.", true);
            return;
        }
        document.setDeliveryDestination(label(type, name));
        final String redacted = document.getRedactedText();
        if (redacted == null) {
            // Never fall back to the original text.
            fail(document, type, id, name, attempt, "The document has no finalized output to deliver.", true);
            return;
        }

        final byte[] payload = redacted.getBytes(StandardCharsets.UTF_8);
        final String filename = deliveredFilename(document);
        final DestinationWriter.Result result = TYPE_LOCAL.equals(type)
                ? writer.writeLocal(localRepository.findById(id).orElseThrow(), filename, payload)
                : writer.writeS3(s3Repository.findById(id).orElseThrow(), filename, payload);
        if (!result.isOk()) {
            fail(document, type, id, name, attempt, result.getError(), attempt >= MAX_ATTEMPTS);
            return;
        }

        document.setDeliveryStatus(STATUS_DELIVERED);
        document.setDeliveredAt(clock.instant());
        document.setDeliveryNextAttemptAt(null);
        document.setDeliveryError(null);
        documentRepository.save(document);
        final Map<String, Object> details = details(type, id, name, attempt);
        // The filename comes from the uploaded file's name, which can carry PII, and the audit log is readable across
        // groups, so it is recorded as a keyed hash like the Download entry.
        details.put("filenameHash", auditLogService.hashForAudit(filename));
        details.put("sha256", sha256(payload));
        details.put("bytes", payload.length);
        auditLogService.logForUser(document.getDeliveryRequestedBy(), "DOCUMENT_DELIVER", "Document",
                document.getId(), AuditLogService.OUTCOME_SUCCESS, details);
    }

    private void fail(final Document document, final String type, final String id, final String name,
                      final int attempt, final String error, final boolean giveUp) {
        document.setDeliveryError(error);
        if (giveUp) {
            document.setDeliveryStatus(STATUS_FAILED);
            document.setDeliveryNextAttemptAt(null);
        } else {
            document.setDeliveryNextAttemptAt(clock.instant().plus(RETRY_DELAYS.get(attempt - 1)));
        }
        documentRepository.save(document);
        final Map<String, Object> details = details(type, id, name, attempt);
        details.put("finalAttempt", giveUp);
        auditLogService.logForUser(document.getDeliveryRequestedBy(), "DOCUMENT_DELIVER", "Document",
                document.getId(), AuditLogService.OUTCOME_FAILURE, details);
        LOG.warn("Delivery of document {} failed on attempt {}{}.", document.getId(), attempt,
                giveUp ? ", giving up" : ", will retry");
    }

    private static Map<String, Object> details(final String type, final String id, final String name,
                                               final int attempt) {
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("destinationType", type == null ? "" : type);
        details.put("destinationId", id == null ? "" : id);
        details.put("destinationName", name == null ? "" : name);
        details.put("attempt", attempt);
        return details;
    }

    /**
     * The file written for a document: {@code <documentId>/<name>_redacted.txt}. The document id keeps two documents
     * with the same name apart, and the name is reduced to one safe path segment.
     */
    static String deliveredFilename(final Document document) {
        String name = document.getFilename() == null ? "" : document.getFilename();
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        final int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("^[.\\s]+", "").strip();
        if (name.isEmpty()) name = "document";
        return document.getId() + "/" + name + "_redacted.txt";
    }

    private String destinationName(final String type, final String id) {
        if (TYPE_LOCAL.equals(type)) {
            return localRepository.findById(id).map(LocalDirectoryDestination::getName).orElse(null);
        }
        if (TYPE_S3.equals(type)) {
            return s3Repository.findById(id).map(S3Destination::getName).orElse(null);
        }
        return null;
    }

    private static String label(final String type, final String name) {
        return (TYPE_S3.equals(type) ? "Amazon S3" : "Local directory") + ": " + name;
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
