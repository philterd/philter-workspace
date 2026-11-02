/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.AuditLog;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.ComplianceProfile;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.AuditLogRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.ComplianceProfileRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.service.AuditLogQueryService;
import ai.philterd.workspace.util.Csv;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.service.UserGroupsService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lists PII spans where a reviewer has requested a second opinion. The current user
 * sees only requests they did not initiate, scoped to documents in batches assigned
 * to their groups (admins see all). Also exposes a per-span audit history endpoint
 * that drives the History popup on the review page.
 */
@Controller
public class SecondOpinionsController {

    private final SpanRepository spanRepository;
    private final DocumentRepository documentRepository;
    private final BatchRepository batchRepository;
    private final ComplianceProfileRepository complianceProfileRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogRepository auditLogRepository;
    private final AuditLogQueryService auditLogQueryService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final ai.philterd.workspace.service.BlindDoubleReviewService blindReview;

    public SecondOpinionsController(final SpanRepository spanRepository,
                                    final DocumentRepository documentRepository,
                                    final BatchRepository batchRepository,
                                    final ComplianceProfileRepository complianceProfileRepository,
                                    final UserGroupsService userGroupsService,
                                    final AuditLogRepository auditLogRepository,
                                    final AuditLogQueryService auditLogQueryService,
                                    final AuditLogService auditLogService,
                                    final ObjectMapper objectMapper,
                                    final ai.philterd.workspace.service.BlindDoubleReviewService blindReview) {
        this.spanRepository = spanRepository;
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.complianceProfileRepository = complianceProfileRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogRepository = auditLogRepository;
        this.auditLogQueryService = auditLogQueryService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.blindReview = blindReview;
    }

    /** The history shows the first review's decisions (#86). */
    private void showSpans(final Authentication authentication, final Document document) {
        try {
            blindReview.showSpans(authentication, document);
        } catch (ai.philterd.workspace.service.BlindDoubleReviewService.Refused e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/second-opinions")
    public String list(final Authentication authentication, final Model model) {
        final String email = authentication == null ? null : authentication.getName();
        // Cross-group view for both admins and auditors; this is a read.
        final boolean admin = AuthUtils.isAdminOrAuditor(authentication);
        final Set<String> myGroupIds = admin
                ? Set.of()
                : userGroupsService.groupIdsForEmail(email);

        final List<Span> awaiting = spanRepository.findByStatusOrderByStatusChangedAtDesc(Span.STATUS_NEEDS_SECOND_OPINION);

        final Map<String, Document> docCache = new HashMap<>();
        final Map<String, Batch> batchCache = new HashMap<>();
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (Span span : awaiting) {
            if (span.getDocumentId() == null) continue;

            final Document document = docCache.computeIfAbsent(span.getDocumentId(),
                    id -> documentRepository.findById(id).orElse(null));
            if (document == null || document.getBatchId() == null) continue;

            final Batch batch = batchCache.computeIfAbsent(document.getBatchId(),
                    id -> batchRepository.findById(id).orElse(null));
            if (batch == null) continue;

            // Group-scope check (admins bypass).
            if (!admin) {
                if (batch.getGroupId() == null) continue;
                if (!myGroupIds.contains(batch.getGroupId())) continue;
            }

            // Show the current user's own requests for visibility, but mark them as
            // un-actionable — they need a different reviewer to resolve.
            final boolean ownRequest = email != null && span.getStatusChangedBy() != null
                    && email.equalsIgnoreCase(span.getStatusChangedBy());

            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("spanId", span.getId());
            row.put("documentId", document.getId());
            row.put("documentName", document.getFilename() == null ? document.getId() : document.getFilename());
            row.put("batchName", batch.getName() == null ? batch.getId() : batch.getName());
            row.put("type", span.getType() == null ? "" : span.getType());
            row.put("text", span.getText() == null ? "" : span.getText());
            row.put("requestedBy", span.getStatusChangedBy() == null ? "" : span.getStatusChangedBy());
            row.put("requestedAt", span.getStatusChangedAt());
            row.put("ownRequest", ownRequest);
            rows.add(row);
        }

        model.addAttribute("rows", rows);
        return "second-opinions";
    }

    /**
     * Full audit history for a document — document-level events plus all span events, ordered by time.
     *
     * <p>Restricted to admins only. The response includes raw {@code spanText} (pre-redaction PII);
     * exposing that to group-scoped reviewers would let them retrieve all identified PII regardless
     * of what the review UI presents. The CSV export at {@code history.csv} carries the same restriction.
     *
     * <p>The {@code actor} field defaults to the user's MongoDB ID. Pass {@code ?resolveActors=true}
     * to receive email addresses instead — requires {@code ROLE_ADMIN}.
     */
    @GetMapping("/api/v1/documents/{id}/history")
    @ResponseBody
    public List<Map<String, Object>> documentHistory(
            @PathVariable final String id,
            @RequestParam(value = "resolveActors", defaultValue = "false") final boolean resolveActors,
            final Authentication authentication) {
        // Audit history with span text + actor identity is exactly what auditors need —
        // it's a read of audit data. Admins also have access for the same reason.
        if (!AuthUtils.isAdminOrAuditor(authentication)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Audit history with span text is restricted to administrators and auditors.");
        }
        if (resolveActors && !AuthUtils.isAdminOrAuditor(authentication)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "resolveActors requires ROLE_ADMIN or ROLE_AUDITOR.");
        }
        final Document document = documentRepository.findById(id).orElse(null);
        if (document == null) return List.of();
        // The history shows the first review's decisions: an administrator shown it cannot then do the document's
        // blind second review (#86), and the one doing it is not shown it.
        showSpans(authentication, document);
        final Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        final String complianceProfileName = (batch != null && batch.getComplianceProfileId() != null)
                ? complianceProfileRepository.findById(batch.getComplianceProfileId())
                        .map(ComplianceProfile::getName).orElse(null)
                : null;
        final List<Span> spans = spanRepository.findByDocumentId(id);
        final Map<String, String> spanText = new HashMap<>();
        final Map<String, String> spanExemptionCode = new HashMap<>();
        for (Span s : spans) {
            if (s.getId() != null) {
                if (s.getText() != null) spanText.put(s.getId(), s.getText());
                if (s.getExemptionCode() != null) spanExemptionCode.put(s.getId(), s.getExemptionCode());
            }
        }
        final List<String> spanIds = spans.stream().map(Span::getId).toList();
        final List<AuditLog> entries = auditLogQueryService.findForDocument(id, spanIds);
        final List<Map<String, Object>> out = new ArrayList<>();
        for (AuditLog entry : entries) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", entry.getAction());
            row.put("resourceType", entry.getResourceType());
            row.put("resourceId", entry.getResourceId());
            row.put("timestamp", entry.getTimestamp());
            row.put("actor", resolveActor(entry, resolveActors));
            final Map<String, Object> details = entry.getDetails();
            if (details != null) {
                details.forEach(row::putIfAbsent);
            }
            if ("Span".equals(entry.getResourceType())) {
                final String text = spanText.get(entry.getResourceId());
                if (text != null) row.putIfAbsent("spanText", text);
                final String code = spanExemptionCode.get(entry.getResourceId());
                if (code != null) row.put("spanExemptionCode", code);
                if (complianceProfileName != null) row.put("complianceProfileName", complianceProfileName);
            }
            out.add(row);
        }
        return out;
    }

    /**
     * CSV download of a document's full audit history, sorted newest-first. Drives the
     * "Download" button on the document Audit Log popup. The PII text of each span is
     * deliberately omitted; instead the span's location (character offsets and page) is
     * emitted so the export can be safely shared without leaking the redacted content.
     *
     * <p>Restricted to admins only — group-scoped reviewers can read the audit log in the
     * popup but cannot export it, since the export is intended as a chain-of-custody artifact.
     */
    @GetMapping("/api/v1/documents/{id}/history.csv")
    public void documentHistoryCsv(@PathVariable final String id,
                                   final Authentication authentication,
                                   final HttpServletResponse response) throws IOException {
        // CSV export of the audit history is read-only chain-of-custody — auditors get it
        // for the same reason admins do.
        if (!AuthUtils.isAdminOrAuditor(authentication)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        final Document document = documentRepository.findById(id).orElse(null);
        if (document == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try {
            blindReview.showSpans(authentication, document);
        } catch (ai.philterd.workspace.service.BlindDoubleReviewService.Refused e) {
            response.sendError(HttpServletResponse.SC_CONFLICT);
            return;
        }

        // Record the export *before* querying entries so the export event itself appears
        // as the newest row in the resulting CSV. findForDocument() sorts DESC by
        // timestamp, so the just-written entry naturally lands at the top.
        //
        // Filename is hashed (R2-F7) — filenames carry PII and this row is read
        // by auditors who may not have access to the source document.
        auditLogService.log("DOCUMENT_AUDIT_EXPORT", "Document", id,
                Map.of("format", "csv",
                        "filenameHash", auditLogService.hashForAudit(document.getFilename()),
                        "filenameLength", document.getFilename() == null ? 0 : document.getFilename().length()));

        final List<Span> spans = spanRepository.findByDocumentId(id);
        final Map<String, Span> spansById = new HashMap<>();
        for (Span s : spans) {
            if (s.getId() != null) spansById.put(s.getId(), s);
        }
        final List<String> spanIds = spans.stream().map(Span::getId).toList();
        // findForDocument returns newest-first by timestamp.
        final List<AuditLog> entries = auditLogQueryService.findForDocument(id, spanIds);

        final String filenameSafeDocId = id.replaceAll("[^A-Za-z0-9._-]", "_");
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"audit-log-" + filenameSafeDocId + ".csv\"");

        try (final Writer w = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write("timestamp,actor,action,resourceType,resourceId,"
                    + "spanType,spanCharacterStart,spanCharacterEnd,spanPage,details\n");
            for (AuditLog entry : entries) {
                String spanType = "";
                String charStart = "";
                String charEnd = "";
                String page = "";
                if ("Span".equals(entry.getResourceType())) {
                    final Span s = spansById.get(entry.getResourceId());
                    if (s != null) {
                        spanType = s.getType() == null ? "" : s.getType();
                        final Location loc = s.getLocation();
                        if (loc != null) {
                            charStart = Integer.toString(loc.characterStart());
                            charEnd = Integer.toString(loc.characterEnd());
                            page = Integer.toString(loc.page());
                        }
                    }
                }
                w.write(Csv.escapeField(entry.getTimestamp() == null ? "" : entry.getTimestamp().toString()));
                w.write(',');
                w.write(Csv.escapeField(entry.getUserEmail()));
                w.write(',');
                w.write(Csv.escapeField(entry.getAction()));
                w.write(',');
                w.write(Csv.escapeField(entry.getResourceType()));
                w.write(',');
                w.write(Csv.escapeField(entry.getResourceId()));
                w.write(',');
                w.write(Csv.escapeField(spanType));
                w.write(',');
                w.write(charStart);
                w.write(',');
                w.write(charEnd);
                w.write(',');
                w.write(page);
                w.write(',');
                w.write(Csv.escapeField(detailsAsJson(entry.getDetails())));
                w.write('\n');
            }
        }
    }

    private String detailsAsJson(final Map<String, Object> details) {
        if (details == null || details.isEmpty()) return "";
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JacksonException e) {
            return "";
        }
    }

    /**
     * Audit history for a single span — used by the History popup on the review page.
     *
     * <p>The {@code actor} field defaults to the user's MongoDB ID. Pass
     * {@code ?resolveActors=true} to receive email addresses instead — requires
     * {@code ROLE_ADMIN}.
     */
    @GetMapping("/api/v1/spans/{id}/history")
    @ResponseBody
    public List<Map<String, Object>> history(
            @PathVariable final String id,
            @RequestParam(value = "resolveActors", defaultValue = "false") final boolean resolveActors,
            final Authentication authentication) {
        if (resolveActors && !AuthUtils.isAdminOrAuditor(authentication)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "resolveActors requires ROLE_ADMIN or ROLE_AUDITOR.");
        }
        final Span span = spanRepository.findById(id).orElse(null);
        if (span == null) return List.of();

        // Group-scope check via the document.
        final Document document = span.getDocumentId() == null ? null
                : documentRepository.findById(span.getDocumentId()).orElse(null);
        if (document == null) return List.of();
        if (!AuthUtils.isAdminOrAuditor(authentication)) {
            final Batch batch = document.getBatchId() == null ? null
                    : batchRepository.findById(document.getBatchId()).orElse(null);
            if (batch == null || batch.getGroupId() == null) return List.of();
            final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                    authentication == null ? null : authentication.getName());
            if (!myGroupIds.contains(batch.getGroupId())) return List.of();
        }

        final List<AuditLog> entries = auditLogRepository
                .findByResourceTypeAndResourceIdOrderByTimestampAsc("Span", id);
        final List<Map<String, Object>> out = new ArrayList<>();
        for (AuditLog entry : entries) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", entry.getAction());
            row.put("timestamp", entry.getTimestamp());
            row.put("actor", resolveActor(entry, resolveActors));
            final Map<String, Object> details = entry.getDetails();
            if (details != null) {
                row.put("previousStatus", details.getOrDefault("previousStatus", ""));
                row.put("status", details.getOrDefault("status", ""));
                row.put("reason", details.getOrDefault("reason", ""));
                row.put("overturn", details.getOrDefault("overturn", false));
                row.put("previousType", details.getOrDefault("previousType", ""));
                row.put("type", details.getOrDefault("type", ""));
            }
            out.add(row);
        }
        return out;
    }

    private static String resolveActor(final AuditLog entry, final boolean resolveActors) {
        if (resolveActors) {
            return entry.getUserEmail() == null ? "" : entry.getUserEmail();
        }
        return entry.getUserId() == null ? "" : entry.getUserId();
    }

}
