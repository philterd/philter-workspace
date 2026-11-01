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

import ai.philterd.workspace.model.BackgroundJob;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.LocalDirectoryDestination;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.BackgroundJobRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.LocalDirectoryDestinationRepository;
import ai.philterd.workspace.repository.S3DestinationRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exports leave out, or mark, spans the PII Threshold accepted without a person deciding them (#40). */
class BatchExportThresholdAcceptedTest {

    private static final String TEXT = "Call Jane Roe today.";

    private final List<String> written = new ArrayList<>();
    private final BackgroundJob job = new BackgroundJob();
    private DocumentRepository documentRepository;
    private SpanRepository spanRepository;
    private AuditLogService auditLogService;
    private BatchExportService service;

    @BeforeEach
    void setUp() throws Exception {
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Claims");
        final BatchRepository batchRepository = mock(BatchRepository.class);
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        documentRepository = mock(DocumentRepository.class);
        when(documentRepository.findByBatchIdAndStatus(eq("b1"), eq("APPROVED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(doc("decided"), doc("guessed"), doc("negative"), doc("refused"))));
        spanRepository = mock(SpanRepository.class);
        when(spanRepository.findByDocumentId("decided")).thenReturn(List.of(span("APPROVED", "rev@example.com")));
        when(spanRepository.findByDocumentId("guessed")).thenReturn(List.of(span("APPROVED", null)));
        when(spanRepository.findByDocumentId("negative")).thenReturn(List.of());
        // A threshold-accepted span a person refused is not exported, so it does not exclude the document.
        when(spanRepository.findByDocumentId("refused")).thenReturn(List.of(span("REJECTED", "rev@example.com")));

        final LocalDirectoryDestinationRepository localRepository = mock(LocalDirectoryDestinationRepository.class);
        when(localRepository.findById("dest")).thenReturn(Optional.of(new LocalDirectoryDestination()));
        final DestinationWriter writer = mock(DestinationWriter.class);
        // The combined file is deleted once shipped, so read it while it is written.
        when(writer.writeLocal(any(), anyString(), any(Path.class))).thenAnswer(inv -> {
            written.addAll(Files.readAllLines(inv.getArgument(2, Path.class), StandardCharsets.UTF_8));
            return DestinationWriter.Result.success("ok");
        });
        when(writer.writeLocal(any(), anyString(), any(byte[].class))).thenAnswer(inv -> {
            written.add(inv.getArgument(1, String.class));
            return DestinationWriter.Result.success("ok");
        });

        final BackgroundJobRepository jobRepository = mock(BackgroundJobRepository.class);
        job.setId("job");
        job.setBatchId("b1");
        job.setDestinationKind("LOCAL");
        job.setDestinationId("dest");
        job.setStatus(BackgroundJob.STATUS_RUNNING);
        when(jobRepository.findById("job")).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditLogService = mock(AuditLogService.class);
        service = new BatchExportService(batchRepository, documentRepository, spanRepository, localRepository,
                mock(S3DestinationRepository.class), new JsonlExportRenderer(), new BioExportRenderer(),
                new PheyeJsonlExportRenderer(), writer, auditLogService, jobRepository);
    }

    @Test
    void jsonlLeavesOutDocumentsWithThresholdAcceptedSpans() {
        run("JSONL", true);

        assertEquals(3, written.size(), String.join("\n", written));
        assertTrue(written.stream().noneMatch(line -> line.contains("\"doc_id\":\"guessed\"")));
        assertTrue(written.get(0).contains("\"decidedBy\":\"person\""), written.get(0));
        assertEquals(BackgroundJob.STATUS_COMPLETED, job.getStatus());
        assertEquals(3, job.getProcessedDocuments());
        assertEquals(1, job.getSkippedDocuments());
        assertAudited(true, 1);
    }

    @Test
    void jsonlCanIncludeThemMarked() {
        run("JSONL", false);

        assertEquals(4, written.size());
        final String guessed = written.stream().filter(l -> l.contains("\"doc_id\":\"guessed\"")).findFirst().orElseThrow();
        assertTrue(guessed.contains("\"decidedBy\":\"threshold\""), guessed);
        assertFalse(guessed.contains("rev@example.com"));
        assertEquals(0, job.getSkippedDocuments());
        assertAudited(false, 0);
    }

    @Test
    void pheyeAndBioLeaveThemOutToo() {
        run("PHEYE", true);
        assertEquals(3, written.size());
        assertEquals(1, job.getSkippedDocuments());

        written.clear();
        job.setStatus(BackgroundJob.STATUS_RUNNING);
        run("BIO", true);
        assertEquals(List.of("claims/decided.bio", "claims/negative.bio", "claims/refused.bio"), written);
        assertEquals(1, job.getSkippedDocuments());
    }

    @Test
    void anExportWithEveryDocumentLeftOutFailsAndSaysWhy() {
        when(documentRepository.findByBatchIdAndStatus(eq("b1"), eq("APPROVED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(doc("guessed"))));

        run("JSONL", true);

        assertEquals(BackgroundJob.STATUS_FAILED, job.getStatus());
        assertTrue(job.getErrorMessage().contains("no person decided"), job.getErrorMessage());
        assertTrue(written.isEmpty());
    }

    @Test
    void aDocumentWithAValueThatDidNotDecryptIsNotExported() {
        // #74: the placeholder must never be written out as a document's text or a span's.
        final Document unreadable = doc("unreadable");
        unreadable.setOriginalText(PiiFieldEncryption.UNDECRYPTABLE);
        final Span unreadableSpan = span("APPROVED", "rev@example.com");
        unreadableSpan.setText(PiiFieldEncryption.UNDECRYPTABLE);
        when(spanRepository.findByDocumentId("span-unreadable")).thenReturn(List.of(unreadableSpan));
        when(documentRepository.findByBatchIdAndStatus(eq("b1"), eq("APPROVED"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(doc("decided"), unreadable, doc("span-unreadable"))));

        for (String format : List.of("JSONL", "PHEYE", "BIO")) {
            written.clear();
            job.setStatus(BackgroundJob.STATUS_RUNNING);
            job.setFailedDocuments(0);
            run(format, false);
            assertEquals(1, written.size(), format + ": " + written);
            assertTrue(written.stream().noneMatch(l -> l.contains(PiiFieldEncryption.UNDECRYPTABLE)), format);
            assertEquals(2, job.getFailedDocuments(), format);
        }
    }

    private void run(final String format, final boolean exclude) {
        job.setDataFormat(format);
        job.setExcludeThresholdAccepted(exclude);
        service.runJob("job");
    }

    @SuppressWarnings("unchecked")
    private void assertAudited(final boolean exclude, final long skipped) {
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("BATCH_EXPORT"), eq("Batch"), eq("b1"), details.capture());
        assertEquals(exclude, details.getValue().get("excludeThresholdAccepted"));
        assertEquals(skipped, details.getValue().get("skippedDocuments"));
    }

    private static Document doc(final String id) {
        final Document d = new Document();
        d.setId(id);
        d.setBatchId("b1");
        d.setFilename(id + ".txt");
        d.setStatus("APPROVED");
        d.setOriginalText(TEXT);
        return d;
    }

    /** A detected (not hand-added) span over "Jane Roe"; a null actor means it was set at ingest. */
    private static Span span(final String status, final String actor) {
        final Span s = new Span();
        s.setType("person");
        s.setText("Jane Roe");
        s.setConfidence(0.95);
        s.setLocation(new Location(5, 13, 1, null));
        if (actor == null) {
            s.changeStatus(status);
        } else {
            s.changeStatus(status, actor);
        }
        return s;
    }
}
