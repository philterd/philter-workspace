/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.repository;

import ai.philterd.workspace.model.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends WorkspaceRepository<Document, String> {
    /** A batch's documents in {@code status} whose source a finalization policy has not cleared yet (#68). */
    List<Document> findByBatchIdAndStatusAndSourceClearedAtIsNull(String batchId, String status);
    Page<Document> findByStatus(String status, Pageable pageable);
    Page<Document> findByBatchId(String batchId, Pageable pageable);
    Page<Document> findByBatchIdAndStatus(String batchId, String status, Pageable pageable);
    Page<Document> findByBatchIdAndStatusIn(String batchId, java.util.Collection<String> statuses, Pageable pageable);
    Page<Document> findByBatchIdIn(java.util.Collection<String> batchIds, Pageable pageable);
    Page<Document> findByBatchIdInAndStatus(java.util.Collection<String> batchIds, String status, Pageable pageable);

    Page<Document> findByFilenameContainingIgnoreCase(String filename, Pageable pageable);
    Page<Document> findByStatusAndFilenameContainingIgnoreCase(String status, String filename, Pageable pageable);
    Page<Document> findByBatchIdAndFilenameContainingIgnoreCase(String batchId, String filename, Pageable pageable);
    Page<Document> findByBatchIdAndStatusAndFilenameContainingIgnoreCase(String batchId, String status, String filename, Pageable pageable);
    Page<Document> findByBatchIdInAndFilenameContainingIgnoreCase(java.util.Collection<String> batchIds, String filename, Pageable pageable);
    Page<Document> findByBatchIdInAndStatusAndFilenameContainingIgnoreCase(java.util.Collection<String> batchIds, String status, String filename, Pageable pageable);

    // Variants used by the Document Queue to hide ingest-queue-only statuses (PENDING /
    // PROCESSING) when the user has not explicitly filtered by status.
    Page<Document> findByStatusNotIn(java.util.Collection<String> excludedStatuses, Pageable pageable);
    Page<Document> findByStatusNotInAndFilenameContainingIgnoreCase(java.util.Collection<String> excludedStatuses, String filename, Pageable pageable);
    Page<Document> findByBatchIdAndStatusNotIn(String batchId, java.util.Collection<String> excludedStatuses, Pageable pageable);
    Page<Document> findByBatchIdAndStatusNotInAndFilenameContainingIgnoreCase(String batchId, java.util.Collection<String> excludedStatuses, String filename, Pageable pageable);
    Page<Document> findByBatchIdInAndStatusNotIn(java.util.Collection<String> batchIds, java.util.Collection<String> excludedStatuses, Pageable pageable);
    Page<Document> findByBatchIdInAndStatusNotInAndFilenameContainingIgnoreCase(java.util.Collection<String> batchIds, java.util.Collection<String> excludedStatuses, String filename, Pageable pageable);
    long countByBatchId(String batchId);
    long countByBatchIdAndStatus(String batchId, String status);
    long countByBatchIdAndStatusIn(String batchId, java.util.Collection<String> statuses);
    long countByCreatedAtAfter(java.time.LocalDateTime cutoff);
    long countByStatus(String status);
    boolean existsBySourceIndexAndSourceDocId(String sourceIndex, String sourceDocId);
    List<Document> findByBatchId(String batchId);
    List<Document> findByDeliveryStatus(String deliveryStatus);
}
