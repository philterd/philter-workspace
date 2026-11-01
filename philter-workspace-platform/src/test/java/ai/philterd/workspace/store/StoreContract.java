/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store;

import ai.philterd.workspace.model.AuditLog;
import ai.philterd.workspace.model.BackgroundJob;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.DocumentComment;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.model.Group;
import ai.philterd.workspace.model.InboxMessage;
import ai.philterd.workspace.model.OpenSearchDataSource;
import ai.philterd.workspace.model.PendingUpload;
import ai.philterd.workspace.model.RedactionCertificate;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.model.User;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentCommentRepository;
import ai.philterd.workspace.repository.GeneralSettingsRepository;
import ai.philterd.workspace.repository.RedactionCertificateRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.GroupRepository;
import ai.philterd.workspace.repository.InboxMessageRepository;
import ai.philterd.workspace.repository.OpenSearchDataSourceRepository;
import ai.philterd.workspace.repository.PendingUploadRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.repository.WorkspaceRepository;
import ai.philterd.workspace.service.AuditLogQueryService;
import ai.philterd.workspace.service.DocumentLockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour both stores must share, run against each by a subclass: SQLite on a database file
 * ({@code SqliteStoreContractTest}) and MongoDB on a real server ({@code MongoStoreContractIT}).
 * Each test starts from an empty store with its indexes in place.
 */
public abstract class StoreContract {

    // ---- the store under test

    protected abstract <R> R repository(Class<R> type);

    protected abstract DocumentLockService locks();

    protected abstract DocumentQueueStore queue();

    protected abstract BackgroundJobStore jobs();

    protected abstract AuditLogQueryService audit();

    protected abstract <T> T save(T entity);

    protected abstract <T> Optional<T> findById(Class<T> type, String id);

    /** The record as the store holds it at rest, as text. */
    protected abstract String raw(Class<?> type, String id);

    /** A binary field of a stored record, as the store holds it at rest. */
    protected abstract byte[] rawBytes(Class<?> type, String id, String field);

    /** Sets one field of a stored record directly, bypassing the entity. */
    protected abstract void setField(Class<?> type, String id, String field, Object value);

    /** Removes one field from a stored record, as for a record written before the field existed. */
    protected abstract void removeField(Class<?> type, String id, String field);

    // ---- repositories and derived queries

    @Test
    void everyRepositoryIsAvailable() throws Exception {
        final List<Class<?>> repos = repositoryInterfaces();
        assertEquals(29, repos.size());
        for (Class<?> repo : repos) {
            assertNotNull(repository(repo), repo.getSimpleName());
        }
    }

    @Test
    void saveAssignsIdAndReplacesWholeDocument() {
        final OpenSearchDataSourceRepository sources = repository(OpenSearchDataSourceRepository.class);
        final OpenSearchDataSource source = new OpenSearchDataSource();
        source.setName("claims");
        source.setQuery("{}");
        sources.save(source);
        assertTrue(source.getId().matches("[0-9a-f]{24}"), source.getId());

        source.setQuery("{\"a\":1}");
        sources.save(source);
        assertEquals(1, sources.count());
        assertEquals("{\"a\":1}", sources.findById(source.getId()).orElseThrow().getQuery());
    }

    @Test
    void deliveryQueriesFindPendingDocumentsAndBatchesByDestination() {
        final DocumentRepository documents = repository(DocumentRepository.class);
        final ai.philterd.workspace.model.Document pending = new ai.philterd.workspace.model.Document();
        pending.setDeliveryStatus("PENDING");
        pending.setDeliveryNextAttemptAt(java.time.Instant.parse("2026-10-31T12:00:00.123Z"));
        final ai.philterd.workspace.model.Document delivered = new ai.philterd.workspace.model.Document();
        delivered.setDeliveryStatus("DELIVERED");
        documents.saveAll(List.of(pending, delivered, new ai.philterd.workspace.model.Document()));

        final List<ai.philterd.workspace.model.Document> found = documents.findByDeliveryStatus("PENDING");
        assertEquals(List.of(pending.getId()), found.stream().map(ai.philterd.workspace.model.Document::getId).toList());
        assertEquals(java.time.Instant.parse("2026-10-31T12:00:00.123Z"), found.get(0).getDeliveryNextAttemptAt());

        final BatchRepository batches = repository(BatchRepository.class);
        final Batch batch = new Batch();
        batch.setName("filings");
        batch.setDestinationType("S3");
        batch.setDestinationId("s1");
        batches.save(batch);
        assertTrue(batches.existsByDestinationTypeAndDestinationId("S3", "s1"));
        assertFalse(batches.existsByDestinationTypeAndDestinationId("LOCAL", "s1"));
        assertFalse(batches.existsByDestinationTypeAndDestinationId("S3", "s2"));
    }

    @Test
    void adjustedDetectionsAreCountedApartFromHandAddedSpans() {
        final ai.philterd.workspace.repository.SpanRepository spans =
                repository(ai.philterd.workspace.repository.SpanRepository.class);
        final ai.philterd.workspace.model.Span adjusted = new ai.philterd.workspace.model.Span();
        adjusted.setDocumentId("d1");
        adjusted.setAdjusted(true);
        final ai.philterd.workspace.model.Span adjustedManual = new ai.philterd.workspace.model.Span();
        adjustedManual.setDocumentId("d1");
        adjustedManual.setAdjusted(true);
        adjustedManual.setManuallyCreated(true);
        final ai.philterd.workspace.model.Span old = new ai.philterd.workspace.model.Span();
        old.setDocumentId("d1");
        spans.saveAll(List.of(adjusted, adjustedManual, old));
        // A span saved before "adjusted" existed has no such field.
        removeField(ai.philterd.workspace.model.Span.class, old.getId(), "adjusted");

        assertEquals(1, spans.countByDocumentIdInAndAdjustedAndManuallyCreated(List.of("d1"), true, false));
        assertEquals(0, spans.countByDocumentIdInAndAdjustedAndManuallyCreated(List.of("d2"), true, false));
    }

    @Test
    void negationAlsoMatchesMissingFields() {
        final InboxMessageRepository inbox = repository(InboxMessageRepository.class);
        final InboxMessage unread = message("u1", false, false);
        final InboxMessage archived = message("u1", false, true);
        inbox.saveAll(List.of(unread, archived, message("u2", false, false)));
        // A message saved before "archived" existed has no such field.
        removeField(InboxMessage.class, unread.getId(), "archived");

        assertEquals(List.of(unread.getId()),
                inbox.findNonArchivedByUserId("u1").stream().map(InboxMessage::getId).toList());
        assertEquals(1, inbox.countUnreadNonArchivedByUserId("u1"));
    }

    @Test
    void pagedQueryFiltersSortsAndCounts() {
        final DocumentRepository docs = repository(DocumentRepository.class);
        for (int i = 0; i < 5; i++) {
            docs.save(document("b1", i % 2 == 0 ? "PENDING" : "FINALIZED", "Report-" + i + ".txt",
                    LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(i)));
        }
        final Document noStatus = document("b1", null, "report-x.TXT", LocalDateTime.of(2026, 2, 1, 0, 0));
        docs.save(noStatus);
        docs.save(document("b2", "PENDING", "report-9.txt", LocalDateTime.of(2026, 1, 1, 0, 0)));

        final Page<Document> page = docs.findByBatchIdAndStatusNotInAndFilenameContainingIgnoreCase("b1",
                List.of("FINALIZED"), "REPORT", PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

        // PENDING report-0, -2, -4 plus the document with no status, which NotIn matches.
        assertEquals(4, page.getTotalElements());
        assertEquals(2, page.getTotalPages());
        assertEquals(List.of(noStatus.getId(), page.getContent().get(1).getId()),
                page.getContent().stream().map(Document::getId).toList());
        assertEquals("Report-4.txt", page.getContent().get(1).getFilename());

        final Page<Document> second = docs.findByBatchIdAndStatusNotInAndFilenameContainingIgnoreCase("b1",
                List.of("FINALIZED"), "REPORT", PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "createdAt")));
        assertEquals(List.of("Report-2.txt", "Report-0.txt"),
                second.getContent().stream().map(Document::getFilename).toList());
    }

    @Test
    void timestampComparisonsFollowTimeOrder() {
        final DocumentRepository docs = repository(DocumentRepository.class);
        final LocalDateTime t = LocalDateTime.of(2026, 3, 1, 12, 0, 0);
        docs.save(document("b", "PENDING", "a", t));
        docs.save(document("b", "PENDING", "b", t.plusNanos(500_000_000)));
        docs.save(document("b", "PENDING", "c", t.plusSeconds(1)));

        assertEquals(2, docs.countByCreatedAtAfter(t));
        assertEquals(1, docs.countByCreatedAtAfter(t.plusNanos(600_000_000)));
    }

    @Test
    void collectionFieldsMatchAnyElement() {
        final GroupRepository groups = repository(GroupRepository.class);
        final Group g = new Group();
        g.setName("g");
        g.setUserIds(Set.of("u1", "u2"));
        g.setLeaderUserIds(Set.of("u1"));
        g.setApproverUserIds(Set.of("u2"));
        groups.save(g);
        assertEquals(1, groups.findByUserIdsContaining("u2").size());
        assertEquals(0, groups.findByUserIdsContaining("u3").size());
        assertEquals(1, groups.findByLeaderUserIdsContaining("u1").size());
        assertEquals(1, groups.findByApproverUserIdsContaining("u2").size());
        assertEquals(0, groups.findByApproverUserIdsContaining("u1").size(), "a lead is not an approver");

        final UserRepository users = repository(UserRepository.class);
        final User admin = new User();
        admin.setEmail("admin@example.com");
        admin.setRoles(Set.of("ADMIN", "USER"));
        users.save(admin);
        assertEquals(1, users.countByRolesContaining("ADMIN"));
        assertEquals(0, users.countByRolesContaining("AUDITOR"));
    }

    @Test
    void ignoreCaseFoldsNonAsciiLetters() {
        final OpenSearchDataSourceRepository sources = repository(OpenSearchDataSourceRepository.class);
        final OpenSearchDataSource source = new OpenSearchDataSource();
        source.setName("Müller");
        sources.save(source);
        assertTrue(sources.findFirstByNameIgnoreCase("MÜLLER").isPresent());
        assertFalse(sources.findByName("MÜLLER").isPresent());
    }

    // ---- indexes and versions

    @Test
    void uniqueIndexRefusesDuplicates() {
        final GroupRepository groups = repository(GroupRepository.class);
        final Group a = new Group();
        a.setName("same");
        groups.save(a);
        final Group b = new Group();
        b.setName("same");
        assertThrows(DuplicateKeyException.class, () -> groups.save(b));
    }

    @Test
    void uniqueFieldsLeftEmptyAreNotDuplicates() {
        // Uniqueness applies among records that have a value: MongoDB's unique indexes are
        // sparse, and SQLite's never count a missing value.
        final GroupRepository groups = repository(GroupRepository.class);
        groups.save(new Group());
        groups.save(new Group());
        assertEquals(2, groups.count());

        final UserRepository users = repository(UserRepository.class);
        users.save(new User());
        users.save(new User());
        assertEquals(2, users.count());

        // An empty string is a value.
        final Group blank = new Group();
        blank.setName("");
        groups.save(blank);
        final Group secondBlank = new Group();
        secondBlank.setName("");
        assertThrows(DuplicateKeyException.class, () -> groups.save(secondBlank));
    }

    @Test
    void aBatchNameIsUniqueWithinItsGroupOnly() {
        // #83: a name another group uses is free, so taking it reveals nothing about that group.
        final BatchRepository batches = repository(BatchRepository.class);
        batches.save(batch("g1", "Smith v. Jones"));
        batches.save(batch("g2", "Smith v. Jones"));
        assertThrows(DuplicateKeyException.class, () -> batches.save(batch("g1", "Smith v. Jones")));
        assertTrue(batches.existsByGroupIdAndName("g1", "Smith v. Jones"));
        assertFalse(batches.existsByGroupIdAndName("g3", "Smith v. Jones"));

        // The index covers only batches with a group and a name, the same in both stores.
        batches.save(batch(null, "Ungrouped"));
        batches.save(batch(null, "Ungrouped"));
        batches.save(batch("g1", null));
        batches.save(batch("g1", null));
        assertEquals(6, batches.count());
    }

    private static Batch batch(final String groupId, final String name) {
        final Batch b = new Batch();
        b.setGroupId(groupId);
        b.setName(name);
        return b;
    }

    @Test
    void versionedSaveRefusesAStaleCopy() {
        final UserRepository users = repository(UserRepository.class);
        final User u = new User();
        u.setEmail("a@example.com");
        users.save(u);
        assertEquals(0L, u.getVersion());

        final User stale = users.findById(u.getId()).orElseThrow();
        u.setEmail("b@example.com");
        users.save(u);
        assertEquals(1L, u.getVersion());

        stale.setEmail("c@example.com");
        assertThrows(OptimisticLockingFailureException.class, () -> users.save(stale));
        assertEquals("b@example.com", users.findById(u.getId()).orElseThrow().getEmail());
    }

    @Test
    void partialUniqueIndexAllowsOneRunningImportPerBatch() {
        final BackgroundJob a = save(job("b1", BackgroundJob.TYPE_OPENSEARCH_INGEST));
        final BackgroundJob b = save(job("b1", BackgroundJob.TYPE_RDB_INGEST));
        final BackgroundJob other = save(job("b2", BackgroundJob.TYPE_OPENSEARCH_INGEST));
        final BackgroundJob export = save(job("b1", BackgroundJob.TYPE_BATCH_EXPORT));

        assertNotNull(jobs().claimPending(a.getId()));
        assertNull(jobs().claimPending(a.getId()));
        assertNull(jobs().claimPending(b.getId()));
        assertEquals("PENDING", findById(BackgroundJob.class, b.getId()).orElseThrow().getStatus());
        assertNotNull(jobs().claimPending(other.getId()));
        assertNotNull(jobs().claimPending(export.getId()));
        assertEquals(2, jobs().countRunning(List.of(BackgroundJob.TYPE_OPENSEARCH_INGEST, BackgroundJob.TYPE_RDB_INGEST)));
    }

    @Test
    void s3ImportWaitsWhileAnotherImportRunsForTheBatch() {
        final BackgroundJob running = save(job("b1", BackgroundJob.TYPE_OPENSEARCH_INGEST));
        final BackgroundJob s3 = save(job("b1", BackgroundJob.TYPE_S3_INGEST));
        final BackgroundJob secondS3 = save(job("b1", BackgroundJob.TYPE_S3_INGEST));
        final BackgroundJob otherBatch = save(job("b2", BackgroundJob.TYPE_S3_INGEST));

        assertNotNull(jobs().claimPending(running.getId()));
        assertNull(jobs().claimPending(s3.getId()));
        assertNotNull(jobs().claimPending(otherBatch.getId()));

        setField(BackgroundJob.class, running.getId(), "status", BackgroundJob.STATUS_COMPLETED);
        assertNotNull(jobs().claimPending(s3.getId()));
        assertNull(jobs().claimPending(secondS3.getId()), "a second S3 import waits for the first");
    }

    // ---- atomic claims and locks

    @Test
    void onlyOneOfManyConcurrentWorkersClaimsADocument() throws Exception {
        final Document d = save(document("b", "PENDING", "f", LocalDateTime.now()));
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final List<Callable<Document>> claims = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                claims.add(() -> queue().claimPending(d.getId()));
            }
            int won = 0;
            for (Future<Document> f : pool.invokeAll(claims)) {
                if (f.get() != null) {
                    won++;
                    assertEquals("PROCESSING", f.get().getStatus());
                    assertEquals(0L, f.get().getVersion(), "a claim leaves the version alone");
                }
            }
            assertEquals(1, won);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlyOneOfManyConcurrentDispatchersClaimsAJob() throws Exception {
        final BackgroundJob j = save(job("b1", BackgroundJob.TYPE_BATCH_EXPORT));
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final List<Callable<BackgroundJob>> claims = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                claims.add(() -> jobs().claimPending(j.getId()));
            }
            int won = 0;
            for (Future<BackgroundJob> f : pool.invokeAll(claims)) {
                if (f.get() != null) {
                    won++;
                }
            }
            assertEquals(1, won);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void documentLockIsExclusiveUntilReleasedOrExpired() {
        final Document d = save(document("b", "PENDING", "f", LocalDateTime.now()));
        d.setOriginalText("secret text");
        save(d);

        final Document held = locks().acquire(d.getId(), "a@example.com");
        assertEquals("a@example.com", held.getLockedBy());
        assertEquals("secret text", held.getOriginalText());
        assertNull(locks().acquire(d.getId(), "b@example.com"));
        assertNotNull(locks().acquire(d.getId(), "a@example.com"));
        assertNotNull(locks().pulse(d.getId(), "a@example.com"));
        assertNull(locks().pulse(d.getId(), "b@example.com"));

        locks().release(d.getId(), "b@example.com");
        assertNull(locks().acquire(d.getId(), "b@example.com"));
        locks().release(d.getId(), "a@example.com");
        assertEquals("b@example.com", locks().acquire(d.getId(), "b@example.com").getLockedBy());

        // An expired lock can be taken over.
        setField(Document.class, d.getId(), "lockExpiresAt", Instant.now().minusSeconds(1));
        assertEquals("c@example.com", locks().acquire(d.getId(), "c@example.com").getLockedBy());

        final Document broken = locks().breakLock(d.getId());
        assertNull(broken.getLockedBy());
        assertNull(broken.getLockExpiresAt());
        // Lock changes leave the optimistic-lock version alone, so the holder's save still succeeds.
        assertEquals(1L, findById(Document.class, d.getId()).orElseThrow().getVersion());
        final Document holder = locks().acquire(d.getId(), "c@example.com");
        locks().pulse(d.getId(), "c@example.com");
        holder.setStatus("REVIEW_REQUIRED");
        save(holder);
        assertEquals(2L, holder.getVersion());
    }

    // ---- the document queue

    @Test
    void queueRequeuesStuckClaimsAndCountsWindows() {
        final LocalDateTime now = LocalDateTime.now();
        final Document stuck = document("b", "PROCESSING", "a", now);
        stuck.setStatusChangedAt(now.minusHours(2));
        save(stuck);
        final Document fresh = document("b", "PROCESSING", "b", now);
        fresh.setStatusChangedAt(now);
        save(fresh);

        assertEquals(1, queue().requeueProcessingBefore(now.minusHours(1)));
        final Document requeued = findById(Document.class, stuck.getId()).orElseThrow();
        assertEquals("PENDING", requeued.getStatus());
        assertEquals(0L, requeued.getVersion(), "a requeue leaves the version alone");
        assertEquals(2, queue().countCreatedBetween(now.minusDays(1), now.plusMinutes(1)));
        assertEquals(1, queue().countStatusChangedBetween(now.minusDays(1), now.plusMinutes(1), List.of("PENDING")));
    }

    @Test
    void onlyAFailedDocumentIsRequeuedAndOnlyOnce() throws Exception {
        final Document failed = document("b", "FAILED", "a", LocalDateTime.now());
        failed.setStatusChangedAt(LocalDateTime.now().minusDays(1));
        save(failed);
        final Document pending = save(document("b", "PENDING", "b", LocalDateTime.now()));

        assertNull(queue().requeueFailed(pending.getId()));
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final List<Callable<Document>> retries = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                retries.add(() -> queue().requeueFailed(failed.getId()));
            }
            int won = 0;
            for (Future<Document> f : pool.invokeAll(retries)) {
                if (f.get() != null) {
                    won++;
                    assertEquals("PENDING", f.get().getStatus());
                    assertTrue(f.get().getStatusChangedAt().isAfter(LocalDateTime.now().minusMinutes(1)));
                    assertEquals(0L, f.get().getVersion(), "a requeue leaves the version alone");
                }
            }
            assertEquals(1, won);
        } finally {
            pool.shutdownNow();
        }
        assertNull(queue().requeueFailed("no-such-document"));
    }

    @Test
    void queueRemovesOnlyDocumentsInTheGivenStatus() {
        final Document failed = save(document("b", "FAILED", "a", LocalDateTime.now()));
        final Document pending = save(document("b", "PENDING", "b", LocalDateTime.now()));

        assertNull(queue().removeIfStatus(pending.getId(), "FAILED"));
        assertEquals(failed.getId(), queue().removeIfStatus(failed.getId(), "FAILED").getId());
        save(document("b", "FAILED", "c", LocalDateTime.now()));
        save(document("b", "FAILED", "d", LocalDateTime.now()));
        assertEquals(2, queue().removeAllWithStatus("FAILED").size());
        assertEquals(1, repository(DocumentRepository.class).count());
    }

    @Test
    void webhookDeliveryRecordsArePrunedByAge() {
        final ai.philterd.workspace.repository.PhilterWebhookDeliveryRepository deliveries =
                repository(ai.philterd.workspace.repository.PhilterWebhookDeliveryRepository.class);
        final Instant now = Instant.parse("2026-10-30T12:00:00Z");
        deliveries.save(new ai.philterd.workspace.model.PhilterWebhookDelivery("old", "WEBHOOK_TEST", now.minusSeconds(8 * 86400)));
        deliveries.save(new ai.philterd.workspace.model.PhilterWebhookDelivery("new", "WEBHOOK_TEST", now.minusSeconds(3600)));

        assertTrue(deliveries.existsById("old"));
        assertEquals(1, deliveries.deleteByReceivedAtBefore(now.minusSeconds(7 * 86400)));
        assertFalse(deliveries.existsById("old"));
        assertTrue(deliveries.existsById("new"));
    }

    // ---- the audit log

    @Test
    void auditQueriesFilterSortAndPage() {
        final Instant t = Instant.parse("2026-05-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            save(entry(t.plusSeconds(i), "Document", "d1", i % 2 == 0 ? "a@example.com" : "b@example.com"));
        }
        save(entry(t.plusSeconds(10), "Span", "s1", "a@example.com"));
        save(entry(t.plusSeconds(11), "Span", "s9", "a@example.com"));

        final List<AuditLog> asc = audit().find(t.plusSeconds(1), t.plusSeconds(3), null, "Document", null, 0);
        assertEquals(List.of(t.plusSeconds(1), t.plusSeconds(2), t.plusSeconds(3)),
                asc.stream().map(AuditLog::getTimestamp).toList());

        final AuditLogQueryService.Result page = audit().query(
                new AuditLogQueryService.QueryFilters(null, null, "a@example.com", null, null, null, null, null), 0, 2);
        assertEquals(5, page.total());
        assertEquals(List.of(t.plusSeconds(11), t.plusSeconds(10)),
                page.entries().stream().map(AuditLog::getTimestamp).toList());

        assertEquals(6, audit().findForDocument("d1", List.of("s1")).size());
    }

    // ---- encryption at rest

    @Test
    void piiIsEncryptedAtRestAndPlainInMemory() {
        final DocumentRepository docs = repository(DocumentRepository.class);
        final Document d = document("b", "PENDING", "f.txt", LocalDateTime.now());
        d.setOriginalText("SSN 123-45-6789");
        docs.save(d);

        assertEquals("SSN 123-45-6789", d.getOriginalText());
        final String raw = raw(Document.class, d.getId());
        assertFalse(raw.contains("123-45-6789"), raw);
        assertTrue(raw.contains("enc:v1:"), raw);
        assertEquals("SSN 123-45-6789", docs.findById(d.getId()).orElseThrow().getOriginalText());

        final SpanRepository spans = repository(SpanRepository.class);
        final Span s = new Span();
        s.setDocumentId(d.getId());
        s.setText("123-45-6789");
        spans.save(s);
        assertFalse(raw(Span.class, s.getId()).contains("123-45-6789"));
        assertEquals("123-45-6789", spans.findByDocumentId(d.getId()).get(0).getText());

        // A lock change goes around the repository and must not undo the encryption.
        locks().acquire(d.getId(), "a@example.com");
        assertFalse(raw(Document.class, d.getId()).contains("123-45-6789"));
    }

    @Test
    void valuesThatLookEncryptedAreEncryptedAndReadBackAsWritten() {
        // #74: a comment or an ingested text starting with the marker is someone's input, not ciphertext.
        final String looksEncrypted = "enc:v1:aGVsbG8gd29ybGQgdGhpcyBpcyBub3QgY2lwaGVydGV4dA==";
        final DocumentCommentRepository comments = repository(DocumentCommentRepository.class);
        final DocumentComment comment = new DocumentComment();
        comment.setDocumentId("d-74");
        comment.setText(looksEncrypted);
        comments.save(comment);
        assertFalse(raw(DocumentComment.class, comment.getId()).contains(looksEncrypted.substring(7)));
        assertEquals(looksEncrypted, comments.findById(comment.getId()).orElseThrow().getText());

        final DocumentRepository docs = repository(DocumentRepository.class);
        final Document d = document("b", "PENDING", "f.txt", LocalDateTime.now());
        d.setOriginalText(looksEncrypted);
        docs.save(d);
        assertEquals(looksEncrypted, docs.findById(d.getId()).orElseThrow().getOriginalText());
    }

    @Test
    void aValueThatDoesNotDecryptLeavesTheRestOfTheListReadableAndCannotBeSaved() {
        final DocumentCommentRepository comments = repository(DocumentCommentRepository.class);
        final List<String> ids = new ArrayList<>();
        for (String text : List.of("first", "second")) {
            final DocumentComment c = new DocumentComment();
            c.setDocumentId("d-bad");
            c.setText(text);
            c.setTimestamp(java.time.Instant.now());
            comments.save(c);
            ids.add(c.getId());
        }
        // As a comment starting with the marker was stored before #74.
        setField(DocumentComment.class, ids.get(0), "text", "enc:v1:aGVsbG8gd29ybGQ=");

        final List<DocumentComment> read = comments.findByDocumentIdOrderByTimestampDesc("d-bad");
        assertEquals(2, read.size());
        final DocumentComment bad = read.stream().filter(c -> c.getId().equals(ids.get(0))).findFirst().orElseThrow();
        assertEquals(ai.philterd.workspace.service.PiiFieldEncryption.UNDECRYPTABLE, bad.getText());
        assertEquals("second", read.stream().filter(c -> c.getId().equals(ids.get(1))).findFirst().orElseThrow()
                .getText());

        assertThrows(ai.philterd.workspace.service.PiiFieldEncryption.UndecryptableRecordException.class,
                () -> comments.save(bad));
        assertEquals("enc:v1:aGVsbG8gd29ybGQ=", rawText(DocumentComment.class, ids.get(0)), "the stored value is kept");
    }

    /** The stored value of the {@code text} field. */
    private String rawText(final Class<?> type, final String id) {
        final String raw = raw(type, id);
        final int start = raw.indexOf("enc:v1:");
        return raw.substring(start, raw.indexOf('"', start));
    }

    @Test
    void everyOtherPiiFieldIsEncryptedAtRest() {
        final DocumentComment comment = new DocumentComment();
        comment.setDocumentId("d1");
        comment.setText("Call 555-0100");
        repository(DocumentCommentRepository.class).save(comment);
        final String rawComment = raw(DocumentComment.class, comment.getId());
        assertFalse(rawComment.contains("555-0100"), rawComment);
        assertTrue(rawComment.contains("enc:v1:"), rawComment);
        assertEquals("Call 555-0100", repository(DocumentCommentRepository.class)
                .findById(comment.getId()).orElseThrow().getText());

        final RedactionCertificate certificate = new RedactionCertificate();
        certificate.setDocumentId("d1");
        certificate.setDocumentFilename("jane-roe-claim.pdf");
        final RedactionCertificate.OverturnEntry overturn = new RedactionCertificate.OverturnEntry();
        overturn.setSpanText("Jane Roe");
        certificate.setOverturns(new ArrayList<>(List.of(overturn)));
        repository(RedactionCertificateRepository.class).save(certificate);
        final String rawCertificate = raw(RedactionCertificate.class, certificate.getId());
        assertFalse(rawCertificate.contains("jane-roe") || rawCertificate.contains("Jane Roe"), rawCertificate);
        // The filename and the overturned span's text, each encrypted.
        assertEquals(2, rawCertificate.split("enc:v1:", -1).length - 1, rawCertificate);
        final RedactionCertificate readCertificate = repository(RedactionCertificateRepository.class)
                .findById(certificate.getId()).orElseThrow();
        assertEquals("jane-roe-claim.pdf", readCertificate.getDocumentFilename());
        assertEquals("Jane Roe", readCertificate.getOverturns().get(0).getSpanText());

        final GeneralSettings settings = new GeneralSettings();
        settings.setId(GeneralSettings.SINGLETON_ID);
        settings.setOpensearchPassword("search-secret");
        repository(GeneralSettingsRepository.class).save(settings);
        final String rawSettings = raw(GeneralSettings.class, GeneralSettings.SINGLETON_ID);
        assertFalse(rawSettings.contains("search-secret"), rawSettings);
        assertTrue(rawSettings.contains("enc:v1:"), rawSettings);
        assertEquals("search-secret", repository(GeneralSettingsRepository.class)
                .findById(GeneralSettings.SINGLETON_ID).orElseThrow().getOpensearchPassword());
    }

    @Test
    void finalizedDocumentsAwaitingClearingAreFoundIncludingOnesSavedBeforeTheField() {
        // #68: a document whose source has not been cleared has no sourceClearedAt, including one saved before the
        // field existed.
        final DocumentRepository docs = repository(DocumentRepository.class);
        final Document awaiting = document("b-68", "FINALIZED", "a.txt", LocalDateTime.now());
        awaiting.setFinalizedAt(java.time.Instant.parse("2026-10-30T12:00:00Z"));
        docs.save(awaiting);
        final Document cleared = document("b-68", "FINALIZED", "b.txt", LocalDateTime.now());
        cleared.setSourceClearedAt(java.time.Instant.parse("2026-10-31T12:00:00Z"));
        docs.save(cleared);
        docs.save(document("b-68", "APPROVED", "c.txt", LocalDateTime.now()));
        final Document legacy = document("b-68", "FINALIZED", "d.txt", LocalDateTime.now());
        docs.save(legacy);
        removeField(Document.class, legacy.getId(), "sourceClearedAt");

        final java.util.Set<String> found = new java.util.HashSet<>();
        for (Document d : docs.findByBatchIdAndStatusAndSourceClearedAtIsNull("b-68", "FINALIZED")) {
            found.add(d.getId());
        }
        assertEquals(java.util.Set.of(awaiting.getId(), legacy.getId()), found);
        assertEquals(java.time.Instant.parse("2026-10-30T12:00:00Z"),
                docs.findById(awaiting.getId()).orElseThrow().getFinalizedAt());
    }

    @Test
    void credentialsReasonsAndIngestErrorsAreEncryptedAtRest() {
        // #77: the SMTP password.
        final ai.philterd.workspace.model.NotificationSettings smtp = new ai.philterd.workspace.model.NotificationSettings();
        smtp.setId("notifications");
        smtp.setPassword("smtp-secret-77");
        repository(ai.philterd.workspace.repository.NotificationSettingsRepository.class).save(smtp);
        assertEncryptedAtRest(ai.philterd.workspace.model.NotificationSettings.class, "notifications", "smtp-secret-77");
        assertEquals("smtp-secret-77", repository(ai.philterd.workspace.repository.NotificationSettingsRepository.class)
                .findById("notifications").orElseThrow().getPassword());

        // An overturn reason in the audit log, and on the certificate.
        final ai.philterd.workspace.model.AuditLog entry = new ai.philterd.workspace.model.AuditLog();
        entry.setId("a-77");
        entry.setAction("SPAN_UPDATE");
        entry.setDetails(new java.util.LinkedHashMap<>(java.util.Map.of("overturn", true,
                "reason", "Jane Roe's SSN, 123-45-6789, is quoted in the exhibit")));
        repository(ai.philterd.workspace.repository.AuditLogRepository.class).save(entry);
        assertEncryptedAtRest(ai.philterd.workspace.model.AuditLog.class, "a-77", "123-45-6789");
        final ai.philterd.workspace.model.AuditLog readEntry = repository(
                ai.philterd.workspace.repository.AuditLogRepository.class).findById("a-77").orElseThrow();
        assertEquals("Jane Roe's SSN, 123-45-6789, is quoted in the exhibit", readEntry.getDetails().get("reason"));
        assertEquals(true, readEntry.getDetails().get("overturn"), "the other details are as they were");

        final RedactionCertificate certificate = new RedactionCertificate();
        certificate.setId("cert-77");
        final RedactionCertificate.OverturnEntry overturn = new RedactionCertificate.OverturnEntry();
        overturn.setReason("Quoted 555-0177 in the margin");
        certificate.setOverturns(new ArrayList<>(List.of(overturn)));
        repository(RedactionCertificateRepository.class).save(certificate);
        assertEncryptedAtRest(RedactionCertificate.class, "cert-77", "555-0177");
        assertEquals("Quoted 555-0177 in the margin", repository(RedactionCertificateRepository.class)
                .findById("cert-77").orElseThrow().getOverturns().get(0).getReason());

        // An ingest job's error and failure messages, and an import log entry.
        final ai.philterd.workspace.model.BackgroundJob job = new ai.philterd.workspace.model.BackgroundJob();
        job.setId("job-77");
        job.setErrorMessage("Duplicate entry 'jroe@example.com' for key 'email'");
        job.getFailureMessages().add("Row 'Jane Roe' could not be read");
        repository(ai.philterd.workspace.repository.BackgroundJobRepository.class).save(job);
        assertEncryptedAtRest(ai.philterd.workspace.model.BackgroundJob.class, "job-77", "jroe@example.com");
        assertEncryptedAtRest(ai.philterd.workspace.model.BackgroundJob.class, "job-77", "Jane Roe");
        final ai.philterd.workspace.model.BackgroundJob readJob = repository(
                ai.philterd.workspace.repository.BackgroundJobRepository.class).findById("job-77").orElseThrow();
        assertEquals("Duplicate entry 'jroe@example.com' for key 'email'", readJob.getErrorMessage());
        assertEquals(List.of("Row 'Jane Roe' could not be read"), readJob.getFailureMessages());

        final ai.philterd.workspace.model.DataImportLogEntry logEntry = new ai.philterd.workspace.model.DataImportLogEntry();
        logEntry.setId("log-77");
        logEntry.setJobId("job-77");
        logEntry.setMessage("Value 'Jane Roe' is too long");
        repository(ai.philterd.workspace.repository.DataImportLogEntryRepository.class).save(logEntry);
        assertEncryptedAtRest(ai.philterd.workspace.model.DataImportLogEntry.class, "log-77", "Jane Roe");
        assertEquals("Value 'Jane Roe' is too long", repository(
                ai.philterd.workspace.repository.DataImportLogEntryRepository.class).findById("log-77").orElseThrow()
                .getMessage());
    }

    @Test
    void valuesStoredInTheClearBeforeEncryptionStillRead() {
        // #77: existing values read as they are until their next save encrypts them.
        final ai.philterd.workspace.model.BackgroundJob job = new ai.philterd.workspace.model.BackgroundJob();
        job.setId("job-legacy");
        job.setErrorMessage("placeholder");
        repository(ai.philterd.workspace.repository.BackgroundJobRepository.class).save(job);
        setField(ai.philterd.workspace.model.BackgroundJob.class, "job-legacy", "errorMessage", "plain old error");

        final ai.philterd.workspace.repository.BackgroundJobRepository jobs =
                repository(ai.philterd.workspace.repository.BackgroundJobRepository.class);
        final ai.philterd.workspace.model.BackgroundJob read = jobs.findById("job-legacy").orElseThrow();
        assertEquals("plain old error", read.getErrorMessage());
        jobs.save(read);
        assertEncryptedAtRest(ai.philterd.workspace.model.BackgroundJob.class, "job-legacy", "plain old error");
    }

    private void assertEncryptedAtRest(final Class<?> type, final String id, final String value) {
        final String raw = raw(type, id);
        assertFalse(raw.contains(value), raw);
        assertTrue(raw.contains("enc:v1:"), raw);
    }

    @Test
    void aDocumentsCaseNumberAndFilerAreEncryptedAndItsCaseTypeIsNot() {
        final Document d = new Document();
        d.setId("case-1");
        d.setStatus("PENDING");
        d.setCaseMetadata(ai.philterd.workspace.model.CaseMetadata.of("1:26-cv-01234", "civil", "Acme Dental LLC",
                "paid", true));
        d.setAppliedPolicyName("civil-filings");
        d.setAppliedPolicyVersion(3);
        repository(DocumentRepository.class).save(d);

        final String raw = raw(Document.class, "case-1");
        assertFalse(raw.contains("1:26-cv-01234") || raw.contains("Acme Dental"), raw);
        assertTrue(raw.contains("civil"), "the case type stays readable for matching: " + raw);
        final Document read = repository(DocumentRepository.class).findById("case-1").orElseThrow();
        assertEquals("1:26-cv-01234", read.getCaseMetadata().getCaseNumber());
        assertEquals("Acme Dental LLC", read.getCaseMetadata().getFiler());
        assertEquals("civil", read.getCaseMetadata().getCaseType());
        assertEquals("paid", read.getCaseMetadata().getFeeStatus());
        assertEquals(true, read.getCaseMetadata().getIfp());
        assertEquals("civil-filings", read.getAppliedPolicyName());
        assertEquals(3, read.getAppliedPolicyVersion());
    }

    @Test
    void detectionRulesAreFoundByTheirKeyWithTheirValueEncrypted() {
        final ai.philterd.workspace.repository.DetectionRuleRepository rules =
                repository(ai.philterd.workspace.repository.DetectionRuleRepository.class);
        final ai.philterd.workspace.model.DetectionRule rule = new ai.philterd.workspace.model.DetectionRule();
        rule.setId("r1");
        rule.setKind(ai.philterd.workspace.model.DetectionRule.KIND_NEVER);
        rule.setStatus(ai.philterd.workspace.model.DetectionRule.STATUS_ACTIVE);
        rule.setText("Acme Dental");
        rule.setTextKey("key-1");
        rule.setProposedAt(Instant.parse("2026-10-30T12:00:00Z"));
        rules.save(rule);

        final String raw = raw(ai.philterd.workspace.model.DetectionRule.class, "r1");
        assertFalse(raw.contains("Acme Dental"), raw);
        assertTrue(raw.contains("enc:v1:"), raw);
        assertEquals(List.of("r1"), rules.findByTextKey("key-1").stream()
                .map(ai.philterd.workspace.model.DetectionRule::getId).toList());
        assertEquals("Acme Dental", rules.findByTextKey("key-1").get(0).getText());
        assertTrue(rules.findByTextKey("key-2").isEmpty());
    }

    @Test
    void spansAreFoundByTheirDocumentsAndStatuses() {
        final SpanRepository spans = repository(SpanRepository.class);
        for (String[] s : new String[][]{{"s1", "d1", "REJECTED"}, {"s2", "d2", "APPROVED"}, {"s3", "d2", "PENDING"},
                {"s4", "d3", "REJECTED"}}) {
            final Span span = new Span();
            span.setId(s[0]);
            span.setDocumentId(s[1]);
            span.setStatus(s[2]);
            span.setText("text " + s[0]);
            spans.save(span);
        }
        assertEquals(Set.of("s1", "s2"), spans.findByDocumentIdInAndStatusIn(List.of("d1", "d2"),
                List.of("REJECTED", "APPROVED")).stream().map(Span::getId).collect(java.util.stream.Collectors.toSet()));
        assertEquals("text s1", spans.findByDocumentIdInAndStatusIn(List.of("d1"), List.of("REJECTED")).get(0).getText());
    }

    @Test
    void queuedUploadsAreEncryptedAtRestAndReadBackIdentically() throws Exception {
        final PendingUploadRepository uploads = repository(PendingUploadRepository.class);
        final byte[] text = "Patient Jane Roe, SSN 123-45-6789.".getBytes(StandardCharsets.UTF_8);
        final byte[] pdf = pdf("Patient Jane Roe, SSN 123-45-6789.");
        for (byte[] content : List.of(text, pdf)) {
            final PendingUpload upload = upload(content);
            uploads.save(upload);
            assertArrayEquals(content, upload.getContent(), "the caller's entity keeps the plaintext");

            final byte[] stored = rawBytes(PendingUpload.class, upload.getId(), "content");
            assertFalse(contains(stored, "123-45-6789".getBytes(StandardCharsets.UTF_8)), "the SSN is not stored");
            assertFalse(contains(stored, "Jane Roe".getBytes(StandardCharsets.UTF_8)), "the name is not stored");
            assertTrue(contains(stored, "enc:v1:".getBytes(StandardCharsets.US_ASCII)));
            assertArrayEquals(content, uploads.findById(upload.getId()).orElseThrow().getContent());
        }
    }

    @Test
    void aQueuedUploadStoredBeforeEncryptionStillReads() {
        final PendingUploadRepository uploads = repository(PendingUploadRepository.class);
        final byte[] legacy = "Patient Jane Roe, SSN 123-45-6789.".getBytes(StandardCharsets.UTF_8);
        final PendingUpload upload = uploads.save(upload(legacy));
        // As a row written before this change held it: the plain bytes.
        setField(PendingUpload.class, upload.getId(), "content", legacy);
        assertArrayEquals(legacy, rawBytes(PendingUpload.class, upload.getId(), "content"));

        assertArrayEquals(legacy, uploads.findById(upload.getId()).orElseThrow().getContent());
    }

    @Test
    void anUploadThatStartsWithTheMarkerIsStillEncrypted() {
        final PendingUploadRepository uploads = repository(PendingUploadRepository.class);
        final byte[] content = "enc:v1:Jane Roe".getBytes(StandardCharsets.UTF_8);
        final PendingUpload upload = uploads.save(upload(content));

        assertFalse(contains(rawBytes(PendingUpload.class, upload.getId(), "content"),
                "Jane Roe".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(content, uploads.findById(upload.getId()).orElseThrow().getContent());
    }

    // ---- fixtures

    private static PendingUpload upload(final byte[] content) {
        final PendingUpload u = new PendingUpload();
        u.setId(java.util.UUID.randomUUID().toString());
        u.setContentType("application/octet-stream");
        u.setContent(content);
        u.setCreatedAt(LocalDateTime.now());
        return u;
    }

    /** A one-page PDF whose content stream holds {@code text} uncompressed, so the bytes show it. */
    private static byte[] pdf(final String text) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            final org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(
                    doc, page, org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode.OVERWRITE, false)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            final byte[] bytes = out.toByteArray();
            assertTrue(contains(bytes, "123-45-6789".getBytes(StandardCharsets.US_ASCII)), "the PDF shows the text plainly");
            return bytes;
        }
    }

    private static boolean contains(final byte[] haystack, final byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    public static List<Class<?>> repositoryInterfaces() throws ClassNotFoundException {
        final ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(final org.springframework.beans.factory.annotation.AnnotatedBeanDefinition d) {
                return d.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(WorkspaceRepository.class));
        final List<Class<?>> types = new ArrayList<>();
        for (BeanDefinition d : scanner.findCandidateComponents("ai.philterd.workspace.repository")) {
            final Class<?> c = Class.forName(d.getBeanClassName());
            if (c != WorkspaceRepository.class) {
                types.add(c);
            }
        }
        return types;
    }

    private static InboxMessage message(final String userId, final boolean read, final boolean archived) {
        final InboxMessage m = new InboxMessage();
        m.setUserId(userId);
        m.setRead(read);
        m.setArchived(archived);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    private static Document document(final String batchId, final String status, final String filename,
                                     final LocalDateTime createdAt) {
        final Document d = new Document();
        d.setBatchId(batchId);
        d.setStatus(status);
        d.setFilename(filename);
        d.setCreatedAt(createdAt);
        return d;
    }

    private static BackgroundJob job(final String batchId, final String type) {
        final BackgroundJob j = new BackgroundJob();
        j.setBatchId(batchId);
        j.setType(type);
        j.setStatus(BackgroundJob.STATUS_PENDING);
        return j;
    }

    private static AuditLog entry(final Instant at, final String resourceType, final String resourceId, final String email) {
        final AuditLog a = new AuditLog();
        a.setTimestamp(at);
        a.setResourceType(resourceType);
        a.setResourceId(resourceId);
        a.setUserEmail(email);
        a.setAction("TEST");
        return a;
    }
}
