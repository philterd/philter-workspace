/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.api.controller;

import ai.philterd.workspace.dto.SpanUpdateRequest;
import ai.philterd.workspace.model.BlindReviewSpan;
import ai.philterd.workspace.model.Coordinates;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.PiiTypes;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.BlindReviewSpanRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.BlindDoubleReviewService;
import ai.philterd.workspace.service.DetectionRuleService;
import ai.philterd.workspace.service.DocumentAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "APPROVED", "REJECTED", "PENDING", Span.STATUS_NEEDS_SECOND_OPINION);

    private final SpanRepository spanRepository;
    private final DocumentRepository documentRepository;
    private final DocumentAccessService documentAccessService;
    private final AuditLogService auditLogService;
    private final DetectionRuleService detectionRuleService;

    private final ai.philterd.workspace.service.DocumentChangeGuard changeGuard;
    private final BlindReviewSpanRepository copyRepository;
    private final BlindDoubleReviewService blindReview;

    public ReviewController(final SpanRepository spanRepository,
                            final DocumentRepository documentRepository,
                            final DocumentAccessService documentAccessService,
                            final AuditLogService auditLogService,
                            final DetectionRuleService detectionRuleService,
                            final ai.philterd.workspace.service.DocumentChangeGuard changeGuard,
                            final BlindReviewSpanRepository copyRepository,
                            final BlindDoubleReviewService blindReview) {
        this.spanRepository = spanRepository;
        this.documentRepository = documentRepository;
        this.documentAccessService = documentAccessService;
        this.auditLogService = auditLogService;
        this.detectionRuleService = detectionRuleService;
        this.changeGuard = changeGuard;
        this.copyRepository = copyRepository;
        this.blindReview = blindReview;
    }

    /**
     * The span a request names, with its document: one of the document's spans, or a blind second review's copy
     * (#86), which only the person doing that review can reach. Anyone else is told the span does not exist.
     */
    private record Target(Span span, Document document, boolean copy) {
    }

    private Target target(final String id, final Authentication authentication) {
        final Span span = spanRepository.findById(id).orElse(null);
        if (span != null) {
            return new Target(span, documentAccessService.loadAccessibleParentForSpan(span, authentication), false);
        }
        final BlindReviewSpan copy = copyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Span not found."));
        final Document document = documentAccessService.loadAccessibleParentForSpan(copy, authentication);
        if (!blindReview.isBlindReviewer(authentication, document)) {
            throw new ResponseStatusException(NOT_FOUND, "Span not found.");
        }
        return new Target(copy, document, true);
    }

    /** The other spans of the same review: the document's, or the blind review's copies. */
    private List<? extends Span> siblings(final Target t) {
        return t.copy() ? copyRepository.findByDocumentId(t.document().getId())
                : spanRepository.findByDocumentId(t.document().getId());
    }

    private Span save(final Target t, final Span span) {
        return t.copy() ? copyRepository.save((BlindReviewSpan) span) : spanRepository.save(span);
    }

    /**
     * Audits a change to {@code t}'s span. A change in a blind review says so, and leaves the document's approvals
     * alone, since the document's spans did not change.
     */
    private void audited(final Target t, final String action, final String spanId, final Map<String, Object> details,
                         final String actor, final String change) {
        final Map<String, Object> all = new LinkedHashMap<>(details);
        if (t.copy()) {
            all.put("blindReview", true);
        }
        auditLogService.log(action, "Span", spanId, all);
        if (!t.copy() && change != null) {
            changeGuard.spansChanged(t.document(), actor, change, spanId);
        }
    }

    /** What a blind review cannot do: adjudicate, which is an approver's, or ask for a second opinion (#86). */
    private static void requireBlindChange(final Boolean adjudicate, final String status) {
        if (Boolean.TRUE.equals(adjudicate)) {
            throw new ResponseStatusException(BAD_REQUEST, "A blind review does not adjudicate spans.");
        }
        if (Span.STATUS_NEEDS_SECOND_OPINION.equals(status)) {
            throw new ResponseStatusException(BAD_REQUEST, "A blind review cannot ask for a second opinion.");
        }
    }

    /**
     * Spans on a document in a terminal status are read-only. APPROVED / REJECTED documents
     * can be reopened (Unapprove / Unreject). FINALIZED is a permanent terminal state — no
     * further edits are accepted via the API.
     */
    /**
     * Spans change only on an editable document that no one else is reviewing (#71). A change while another person
     * holds the review lock is refused.
     */
    private void requireChangeable(final Document document, final Authentication authentication) {
        requireEditable(document);
        changeGuard.requireNotHeldByOther(document, authentication == null ? null : authentication.getName());
    }

    private static void requireEditable(final Document document) {
        if ("APPROVED".equals(document.getStatus())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Document is APPROVED. Unapprove it before adding, editing, or deleting spans.");
        }
        if ("REJECTED".equals(document.getStatus())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Document is REJECTED. Unreject it before adding, editing, or deleting spans.");
        }
        if ("FINALIZED".equals(document.getStatus())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Document is FINALIZED. Finalized documents cannot be modified.");
        }
    }

    /**
     * The adjudication rules (#5). Only an approver of the document's group may adjudicate, and only an approver
     * may change a span an approver adjudicated; an approver's change to such a span is a new adjudication.
     * Returns whether the change is recorded as an adjudication.
     */
    private boolean requireMayChange(final Span span, final Document document, final Authentication authentication,
                                     final boolean adjudicate) {
        final boolean adjudicated = span.getAdjudicatedBy() != null;
        if (!adjudicate && !adjudicated) {
            return false;
        }
        if (!documentAccessService.canApproveDocument(authentication, document)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, adjudicate
                    ? "Only an approver of this document's group can adjudicate a span."
                    : "An approver made the final decision on this span. Only an approver of this document's "
                            + "group can change it.");
        }
        return true;
    }

    @GetMapping("/documents/{id}/spans")
    public List<Span> getSpans(@PathVariable final String id, final Authentication authentication) {
        final Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        if (document.getOriginalText() == null || document.getOriginalText().isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Document source has been deleted by the batch's finalization policy "
                            + "and is no longer available for review.");
        }
        if (blindReview.isBlindReviewer(authentication, document)) {
            return new ArrayList<>(blindReview.copies(id));
        }
        // Whoever is shown the document's spans cannot then do its blind second review (#86).
        blindReview.noteSeen(authentication, document);
        return spanRepository.findByDocumentId(id);
    }

    public record CreateSpanRequest(String type, Integer start, Integer end) {}

    @PostMapping(value = "/documents/{documentId}/spans",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Span createSpan(@PathVariable final String documentId,
                           @RequestBody final CreateSpanRequest request,
                           final Authentication authentication) {
        if (request == null || request.type() == null || request.type().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "type is required.");
        }
        if (request.start() == null || request.end() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "start and end are required.");
        }
        final int start = request.start();
        final int end = request.end();
        if (start < 0 || end <= start) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid range.");
        }
        final String normalized = request.type().trim().toLowerCase();
        if (!PiiTypes.isValid(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid PII type: " + request.type());
        }
        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);
        // The blind reviewer adds to their own copy (#86).
        final boolean copy = blindReview.isBlindReviewer(authentication, document);
        if (!copy) {
            requireChangeable(document, authentication);
        }
        final String original = document.getOriginalText() == null ? "" : document.getOriginalText();
        if (end > original.length()) {
            throw new ResponseStatusException(BAD_REQUEST, "Range exceeds document length.");
        }
        final String text = original.substring(start, end);
        if (text.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Selection is empty.");
        }

        final Span span = copy ? new BlindReviewSpan() : new Span();
        span.setId(UUID.randomUUID().toString());
        span.setDocumentId(documentId);
        span.setType(normalized);
        span.setText(text);
        span.setConfidence(1.0);
        span.setLocation(new Location(start, end, 1, new Coordinates(0, 0, 0, 0)));
        span.setManuallyCreated(true);
        span.setCreatedAt(LocalDateTime.now());
        span.changeStatus("APPROVED", authentication == null ? null : authentication.getName());
        final Target t = new Target(span, document, copy);
        final Span saved = save(t, span);

        audited(t, "SPAN_CREATE", saved.getId(),
                Map.of("spanId", saved.getId(),
                        "documentId", documentId,
                        "type", normalized,
                        "start", start,
                        "end", end,
                        "length", text.length()),
                authentication == null ? null : authentication.getName(), "SPAN_CREATE");
        return saved;
    }

    @PatchMapping(value = "/spans/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Span updateSpan(@PathVariable final String id,
                           @RequestBody final SpanUpdateRequest request,
                           final Authentication authentication) {
        final boolean moving = request != null && (request.start() != null || request.end() != null);
        if (request == null || (request.status() == null && request.type() == null && !moving)) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "At least one of 'status', 'type', or 'start' and 'end' must be provided.");
        }
        if (moving && (request.start() == null || request.end() == null)) {
            throw new ResponseStatusException(BAD_REQUEST, "'start' and 'end' must be given together.");
        }
        // Moving a span's boundaries also accepts it (#20).
        if (moving && request.status() != null && !"APPROVED".equals(request.status())) {
            throw new ResponseStatusException(BAD_REQUEST, "Moving a span's boundaries accepts it.");
        }
        final String status = moving ? "APPROVED" : request.status();
        final Target t = target(id, authentication);
        final Span span = t.span();
        final Document document = t.document();
        final boolean adjudicating;
        if (t.copy()) {
            requireBlindChange(request.adjudicate(), status);
            adjudicating = false;
        } else {
            requireChangeable(document, authentication);
            adjudicating = requireMayChange(span, document, authentication,
                    Boolean.TRUE.equals(request.adjudicate()));
        }
        if (Boolean.TRUE.equals(request.adjudicate())
                && !"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            throw new ResponseStatusException(BAD_REQUEST, "An adjudication accepts or refuses the span.");
        }
        final int[] previousRange = {span.getLocation().characterStart(), span.getLocation().characterEnd()};
        final String movedText = moving
                ? requireMovable(span, document, siblings(t), request.start(), request.end()) : null;

        final String actor = authentication == null ? null : authentication.getName();
        final Map<String, Object> changes = new LinkedHashMap<>();
        if (status != null) {
            if (!ALLOWED_STATUSES.contains(status)) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid status: " + status);
            }
            // Overturn check: moving a span *out of* APPROVED, where the prior approval
            // was recorded by a different reviewer, requires a reason for the audit trail.
            final boolean leavingApproved = "APPROVED".equals(span.getStatus())
                    && !"APPROVED".equals(status);
            final String priorActor = span.getStatusChangedBy();
            final boolean differentReviewer = priorActor != null && actor != null
                    && !priorActor.equalsIgnoreCase(actor);
            final String reason = request.reason() == null ? "" : request.reason().trim();
            if (leavingApproved && differentReviewer && reason.isEmpty()) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                        "OVERTURN_REASON_REQUIRED: a reason is required to overturn another reviewer's approval.");
            }
            changes.put("previousStatus", span.getStatus() == null ? "" : span.getStatus());
            changes.put("status", status);
            if (priorActor != null) {
                changes.put("previousStatusChangedBy", priorActor);
            }
            if (leavingApproved && differentReviewer) {
                changes.put("overturn", true);
                changes.put("reason", reason);
            } else if (!reason.isEmpty()) {
                // Reason supplied without an overturn — record it for traceability.
                changes.put("reason", reason);
            }
            span.changeStatus(status, actor);
            if ("APPROVED".equals(status)) {
                final String code = request.exemptionCode() == null ? null : request.exemptionCode().trim();
                span.setExemptionCode(code == null || code.isEmpty() ? null : code);
                if (span.getExemptionCode() != null) {
                    changes.put("exemptionCode", span.getExemptionCode());
                }
            } else {
                span.setExemptionCode(null);
            }
        }
        if (request.type() != null) {
            final String normalized = request.type().trim().toLowerCase();
            if (!PiiTypes.isValid(normalized)) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid PII type: " + request.type());
            }
            changes.put("previousType", span.getType() == null ? "" : span.getType());
            changes.put("type", normalized);
            span.setType(normalized);
        }
        final String previousStatus = (String) changes.get("previousStatus");
        if (adjudicating) {
            if (span.getAdjudicatedBy() != null) {
                changes.put("previousAdjudicatedBy", span.getAdjudicatedBy());
            }
            span.setAdjudicatedBy(actor);
            span.setAdjudicatedAt(LocalDateTime.now());
        }
        if (movedText != null) {
            move(span, request.start(), request.end(), movedText, actor);
        }
        final Span saved = save(t, span);
        if (movedText != null && (previousRange[0] != request.start() || previousRange[1] != request.end())) {
            audited(t, "SPAN_ADJUST", saved.getId(), adjustment(saved, previousRange), actor, null);
        }
        changes.put("spanId", saved.getId());
        changes.put("documentId", saved.getDocumentId() == null ? "" : saved.getDocumentId());
        audited(t, adjudicating ? "SPAN_ADJUDICATE" : "SPAN_UPDATE", saved.getId(), changes, actor,
                movedText != null ? "SPAN_ADJUST" : "SPAN_UPDATE");
        // A refusal in a blind review does not count toward proposing a rule, so one value is not counted twice.
        if (!t.copy() && "REJECTED".equals(status) && !"REJECTED".equals(previousStatus)) {
            detectionRuleService.noteRefusal(saved, document);
        }
        return saved;
    }

    /**
     * Checks a span can be moved to {@code [start, end)} of the document's text and returns the text there. Not for
     * a span in a PDF, whose position on the page would no longer match (that follows PDF review, #58), and never
     * onto another span.
     */
    private String requireMovable(final Span span, final Document document, final List<? extends Span> siblings,
                                  final int start, final int end) {
        final ai.philterd.workspace.model.Coordinates c = span.getLocation().coordinates();
        if (c != null && (c.w() > 0 || c.h() > 0)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "A span in a PDF cannot be moved yet.");
        }
        final String original = document.getOriginalText() == null ? "" : document.getOriginalText();
        if (start < 0 || end <= start || end > original.length()) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid range.");
        }
        final String text = original.substring(start, end);
        if (text.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "The new range is empty.");
        }
        for (Span other : siblings) {
            if (other.getId() == null || other.getId().equals(span.getId()) || other.getLocation() == null) continue;
            if (start < other.getLocation().characterEnd() && end > other.getLocation().characterStart()) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                        "The new range overlaps another span.");
            }
        }
        return text;
    }

    /** Moves the span, first recording where it was detected so the link to the detection is kept. */
    private static void move(final Span span, final int start, final int end, final String text, final String actor) {
        if (span.getOriginalCharacterStart() == null) {
            span.setOriginalCharacterStart(span.getLocation().characterStart());
            span.setOriginalCharacterEnd(span.getLocation().characterEnd());
        }
        span.setLocation(new Location(start, end, span.getLocation().page(), span.getLocation().coordinates()));
        span.setText(text);
        span.setAdjusted(start != span.getOriginalCharacterStart() || end != span.getOriginalCharacterEnd());
        span.setAdjustedBy(actor);
        span.setAdjustedAt(LocalDateTime.now());
    }

    /** Before and after positions for the audit log; never the text, which is personal information. */
    private static Map<String, Object> adjustment(final Span span, final int[] previous) {
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("spanId", span.getId());
        details.put("documentId", span.getDocumentId() == null ? "" : span.getDocumentId());
        details.put("previousStart", previous[0]);
        details.put("previousEnd", previous[1]);
        details.put("start", span.getLocation().characterStart());
        details.put("end", span.getLocation().characterEnd());
        details.put("originalStart", span.getOriginalCharacterStart());
        details.put("originalEnd", span.getOriginalCharacterEnd());
        details.put("length", span.getLocation().characterEnd() - span.getLocation().characterStart());
        return details;
    }

    public record RuleRequest(String kind) {}

    /**
     * Makes a rule from a span: {@code NEVER} ("Don't flag this again") from a refused span, {@code ALWAYS}
     * ("Always flag this") from one added by hand. Allowed whatever the document's status, since a rule changes
     * later documents, not this one. The answer's {@code message} says whether the rule is now in Philter or
     * waits to be confirmed, or why it was refused.
     */
    @PostMapping(value = "/spans/{id}/rule", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> makeRule(@PathVariable final String id,
                                                        @RequestBody final RuleRequest request,
                                                        final Authentication authentication) {
        final Target t = target(id, authentication);
        final Span span = t.span();
        final Document document = t.document();
        try {
            final DetectionRuleService.Made made = detectionRuleService.makeFromSpan(
                    request == null ? null : request.kind(), span, document, authentication);
            return ResponseEntity.ok(Map.of("status", made.rule().getStatus(), "message", made.message()));
        } catch (DetectionRuleService.RuleException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping(value = "/spans/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteSpan(@PathVariable final String id, final Authentication authentication) {
        final Target t = target(id, authentication);
        final Span span = t.span();
        final Document document = t.document();
        if (!t.copy()) {
            requireChangeable(document, authentication);
        }

        if (!span.isManuallyCreated()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Only manually-created spans can be deleted. "
                            + "Use the Refused toggle to ignore a detected span.");
        }
        if (!t.copy()) {
            requireMayChange(span, document, authentication, false);
        }

        if (t.copy()) {
            copyRepository.deleteById(id);
        } else {
            spanRepository.deleteById(id);
        }
        audited(t, "SPAN_DELETE", id,
                Map.of("spanId", id,
                        "documentId", document.getId(),
                        "type", span.getType() == null ? "" : span.getType()),
                authentication == null ? null : authentication.getName(), "SPAN_DELETE");
        return Map.of("id", id, "deleted", true);
    }

    /** The shortest span text, after trimming, that Redact All Like This matches (#75). */
    public static final int REDACT_LIKE_MIN_LENGTH = 3;
    /** The most spans one Redact All Like This creates or approves before it stops (#75). */
    public static final int REDACT_LIKE_MAX_MATCHES = 500;

    /** A Redact All Like This the reviewer can correct, answered with its reason (see {@link #redactLikeRefused}). */
    public static final class RedactLikeRefused extends RuntimeException {
        public RedactLikeRefused(final String message) {
            super(message);
        }
    }

    /**
     * Error messages are left out of error responses ({@code spring.web.error.include-message=never}), so this
     * refusal carries its reason in the body for the review page to show.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(RedactLikeRefused.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> redactLikeRefused(final RedactLikeRefused e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    /**
     * {@code consumes = application/json} is a CSRF defence: a cross-site simple
     * form POST sends form-urlencoded data, which the JSON content-type
     * requirement rejects up front. The browser UI's fetch already sends an
     * empty JSON content-type header, so legitimate calls are unaffected.
     *
     * <p>Limited so one click cannot flood a document with spans or hold a request thread (#75): the span's text
     * must be at least {@value #REDACT_LIKE_MIN_LENGTH} characters, and at most {@value #REDACT_LIKE_MAX_MATCHES}
     * spans are created or approved, with {@code limitReached} saying when it stopped. Clicking again continues,
     * since a match that is already a person's approval with the same type is not counted.
     */
    @PostMapping(value = "/spans/{id}/redact-like",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> redactAllLike(@PathVariable final String id, final Authentication authentication) {
        final Target t = target(id, authentication);
        final Span source = t.span();

        // Access check runs *before* any other validation so a non-admin can't
        // distinguish "real but inaccessible span" from "no such span" by reading the
        // status code: an empty-text span used to give 400 while a missing id gave 404.
        // Now both surface as the same uniform "Span not found." 404 — see fix #4 for
        // the cross-controller contract this restores.
        final Document document = t.document();
        if (!t.copy()) {
            requireChangeable(document, authentication);
        }

        final String needle = source.getText();
        if (needle == null || needle.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Source span has no text to match.");
        }
        if (needle.trim().length() < REDACT_LIKE_MIN_LENGTH) {
            throw new RedactLikeRefused("Redact All Like This needs a span of at least " + REDACT_LIKE_MIN_LENGTH
                    + " characters, so it does not match every short run of text in the document.");
        }
        // Redact All Like This is only meaningful while the source span is still a redaction
        // candidate. If the reviewer refused it or flagged it for a second opinion, the action
        // is disabled — propagating the source's redaction across the document doesn't make
        // sense in either case.
        if ("REJECTED".equals(source.getStatus())
                || Span.STATUS_NEEDS_SECOND_OPINION.equals(source.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Redact All Like This is only available for approved or pending spans.");
        }
        if (!t.copy()) {
            requireMayChange(source, document, authentication, false);
        }
        final String haystack = document.getOriginalText();
        if (haystack == null || haystack.isEmpty()) {
            return Map.of("created", 0, "approved", 0, "adjudicatedSkipped", 0, "limitReached", false,
                    "limit", REDACT_LIKE_MAX_MATCHES);
        }

        final List<? extends Span> existing = siblings(t);
        final Map<Long, Span> byRange = new HashMap<>();
        final List<long[]> existingRanges = new ArrayList<>();
        for (Span s : existing) {
            if (s.getLocation() == null) continue;
            final long start = s.getLocation().characterStart();
            final long end = s.getLocation().characterEnd();
            byRange.put((start << 32) | (end & 0xFFFFFFFFL), s);
            existingRanges.add(new long[]{start, end});
        }
        final Ranges occupied = new Ranges(existingRanges);

        final String actor = authentication == null ? null : authentication.getName();
        source.changeStatus("APPROVED", actor);
        if (source.getAdjudicatedBy() != null) {
            source.setAdjudicatedBy(actor);
            source.setAdjudicatedAt(LocalDateTime.now());
        }
        save(t, source);

        final List<Span> toSave = new ArrayList<>();
        int created = 0;
        int approved = 0;
        int adjudicatedSkipped = 0;
        boolean limitReached = false;
        int cursor = 0;
        while (true) {
            final int idx = haystack.indexOf(needle, cursor);
            if (idx < 0) break;
            if (created + approved >= REDACT_LIKE_MAX_MATCHES) {
                // Another match remains; the reviewer is told, and clicking again continues from here.
                limitReached = true;
                break;
            }
            final int end = idx + needle.length();
            cursor = end;

            if (idx == source.getLocation().characterStart() && end == source.getLocation().characterEnd()) {
                continue;
            }

            final Span exact = byRange.get(((long) idx << 32) | (end & 0xFFFFFFFFL));
            if (exact != null && exact.getAdjudicatedBy() != null) {
                // An approver's final decision is changed one span at a time, never in bulk.
                adjudicatedSkipped++;
                continue;
            }
            if (exact != null && "APPROVED".equals(exact.getStatus()) && exact.decidedByPerson()
                    && java.util.Objects.equals(exact.getType(), source.getType())) {
                // Already a person's approval of this type: not counted, so a second click continues past the limit.
                continue;
            }
            if (exact != null) {
                exact.changeStatus("APPROVED", actor);
                exact.setType(source.getType());
                toSave.add(exact);
                approved++;
                continue;
            }

            if (occupied.overlaps(idx, end)) {
                continue;
            }

            final Span fresh = t.copy() ? new BlindReviewSpan() : new Span();
            fresh.setId(UUID.randomUUID().toString());
            fresh.setDocumentId(source.getDocumentId());
            fresh.setType(source.getType());
            fresh.setText(needle);
            fresh.setConfidence(1.0);
            fresh.setLocation(new Location(idx, end, 1, new Coordinates(0, 0, 0, 0)));
            fresh.setManuallyCreated(true);
            fresh.setCreatedAt(LocalDateTime.now());
            fresh.changeStatus("APPROVED", actor);
            toSave.add(fresh);
            // Matches do not overlap one another (the search resumes at this one's end), so a new span never
            // needs adding to the ranges checked for later matches.
            created++;
        }

        if (!toSave.isEmpty()) {
            if (t.copy()) {
                copyRepository.saveAll(toSave.stream().map(BlindReviewSpan.class::cast).toList());
            } else {
                spanRepository.saveAll(toSave);
            }
        }

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("approved", approved);
        result.put("adjudicatedSkipped", adjudicatedSkipped);
        result.put("limitReached", limitReached);
        result.put("limit", REDACT_LIKE_MAX_MATCHES);

        final Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("spanId", source.getId());
        auditDetails.put("documentId", source.getDocumentId());
        auditDetails.put("type", source.getType() == null ? "" : source.getType());
        auditDetails.put("created", created);
        auditDetails.put("approved", approved);
        auditDetails.put("adjudicatedSkipped", adjudicatedSkipped);
        auditDetails.put("limitReached", limitReached);
        audited(t, "SPAN_REDACT_LIKE", source.getId(), auditDetails, actor, "SPAN_REDACT_LIKE");
        return result;
    }

    public record ResetSpanRequest(String originalStatus) {}

    @PostMapping(value = "/spans/{id}/reset",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Span resetSpan(@PathVariable final String id,
                          @RequestBody(required = false) final ResetSpanRequest request,
                          final Authentication authentication) {
        final Target t = target(id, authentication);
        final Span span = t.span();
        final Document document = t.document();
        final String originalStatus = request == null ? null : request.originalStatus();
        if (t.copy()) {
            requireBlindChange(false, originalStatus);
        } else {
            requireChangeable(document, authentication);
            requireMayChange(span, document, authentication, false);
        }

        final String actor = authentication == null ? null : authentication.getName();
        final String previousStatus = span.getStatus() == null ? "" : span.getStatus();

        if (originalStatus == null || !ALLOWED_STATUSES.contains(originalStatus)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid status: " + originalStatus);
        }
        // Back to the detection as it arrived (#20): its original boundaries too, unless a span added since
        // covers part of them.
        final int[] previousRange = {span.getLocation().characterStart(), span.getLocation().characterEnd()};
        final boolean restoring = span.isAdjusted() && span.getOriginalCharacterStart() != null;
        final String restoredText = restoring
                ? requireMovable(span, document, siblings(t), span.getOriginalCharacterStart(),
                        span.getOriginalCharacterEnd())
                : null;
        span.changeStatus(originalStatus, actor);
        if (restoring) {
            move(span, span.getOriginalCharacterStart(), span.getOriginalCharacterEnd(), restoredText, actor);
        }
        // Back to unreviewed: an approver resetting an adjudicated span withdraws the adjudication.
        final String withdrawn = span.getAdjudicatedBy();
        span.setAdjudicatedBy(null);
        span.setAdjudicatedAt(null);
        final Span saved = save(t, span);

        final Map<String, Object> details = new LinkedHashMap<>();
        if (withdrawn != null) {
            details.put("previousAdjudicatedBy", withdrawn);
        }
        details.put("spanId", saved.getId());
        details.put("documentId", saved.getDocumentId() == null ? "" : saved.getDocumentId());
        details.put("previousStatus", previousStatus);
        details.put("originalStatus", originalStatus == null ? "" : originalStatus);
        audited(t, "SPAN_RESET", saved.getId(), details, actor, null);
        if (restoring) {
            audited(t, "SPAN_ADJUST", saved.getId(), adjustment(saved, previousRange), actor, null);
        }
        if (!t.copy()) {
            changeGuard.spansChanged(document, actor, "SPAN_RESET", saved.getId());
        }
        return saved;
    }

    /**
     * The spans already in a document, for asking whether a range overlaps any of them in logarithmic time (#75).
     * Sorted by start, with the greatest end among each prefix: a range [start, end) overlaps a span exactly when
     * some span starting before {@code end} ends after {@code start}, that is when the greatest end among the spans
     * starting before {@code end} is past {@code start}.
     */
    static final class Ranges {
        private final long[] starts;
        private final long[] maxEnds;

        Ranges(final List<long[]> ranges) {
            final List<long[]> sorted = new ArrayList<>(ranges);
            sorted.sort(java.util.Comparator.comparingLong(r -> r[0]));
            starts = new long[sorted.size()];
            maxEnds = new long[sorted.size()];
            long max = Long.MIN_VALUE;
            for (int i = 0; i < sorted.size(); i++) {
                starts[i] = sorted.get(i)[0];
                max = Math.max(max, sorted.get(i)[1]);
                maxEnds[i] = max;
            }
        }

        boolean overlaps(final long start, final long end) {
            // The last span starting before end.
            int lo = 0;
            int hi = starts.length - 1;
            int last = -1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                if (starts[mid] < end) {
                    last = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return last >= 0 && maxEnds[last] > start;
        }
    }
}
