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

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.AuditTrailService;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.service.DocumentLedgerService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * A document's Philter ledger and its one audit trail (#18), for administrators and auditors, who read every
 * document's audit history. Philter Workspace reads the ledger with its own API key, so Philter's log names that
 * key's user; who asked is recorded here.
 */
@RestController
public class AuditTrailController {

    private final DocumentRepository documentRepository;
    private final DocumentLedgerService ledgerService;
    private final AuditTrailService trailService;
    private final AuditTrailWriter writer;
    private final AuditLogService auditLogService;
    private final ai.philterd.workspace.service.BlindDoubleReviewService blindReview;

    public AuditTrailController(final DocumentRepository documentRepository,
                                final DocumentLedgerService ledgerService, final AuditTrailService trailService,
                                final AuditTrailWriter writer, final AuditLogService auditLogService,
                                final ai.philterd.workspace.service.BlindDoubleReviewService blindReview) {
        this.documentRepository = documentRepository;
        this.ledgerService = ledgerService;
        this.trailService = trailService;
        this.writer = writer;
        this.auditLogService = auditLogService;
        this.blindReview = blindReview;
    }

    /** The document's ledger chains, how each verified, and their entries, for the review page. */
    @GetMapping("/api/v1/documents/{id}/ledger")
    public Map<String, Object> ledger(@PathVariable final String id, final Authentication authentication) {
        final Document document = load(id, authentication);
        final DocumentLedgerService.Ledger ledger = ledgerService.forDocument(document);
        auditLogService.log("PHILTER_LEDGER_READ", "Document", id,
                Map.of("chains", ledger.chains().size(), "entries", ledger.entries().size()));
        final List<Map<String, Object>> chains = new ArrayList<>();
        for (DocumentLedgerService.Chain c : ledger.chains()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("philterDocumentId", c.philterDocumentId());
            m.putAll(AuditTrailWriter.chainDetails(c));
            chains.add(m);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("chains", chains);
        out.put("entries", ledger.entries());
        return out;
    }

    /** The document's trail: its Philter Workspace events and its ledger entries, oldest first. */
    @GetMapping("/api/v1/documents/{id}/audit-trail")
    public void trail(@PathVariable final String id,
                      @RequestParam(name = "format", defaultValue = "csv") final String format,
                      final Authentication authentication, final HttpServletResponse response) throws IOException {
        final Document document = load(id, authentication);
        // The trail holds the first review's decisions (#86).
        try {
            blindReview.showSpans(authentication, document);
        } catch (ai.philterd.workspace.service.BlindDoubleReviewService.Refused e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, e.getMessage());
        }
        final String fmt = AuditTrailWriter.format(format);
        if (fmt == null) {
            throw new ResponseStatusException(BAD_REQUEST, "format must be 'csv' or 'json'");
        }
        // Recorded first, so the export appears in the trail it produces.
        auditLogService.log("AUDIT_TRAIL_EXPORT", "Document", id, Map.of("format", fmt));
        final AuditTrailService.Trail trail = trailService.forDocument(document);
        writer.send(trail, Map.of("documentId", id), fmt,
                "audit-trail-" + id.replaceAll("[^A-Za-z0-9._-]", "_"), response);
    }

    /**
     * Every Philter Workspace event in the range, and the ledger entries in the range of the documents those events
     * concern (up to {@link AuditTrailService#MAX_DOCUMENTS} documents). Under /admin, so administrators and auditors
     * only.
     */
    @GetMapping("/admin/audit/trail")
    public void rangeTrail(@RequestParam(name = "startTime", required = false) final String startTime,
                           @RequestParam(name = "endTime", required = false) final String endTime,
                           @RequestParam(name = "format", defaultValue = "csv") final String format,
                           final HttpServletResponse response) throws IOException {
        final java.time.Instant start = AuditLogAdminController.parseInstant(startTime, "startTime");
        final java.time.Instant end = AuditLogAdminController.parseInstant(endTime, "endTime");
        if (start != null && end != null && start.isAfter(end)) {
            throw new ResponseStatusException(BAD_REQUEST, "startTime must be before endTime");
        }
        final String fmt = AuditTrailWriter.format(format);
        if (fmt == null) {
            throw new ResponseStatusException(BAD_REQUEST, "format must be 'csv' or 'json'");
        }
        final Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("startTime", start == null ? null : start.toString());
        scope.put("endTime", end == null ? null : end.toString());
        final AuditTrailService.Trail trail = trailService.forRange(start, end);
        // Recorded after the query, with what the trail holds, since a range export is not about one document.
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("format", fmt);
        details.put("startTime", start == null ? "" : start.toString());
        details.put("endTime", end == null ? "" : end.toString());
        details.put("events", trail.events().size());
        details.put("chains", trail.chains().size());
        details.put("verifiedChains", trail.verifiedChains());
        details.put("truncated", trail.eventsTruncated() || trail.documentsTruncated());
        auditLogService.log("AUDIT_TRAIL_EXPORT", "AuditLog", null, details);
        writer.send(trail, scope, fmt, "audit-trail-" + java.time.Instant.now().toString().replace(':', '-'),
                response);
    }

    private Document load(final String id, final Authentication authentication) {
        if (!AuthUtils.isAdminOrAuditor(authentication)) {
            throw new ResponseStatusException(FORBIDDEN, "Only administrators and auditors can read the audit trail.");
        }
        return documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
    }
}
