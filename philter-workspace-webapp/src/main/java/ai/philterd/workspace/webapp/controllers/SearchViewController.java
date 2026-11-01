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

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.service.DocumentSearchService;
import ai.philterd.workspace.service.DocumentSearchService.SearchHit;
import ai.philterd.workspace.service.DocumentSearchService.SearchResults;
import ai.philterd.workspace.service.UserGroupsService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
public class SearchViewController {

    private static final int PAGE_SIZE = 10;
    /** The session attribute holding recent searches: a token to the query, encrypted (#78). */
    static final String SEARCHES_ATTRIBUTE = SearchViewController.class.getName() + ".searches";
    /** How many recent searches a session keeps, so the back button can return to each. */
    static final int KEPT_SEARCHES = 10;

    private final DocumentSearchService searchService;
    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogService auditLogService;
    private final ai.philterd.workspace.service.SymmetricCipher cipher;
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    public SearchViewController(final DocumentSearchService searchService,
                                final BatchRepository batchRepository,
                                final DocumentRepository documentRepository,
                                final UserGroupsService userGroupsService,
                                final AuditLogService auditLogService,
                                final ai.philterd.workspace.service.SymmetricCipher cipher) {
        this.searchService = searchService;
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogService = auditLogService;
        this.cipher = cipher;
    }

    /**
     * Takes the query from the form's body and redirects to its results under a random token, so the query is never
     * in a URL, an access log, or browser history, while back, refresh, and paging still work (#78). The query is
     * kept in the person's session, encrypted, and only for their {@value #KEPT_SEARCHES} most recent searches.
     */
    @org.springframework.web.bind.annotation.PostMapping("/search")
    public String submit(@RequestParam(name = "q", required = false) final String q,
                         final jakarta.servlet.http.HttpSession session) {
        final String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            return "redirect:/search";
        }
        final byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        final String token = java.util.HexFormat.of().formatHex(bytes);
        // Two searches submitted at once in one session can lose one token; its page then says the search ended.
        synchronized (org.springframework.web.util.WebUtils.getSessionMutex(session)) {
            final LinkedHashMap<String, String> searches = searches(session);
            searches.put(token, cipher.encrypt(query));
            while (searches.size() > KEPT_SEARCHES) {
                searches.remove(searches.keySet().iterator().next());
            }
            // Set again so a session store that saves on change, such as Spring Session's, writes it.
            session.setAttribute(SEARCHES_ATTRIBUTE, searches);
        }
        return "redirect:/search?r=" + token;
    }

    /**
     * The search page, or the results of the search {@code r} names. A query in the URL ({@code q}, the form before
     * #78) is not run, so an old link or bookmark does not keep sending it.
     */
    @GetMapping("/search")
    public String search(@RequestParam(name = "r", required = false) final String token,
                         @RequestParam(name = "offset", defaultValue = "0") final int offset,
                         final jakarta.servlet.http.HttpSession session,
                         final Authentication authentication,
                         final Model model) {
        String query = "";
        if (token != null && !token.isBlank()) {
            final String stored = searches(session).get(token);
            if (stored == null) {
                model.addAttribute("searchExpired", true);
            } else {
                query = cipher.decrypt(stored);
                model.addAttribute("searchToken", token);
            }
        }
        return render(query, offset, authentication, model);
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, String> searches(final jakarta.servlet.http.HttpSession session) {
        final Object kept = session.getAttribute(SEARCHES_ATTRIBUTE);
        return kept instanceof LinkedHashMap<?, ?> map ? (LinkedHashMap<String, String>) map : new LinkedHashMap<>();
    }

    /** Renders the search page for {@code q}, or the empty form when it is blank. */
    public String render(final String q, final int offset, final Authentication authentication, final Model model) {
        final String query = q == null ? "" : q.trim();
        final int safeOffset = Math.max(0, offset);

        final List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;
        if (!query.isEmpty()) {
            // Restrict at the search backend for non-admins so the reported total and the
            // hit list both exclude documents the caller can't see — see SearchController for
            // the matching API change.
            // Auditors see cross-group results too: this controls the search backend's
            // batch-id filter, a read-only decision.
            final boolean admin = AuthUtils.isAdminOrAuditor(authentication);
            final Set<String> allowedBatchIds = admin ? null : allowedBatchIds(authentication);
            final SearchResults results = searchService.search(query, safeOffset, PAGE_SIZE, allowedBatchIds);
            total = results.total();
            if (safeOffset == 0) {
                // Persist only the SHA-256 + length, not the cleartext. Search
                // queries routinely contain PII (an analyst typing an SSN or PAN
                // to locate a document); the audit log is cross-group-readable
                // by auditors, so storing the query cleartext would leak that
                // PII to people who never had access to the source document.
                // Forensic "did anyone search for X?" still works via hashing X.
                auditLogService.log("DOCUMENT_SEARCH", "Document", null,
                        Map.of("queryHash", auditLogService.hashForAudit(query),
                                "queryLength", query == null ? 0 : query.length(),
                                "total", total));
            }

            // Collect all document and batch IDs so we can do two batch lookups.
            final Set<String> docIds = new HashSet<>();
            final Set<String> batchIds = new HashSet<>();
            for (SearchHit h : results.hits()) {
                if (h.id() != null && !h.id().isBlank()) docIds.add(h.id());
                if (h.batchId() != null && !h.batchId().isBlank()) batchIds.add(h.batchId());
            }

            // Live document data from the database, since the search index can be stale after status changes.
            final Map<String, Map<String, Object>> liveDocs = new LinkedHashMap<>();
            for (Document d : documentRepository.findAllById(docIds)) {
                final Map<String, Object> data = new LinkedHashMap<>();
                data.put("status", d.getStatus() == null ? "" : d.getStatus());
                data.put("createdAt", fmt(d.getCreatedAt()));
                data.put("statusChangedAt", fmt(d.getStatusChangedAt()));
                data.put("riskScore", String.format("%.0f%%", d.getRiskScore() * 100));
                final List<String> approved = d.getApprovedBy() == null ? List.of() : d.getApprovedBy();
                data.put("approvedBy", String.join(", ", approved));
                liveDocs.put(d.getId(), data);
            }

            final Map<String, String> batchNames = new LinkedHashMap<>();
            for (Batch b : batchRepository.findAllById(batchIds)) {
                batchNames.put(b.getId(), b.getName() == null ? b.getId() : b.getName());
            }

            for (SearchHit h : results.hits()) {
                // Defense in depth: silently skip any hit whose batchId is outside the allow-list.
                // The search backend's batch filter should already guarantee this, so reaching this
                // branch means an indexing bug or schema drift.
                if (!admin && (h.batchId() == null || !allowedBatchIds.contains(h.batchId()))) {
                    continue;
                }
                final Map<String, Object> doc = liveDocs.getOrDefault(h.id(), Map.of());
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", h.id());
                row.put("filename", h.filename());
                row.put("status", doc.getOrDefault("status", h.status()));
                row.put("batchId", h.batchId());
                row.put("batchName", batchNames.getOrDefault(h.batchId(), h.batchId()));
                row.put("createdAt", doc.getOrDefault("createdAt", ""));
                row.put("statusChangedAt", doc.getOrDefault("statusChangedAt", ""));
                row.put("riskScore", doc.getOrDefault("riskScore", ""));
                row.put("approvedBy", doc.getOrDefault("approvedBy", ""));
                row.put("highlights", h.highlights());
                rows.add(row);
            }
        }

        model.addAttribute("query", query);
        model.addAttribute("offset", safeOffset);
        model.addAttribute("pageSize", PAGE_SIZE);
        model.addAttribute("total", total);
        model.addAttribute("results", rows);
        model.addAttribute("hasPrev", safeOffset > 0);
        model.addAttribute("hasNext", safeOffset + PAGE_SIZE < total);
        model.addAttribute("prevOffset", Math.max(0, safeOffset - PAGE_SIZE));
        model.addAttribute("nextOffset", safeOffset + PAGE_SIZE);
        model.addAttribute("displayStart", rows.isEmpty() ? 0 : safeOffset + 1);
        model.addAttribute("displayEnd", safeOffset + rows.size());
        return "search";
    }

    private Set<String> allowedBatchIds(final Authentication auth) {
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        final Set<String> ids = new HashSet<>();
        for (Batch b : batchRepository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent()) {
            if (b.getGroupId() != null && myGroupIds.contains(b.getGroupId())) {
                ids.add(b.getId());
            }
        }
        return ids;
    }

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static String fmt(final LocalDateTime dt) {
        return dt == null ? "" : dt.format(DT_FMT);
    }

}
