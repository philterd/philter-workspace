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

import ai.philterd.workspace.core.model.Redaction;
import ai.philterd.workspace.core.model.RedactionResponse;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Coordinates;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.IngestStatus;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.RiskScore;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.service.DocumentSearchService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Applies a {@link RedactionResponse} to an existing {@link Document} — persists the spans, computes
 * the risk score, picks the ingest status (honoring the batch's audit sampling rate), and indexes the
 * document into OpenSearch. Used by both the synchronous web upload (now disabled) and the async
 * redaction worker so the post-redaction bookkeeping lives in one place.
 */
@Service
public class RedactionPersistenceService {

    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final DocumentSearchService searchService;

    public RedactionPersistenceService(final DocumentRepository documentRepository,
                                       final SpanRepository spanRepository,
                                       final DocumentSearchService searchService) {
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.searchService = searchService;
    }

    public Document apply(final Document document, final Batch batch, final RedactionResponse response) {
        final String originalText = response.getOriginalText() == null ? "" : response.getOriginalText();
        final List<Redaction> redactions = response.getRedactions() == null ? List.of() : response.getRedactions();
        final double threshold = batch.getConfidenceThreshold();

        document.setOriginalText(originalText);
        document.recordPhilterDocumentId(response.getPhilterDocumentId());
        document.setAppliedPolicyName(response.getAppliedPolicyName());
        document.setAppliedPolicyVersion(response.getAppliedPolicyVersion());
        // A retried document no longer carries the failure from its earlier attempt.
        document.setFailureMessage(null);
        // Stable random roll used by the dual-approval sampling rule. Generated once at persist
        // time so re-reads of the same document never flip the sampling decision.
        if (document.getDualApprovalSamplingRoll() == null) {
            document.setDualApprovalSamplingRoll(
                    java.util.concurrent.ThreadLocalRandom.current().nextDouble());
        }
        // Blind Double Review selection: when the parent batch has the feature enabled, draw
        // an independent Bernoulli sample with the configured percentage. Set at most once —
        // re-runs of redaction on the same document keep the original decision so the cohort
        // of double-review documents is stable for the lifetime of the batch.
        if (batch.isBlindDoubleReviewEnabled() && !document.isDoubleReview()) {
            final int pct = batch.getBlindDoubleReviewPercentage();
            final double p = Math.max(0, Math.min(100, pct)) / 100.0;
            if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < p) {
                document.setDoubleReview(true);
            }
        }

        final List<Span> spans = new ArrayList<>();
        if (!redactions.isEmpty()) {
            final List<Redaction> ordered = new ArrayList<>(redactions);
            ordered.sort(Comparator.comparingInt(Redaction::getStart));
            int cursor = 0;
            for (Redaction r : ordered) {
                final int start = originalText.indexOf(r.getText(), cursor);
                if (start < 0) {
                    continue;
                }
                final int end = start + r.getText().length();
                final LocalDateTime now = LocalDateTime.now();
                final Span span = new Span();
                span.setId(UUID.randomUUID().toString());
                span.setDocumentId(document.getId());
                span.setType(r.getType());
                span.setText(r.getText());
                span.setConfidence(r.getConfidence());
                span.setCreatedAt(now);
                span.changeStatus(r.getConfidence() >= threshold ? "APPROVED" : "PENDING");
                span.setLocation(new Location(start, end, Math.max(r.getPageNumber(), 1),
                        new Coordinates(r.getLowerLeftX(), r.getLowerLeftY(),
                                r.getUpperRightX() - r.getLowerLeftX(),
                                r.getUpperRightY() - r.getLowerLeftY())));
                spans.add(span);
                cursor = end;
            }
        }
        document.setRiskScore(RiskScore.compute(spans, originalText, batch.getPiiTypeWeights()));

        final boolean needsReview = !spans.isEmpty()
                && spans.stream().anyMatch(s -> "PENDING".equals(s.getStatus()));
        document.changeStatus(IngestStatus.pick(batch, needsReview));
        // The blind second review starts from the detections as they arrived (#86), kept until it is started.
        if (document.isDoubleReview() && document.getSecondReviewer() == null
                && document.getSecondReviewSpans() == null) {
            document.setDoubleReviewDetections(BlindDoubleReviewService.detections(spans));
        }
        documentRepository.save(document);
        // A retried document may hold spans from an attempt that failed after saving them (for
        // example while indexing), so they are replaced rather than added to.
        if (document.getId() != null) {
            spanRepository.deleteByDocumentId(document.getId());
        }
        if (!spans.isEmpty()) {
            spanRepository.saveAll(spans);
        }
        searchService.indexDocument(document);
        return document;
    }
}
