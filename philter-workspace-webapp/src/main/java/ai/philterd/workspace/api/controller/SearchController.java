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

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.service.BatchAccessService;
import ai.philterd.workspace.service.DocumentSearchService;
import ai.philterd.workspace.service.DocumentSearchService.SearchHit;
import ai.philterd.workspace.service.DocumentSearchService.SearchResults;
import ai.philterd.workspace.dto.SearchRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final DocumentSearchService searchService;
    private final DocumentRepository documentRepository;
    private final AuditLogService auditLogService;
    private final BatchAccessService batchAccessService;

    public SearchController(final DocumentSearchService searchService,
                            final DocumentRepository documentRepository,
                            final AuditLogService auditLogService,
                            final BatchAccessService batchAccessService) {
        this.searchService = searchService;
        this.documentRepository = documentRepository;
        this.auditLogService = auditLogService;
        this.batchAccessService = batchAccessService;
    }

    /** The query is in the body, so it stays out of access logs and browser history (#78). */
    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> search(@Valid @RequestBody final SearchRequest request,
                                      final Authentication authentication) {
        return run(request.q(), request.offset() == null ? 0 : request.offset(),
                request.size() == null ? 10 : request.size(), authentication);
    }

    /** The removed query-string form, answered with where to send the query instead. The query is not run. */
    @GetMapping("/search")
    public ResponseEntity<Map<String, String>> searchByUrl() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).allow(HttpMethod.POST)
                .body(Map.of("error", "Send the query with POST /api/v1/search in a JSON body, so it stays out of URLs."));
    }

    /** Runs a search for the caller, as {@link #search} answers it. */
    public Map<String, Object> run(final String query, final int offset, final int size,
                                   final Authentication authentication) {
        // Restrict at the search backend for non-admins so the reported `total` and
        // the hit list both exclude foreign documents — an attacker can no longer probe
        // queries to detect the existence of content in batches they can't see.
        // Auditors see cross-group results too: this controls the search backend's
        // batch-id filter, a read-only decision.
        final boolean admin = AuthUtils.isAdminOrAuditor(authentication);
        final Set<String> allowedBatchIds = admin ? null : batchAccessService.allowedBatchIds(authentication);
        final SearchResults results = searchService.search(query, offset, size, allowedBatchIds);
        if (offset == 0) {
            // Hash + length only — search queries routinely contain PII (SSN/PAN
            // typed in to locate a document) and the audit log is readable by
            // auditors who may not have access to the source. See
            // AuditLogService.hashForAudit for the forensic-correlation pattern.
            auditLogService.log("DOCUMENT_SEARCH", "Document", null,
                    java.util.Map.of("queryHash", auditLogService.hashForAudit(query),
                            "queryLength", query == null ? 0 : query.length(),
                            "total", results.total()));
        }

        // Live status from MongoDB — the indexed status can be stale if the document
        // was updated after it was last indexed.
        final java.util.Set<String> docIds = new java.util.HashSet<>();
        for (SearchHit h : results.hits()) {
            if (h.id() != null && !h.id().isBlank()) docIds.add(h.id());
        }
        final java.util.Map<String, String> liveStatuses = new java.util.HashMap<>();
        for (Document d : documentRepository.findAllById(docIds)) {
            liveStatuses.put(d.getId(), d.getStatus() == null ? "" : d.getStatus());
        }

        final List<Map<String, Object>> hits = new ArrayList<>();
        for (SearchHit h : results.hits()) {
            // Defense in depth: verify the hit's batchId is in the allowed set even though
            // the search backend's batch filter should already guarantee it. Skip silently on a
            // mismatch (could only happen via an indexing bug or schema drift).
            if (!admin && (h.batchId() == null || !allowedBatchIds.contains(h.batchId()))) {
                continue;
            }
            final Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", h.id());
            hit.put("batchId", h.batchId());
            hit.put("filename", h.filename());
            hit.put("status", liveStatuses.getOrDefault(h.id(), h.status()));
            hit.put("highlights", h.highlights());
            hits.add(hit);
        }

        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query == null ? "" : query);
        out.put("offset", results.from());
        out.put("size", results.size());
        out.put("total", results.total());
        out.put("hits", hits);
        return out;
    }

}
