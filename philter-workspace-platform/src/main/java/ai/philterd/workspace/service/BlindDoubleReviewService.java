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

import ai.philterd.workspace.model.BlindReviewSpan;
import ai.philterd.workspace.model.DetectedSpan;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.BlindReviewSpanRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Blind double review (#86). A document selected for it is reviewed twice, independently:
 * <ol>
 *   <li>The first review is the usual one, on the document's spans, ending in Approve or Reject.</li>
 *   <li>A second reviewer, who has not been shown the document's spans, starts the blind review. They work on their
 *       own copy of Philter's detections ({@link BlindReviewSpan}), all unreviewed, and submit it. Neither review
 *       sees or changes the other's decisions.</li>
 *   <li>An approver of the document's group, or an administrator, other than the two reviewers, compares the
 *       reviews, changes the document's spans where needed, and marks them reconciled.</li>
 * </ol>
 * The document cannot be finalized until the reviews are reconciled.
 */
@Service
public class BlindDoubleReviewService {

    /** What a person sees on the review page of a document in blind double review. */
    public enum View {
        /** The document's spans: the first review, or a document not (or no longer) in blind review. */
        RECORD,
        /** Their own blind review copy. */
        BLIND,
        /** The text without any spans, for someone who may yet do the blind review or must not see the first. */
        LANDING
    }

    /** Where the two reviews differ on one range: the type each redacts it as, or null where it is not redacted. */
    public record Disagreement(int start, int end, String text, String firstType, String secondType) {
    }

    private static final Set<String> REVIEWED = Set.of("APPROVED", "REJECTED");

    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final BlindReviewSpanRepository copyRepository;
    private final DocumentAccessService documentAccessService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public BlindDoubleReviewService(final DocumentRepository documentRepository,
                                    final SpanRepository spanRepository,
                                    final BlindReviewSpanRepository copyRepository,
                                    final DocumentAccessService documentAccessService,
                                    final AuditLogService auditLogService) {
        this(documentRepository, spanRepository, copyRepository, documentAccessService, auditLogService,
                Clock.systemUTC());
    }

    BlindDoubleReviewService(final DocumentRepository documentRepository,
                             final SpanRepository spanRepository,
                             final BlindReviewSpanRepository copyRepository,
                             final DocumentAccessService documentAccessService,
                             final AuditLogService auditLogService,
                             final Clock clock) {
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.copyRepository = copyRepository;
        this.documentAccessService = documentAccessService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /** A refused blind-review action, with a message for the person who tried it. */
    public static final class Refused extends RuntimeException {
        public Refused(final String message) {
            super(message);
        }
    }

    // ---- State -------------------------------------------------------------------------------------------------

    /** Selected for blind double review, with the second review not yet recorded. */
    public static boolean open(final Document d) {
        return d != null && d.isDoubleReview() && d.getSecondReviewSpans() == null;
    }

    /** Whether the second review is recorded. */
    public static boolean submitted(final Document d) {
        return d != null && d.isDoubleReview() && d.getSecondReviewSpans() != null;
    }

    /** A second reviewer has started the blind review and not yet submitted it. */
    public static boolean inProgress(final Document d) {
        return open(d) && d.getSecondReviewer() != null && !d.getSecondReviewer().isBlank();
    }

    /** The first review is recorded and no one has started the blind review. */
    public static boolean awaitingStart(final Document d) {
        return open(d) && !inProgress(d) && d.getFirstReviewer() != null && !d.getFirstReviewer().isBlank()
                && REVIEWED.contains(d.getStatus());
    }

    /** Both reviews are recorded and the reviews are not yet reconciled. */
    public static boolean awaitingReconciliation(final Document d) {
        return submitted(d) && d.getDoubleReviewReconciledAt() == null;
    }

    /** Whether {@code auth} is doing the document's blind review. */
    public static boolean isBlindReviewer(final Authentication auth, final Document d) {
        return inProgress(d) && same(d.getSecondReviewer(), name(auth));
    }

    /** Whether {@code email} has been shown the document's spans, or reviewed them. */
    public static boolean hasSeenFirstReview(final Document d, final String email) {
        if (email == null) return false;
        if (same(d.getFirstReviewer(), email)) return true;
        if (d.getApprovedBy().stream().anyMatch(a -> same(a, email))) return true;
        return d.getFirstReviewSeenBy().stream().anyMatch(a -> same(a, email));
    }

    /** Whether {@code auth} may start the blind review now. */
    public static boolean mayStart(final Authentication auth, final Document d) {
        final String email = name(auth);
        return awaitingStart(d) && email != null && !AuthUtils.isAuditor(auth) && !hasSeenFirstReview(d, email);
    }

    /** Whether {@code auth} may release a blind review in progress, or reconcile the two reviews. */
    public boolean mayManage(final Authentication auth, final Document d) {
        return documentAccessService.canApproveDocument(auth, d);
    }

    /** Whether {@code auth} may reconcile the reviews now: an approver or administrator who did neither review. */
    public boolean mayReconcile(final Authentication auth, final Document d) {
        final String email = name(auth);
        return awaitingReconciliation(d) && REVIEWED.contains(d.getStatus()) && email != null
                && !same(d.getFirstReviewer(), email) && !same(d.getSecondReviewer(), email) && mayManage(auth, d);
    }

    /** What {@code auth} sees on the document's review page. */
    public static View view(final Authentication auth, final Document d) {
        if (!open(d)) return View.RECORD;
        if (isBlindReviewer(auth, d)) return View.BLIND;
        if (AuthUtils.isAuditor(auth)) return View.RECORD;
        if (hasSeenFirstReview(d, name(auth))) return View.RECORD;
        // While the first review is in progress the document is reviewed as usual, and whoever looks is recorded.
        if (!awaitingStart(d) && !inProgress(d)) return View.RECORD;
        return View.LANDING;
    }

    /**
     * Records that {@code auth} has been shown the document's spans, so they cannot then do its blind review, and
     * returns the document as saved. Nothing changes for an auditor, the blind reviewer, someone already recorded,
     * or a document not awaiting its second review.
     */
    public Document noteSeen(final Authentication auth, final Document document) {
        final String email = name(auth);
        if (email == null || AuthUtils.isAuditor(auth)) return document;
        Document d = document;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!open(d) || isBlindReviewer(auth, d) || hasSeenFirstReview(d, email)) return d;
            d.getFirstReviewSeenBy().add(email.toLowerCase(Locale.ROOT));
            try {
                return documentRepository.save(d);
            } catch (OptimisticLockingFailureException e) {
                d = documentRepository.findById(document.getId()).orElse(null);
                if (d == null) return document;
            }
        }
        throw new Refused("The document changed while it was opened. Refresh the page.");
    }

    /**
     * For anything that shows the document's span decisions, such as its audit history: refused to the person doing
     * its blind review, and recorded for anyone else, as {@link #noteSeen} does.
     */
    public Document showSpans(final Authentication auth, final Document document) {
        if (isBlindReviewer(auth, document)) {
            throw new Refused("This is not available while you are doing this document's blind second review.");
        }
        return noteSeen(auth, document);
    }

    // ---- Actions -----------------------------------------------------------------------------------------------

    /** Starts the blind review for {@code auth} with a fresh copy of the detections, and returns the document. */
    public Document start(final Authentication auth, final Document d) {
        if (!mayStart(auth, d)) {
            throw new Refused(inProgress(d) ? "Someone else is doing this document's blind second review."
                    : "You cannot do this document's blind second review.");
        }
        final String email = name(auth);
        d.setSecondReviewer(email);
        d.setSecondReviewStartedAt(clock.instant());
        final Document saved;
        try {
            saved = documentRepository.save(d);
        } catch (OptimisticLockingFailureException e) {
            throw new Refused("The document changed while the blind review was being started. Refresh the page.");
        }
        copyRepository.deleteByDocumentId(saved.getId());
        final boolean fromDetections = saved.getDoubleReviewDetections() != null;
        final List<BlindReviewSpan> copies = fromDetections
                ? fromDetections(saved, saved.getDoubleReviewDetections())
                : fromCurrentSpans(saved, spanRepository.findByDocumentId(saved.getId()));
        if (!copies.isEmpty()) {
            copyRepository.saveAll(copies);
        }
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("secondReviewer", email);
        details.put("spans", copies.size());
        details.put("from", fromDetections ? "detections" : "spans");
        auditLogService.log("DOUBLE_REVIEW_START", "Document", saved.getId(), details);
        return saved;
    }

    /** Ends a blind review in progress without recording it, so someone else can do it. */
    public Document release(final Authentication auth, final Document d) {
        if (!inProgress(d)) throw new Refused("No blind second review is in progress on this document.");
        if (!mayManage(auth, d)) throw new Refused("Only an approver of this document's group can release it.");
        final String reviewer = d.getSecondReviewer();
        d.setSecondReviewer(null);
        d.setSecondReviewStartedAt(null);
        final Document saved = save(d);
        final long removed = copyRepository.deleteByDocumentId(saved.getId());
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("secondReviewer", reviewer);
        details.put("spans", removed);
        auditLogService.log("DOUBLE_REVIEW_RELEASE", "Document", saved.getId(), details);
        return saved;
    }

    /** Records the blind review once every span in it is accepted or refused. */
    public Document submit(final Authentication auth, final Document d) {
        if (!isBlindReviewer(auth, d)) throw new Refused("You are not doing this document's blind second review.");
        final List<BlindReviewSpan> copies = copies(d.getId());
        final long undecided = copies.stream().filter(s -> !REVIEWED.contains(s.getStatus())).count();
        if (undecided > 0) {
            throw new Refused(undecided + (undecided == 1 ? " span has" : " spans have")
                    + " not yet been approved or refused. Every span must be decided before the blind review is"
                    + " submitted.");
        }
        d.setSecondReviewSpans(approvedRanges(copies));
        d.setSecondReviewSubmittedAt(clock.instant());
        final Document saved = save(d);
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("secondReviewer", saved.getSecondReviewer());
        details.put("spans", copies.size());
        details.put("approved", copies.stream().filter(s -> "APPROVED".equals(s.getStatus())).count());
        details.put("refused", copies.stream().filter(s -> "REJECTED".equals(s.getStatus())).count());
        details.put("added", copies.stream().filter(Span::isManuallyCreated).count());
        auditLogService.log("DOUBLE_REVIEW_SUBMIT", "Document", saved.getId(), details);
        return saved;
    }

    /** Marks the two reviews reconciled, after the document's spans have been changed where needed. */
    public Document reconcile(final Authentication auth, final Document d) {
        if (!awaitingReconciliation(d)) throw new Refused("This document has no reviews waiting to be reconciled.");
        if (!REVIEWED.contains(d.getStatus())) {
            throw new Refused("Approve or reject the document before marking the reviews reconciled.");
        }
        if (!mayReconcile(auth, d)) {
            throw new Refused("The reviews are reconciled by an approver of this document's group who did neither"
                    + " review.");
        }
        final int disagreements = disagreements(d).size();
        d.setDoubleReviewReconciledBy(name(auth));
        d.setDoubleReviewReconciledAt(clock.instant());
        final Document saved = save(d);
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("firstReviewer", saved.getFirstReviewer() == null ? "" : saved.getFirstReviewer());
        details.put("secondReviewer", saved.getSecondReviewer() == null ? "" : saved.getSecondReviewer());
        details.put("disagreements", disagreements);
        details.put("status", saved.getStatus() == null ? "" : saved.getStatus());
        auditLogService.log("DOUBLE_REVIEW_RECONCILE", "Document", saved.getId(), details);
        return saved;
    }

    /** Why the document cannot be finalized yet, or null when blind double review does not hold it back. */
    public static String finalizationRefusal(final Document d) {
        if (d == null || !d.isDoubleReview()) return null;
        if (!submitted(d)) {
            return "This document was selected for blind double review. It can be finalized once the second review"
                    + " is recorded and the two reviews are reconciled.";
        }
        if (d.getDoubleReviewReconciledAt() == null) {
            return "This document was selected for blind double review. It can be finalized once the two reviews"
                    + " are reconciled.";
        }
        return null;
    }

    // ---- Spans -------------------------------------------------------------------------------------------------

    /** The blind review copies of the document's spans, in document order. */
    public List<BlindReviewSpan> copies(final String documentId) {
        final List<BlindReviewSpan> copies = new ArrayList<>(copyRepository.findByDocumentId(documentId));
        copies.sort(Comparator.comparingInt(s -> s.getLocation() == null ? 0 : s.getLocation().characterStart()));
        return copies;
    }

    /**
     * Where the document's spans, as they stand, and the submitted blind review differ: a range redacted in one and
     * not the other, or redacted as different types. Empty until the second review is recorded.
     */
    public List<Disagreement> disagreements(final Document d) {
        if (!submitted(d)) return List.of();
        final String text = d.getOriginalText() == null ? "" : d.getOriginalText();
        final Map<Long, String> first = approvedTypes(spanRepository.findByDocumentId(d.getId()));
        final List<BlindReviewSpan> copies = copyRepository.findByDocumentId(d.getId());
        final Map<Long, String> second;
        if (copies.isEmpty() && !d.getSecondReviewSpans().isEmpty()) {
            // A second review recorded before #86 kept only its ranges.
            second = new HashMap<>();
            for (int[] r : d.getSecondReviewSpans()) {
                if (r != null && r.length >= 2) second.put(key(r[0], r[1]), "");
            }
        } else {
            second = approvedTypes(copies);
        }
        final List<Disagreement> out = new ArrayList<>();
        final java.util.Set<Long> keys = new java.util.TreeSet<>(first.keySet());
        keys.addAll(second.keySet());
        for (long k : keys) {
            final String a = first.get(k);
            final String b = second.get(k);
            if (a != null && b != null && (b.isEmpty() || a.equals(b))) continue;
            final int start = (int) (k >>> 32);
            final int end = (int) k;
            final String excerpt = start >= 0 && end <= text.length() && start < end ? text.substring(start, end) : "";
            out.add(new Disagreement(start, end, excerpt, a, b));
        }
        return out;
    }

    /** Philter's detections, kept when a document selected for blind double review is redacted. */
    public static List<DetectedSpan> detections(final List<Span> spans) {
        final List<DetectedSpan> out = new ArrayList<>();
        for (Span s : spans) {
            if (s.getLocation() == null) continue;
            out.add(new DetectedSpan(s.getType(), s.getConfidence(), s.getLocation()));
        }
        return out;
    }

    /** The {@code [start, end]} ranges of the approved spans, as the agreement report compares them. */
    public static List<int[]> approvedRanges(final List<? extends Span> spans) {
        final List<int[]> ranges = new ArrayList<>();
        if (spans == null) return ranges;
        for (Span s : spans) {
            if (!"APPROVED".equals(s.getStatus()) || s.getLocation() == null) continue;
            ranges.add(new int[]{s.getLocation().characterStart(), s.getLocation().characterEnd()});
        }
        return ranges;
    }

    private List<BlindReviewSpan> fromDetections(final Document d, final List<DetectedSpan> detections) {
        final List<BlindReviewSpan> out = new ArrayList<>();
        for (DetectedSpan det : detections) {
            final BlindReviewSpan copy = copy(d, det.getType(), det.getConfidence(), det.getLocation());
            if (copy != null) out.add(copy);
        }
        return out;
    }

    /**
     * For a document selected before detections were kept: its detected spans (only those added by hand can be
     * removed, and they are left out), at the boundaries they were detected at. A type the first reviewer changed
     * stays changed, since the detected type was not kept.
     */
    private List<BlindReviewSpan> fromCurrentSpans(final Document d, final List<Span> spans) {
        final List<BlindReviewSpan> out = new ArrayList<>();
        for (Span s : spans) {
            if (s.isManuallyCreated() || s.getLocation() == null) continue;
            final Location loc = s.getOriginalCharacterStart() == null ? s.getLocation()
                    : new Location(s.getOriginalCharacterStart(), s.getOriginalCharacterEnd(), s.getLocation().page(),
                            s.getLocation().coordinates());
            final BlindReviewSpan copy = copy(d, s.getType(), s.getConfidence(), loc);
            if (copy != null) out.add(copy);
        }
        return out;
    }

    private static BlindReviewSpan copy(final Document d, final String type, final double confidence,
                                        final Location location) {
        final String text = d.getOriginalText();
        if (location == null || text == null) return null;
        final int start = location.characterStart();
        final int end = location.characterEnd();
        if (start < 0 || end > text.length() || start >= end) return null;
        final BlindReviewSpan copy = new BlindReviewSpan();
        copy.setId(UUID.randomUUID().toString());
        copy.setDocumentId(d.getId());
        copy.setType(type);
        copy.setText(text.substring(start, end));
        copy.setConfidence(confidence);
        copy.setLocation(location);
        copy.setCreatedAt(LocalDateTime.now());
        // Unreviewed, whatever its confidence: the second reviewer decides every span.
        copy.changeStatus("PENDING");
        return copy;
    }

    private static Map<Long, String> approvedTypes(final List<? extends Span> spans) {
        final Map<Long, String> out = new HashMap<>();
        for (Span s : spans) {
            if (!"APPROVED".equals(s.getStatus()) || s.getLocation() == null) continue;
            out.put(key(s.getLocation().characterStart(), s.getLocation().characterEnd()),
                    Objects.requireNonNullElse(s.getType(), ""));
        }
        return out;
    }

    private static long key(final int start, final int end) {
        return ((long) start << 32) | (end & 0xFFFFFFFFL);
    }

    private Document save(final Document d) {
        try {
            return documentRepository.save(d);
        } catch (OptimisticLockingFailureException e) {
            throw new Refused("The document changed meanwhile. Refresh the page and try again.");
        }
    }

    private static String name(final Authentication auth) {
        return auth == null ? null : auth.getName();
    }

    private static boolean same(final String a, final String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
}
