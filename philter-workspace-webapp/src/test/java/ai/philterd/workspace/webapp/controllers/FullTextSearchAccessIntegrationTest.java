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

import ai.philterd.workspace.api.controller.SearchController;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.GroupRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.BatchAccessService;
import ai.philterd.workspace.service.GeneralSettingsService;
import ai.philterd.workspace.service.OpenSearchIndexService;
import ai.philterd.workspace.service.UserGroupsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ConcurrentModel;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration tests for the full-text search authorization model. The tests
 * run against:
 *
 * <ul>
 *     <li>An in-process HTTP server pretending to be OpenSearch — every search query goes
 *         over a real TCP socket, which exercises the on-the-wire {@code terms} filter
 *         (the primary group-scope enforcement layer).</li>
 *     <li>A real {@link OpenSearchIndexService} with the controller's normal HTTP client.</li>
 *     <li>Real {@link BatchAccessService} and {@link UserGroupsService} so the
 *         email→groups→batches resolution that builds the allow-list runs end-to-end.</li>
 *     <li>Real {@link SearchController} and {@link SearchViewController}.</li>
 * </ul>
 *
 * The mock OpenSearch can be driven to return mixed-batch hits even when only one batch
 * is in the caller's allow-list — that simulates either an indexing bug or a hostile
 * attempt to bypass the OpenSearch-layer filter, and exercises the controller's
 * defense-in-depth post-filter.
 */
class FullTextSearchAccessIntegrationTest {

    private static final String INDEX = "philter-workspace-documents";

    private MockOpenSearch server;
    private SearchController apiController;
    private SearchViewController viewController;
    private BatchRepository batchRepository;
    private DocumentRepository documentRepository;
    private TestUserGroupsService userGroupsService;
    private GeneralSettingsService generalSettingsService;
    private GeneralSettings settings;

    private Batch g1Batch;
    private Batch g2Batch;
    private Batch noGroupBatch;

    @BeforeEach
    void setUp() throws Exception {
        server = MockOpenSearch.start();
        batchRepository = mock(BatchRepository.class);
        documentRepository = mock(DocumentRepository.class);
        userGroupsService = new TestUserGroupsService();

        // Settings: feature on, endpoint pointing at the mock server, default index name.
        settings = new GeneralSettings();
        settings.setFullTextSearchEnabled(true);
        settings.setOpensearchEndpoint("http://127.0.0.1:" + server.port());
        settings.setOpensearchIndexName(INDEX);
        generalSettingsService = mock(GeneralSettingsService.class);
        when(generalSettingsService.load()).thenAnswer(inv -> settings);

        // Three batches: b-g1 (group g1), b-g2 (group g2), b-no-group (legacy row with no
        // group). The "no group" batch tests that documents in batches with no group are
        // never visible to non-admins.
        g1Batch = batch("b-g1", "g1", "Batch in g1");
        g2Batch = batch("b-g2", "g2", "Batch in g2");
        noGroupBatch = batch("b-no-group", null, "Legacy batch with no group");
        when(batchRepository.findAll(any(PageRequest.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(g1Batch, g2Batch, noGroupBatch),
                        inv.getArgument(0, PageRequest.class), 3));
        // BatchAccessService.allowedBatchIds uses this query to resolve email→groups→batches.
        when(batchRepository.findByGroupIdIn(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            final Set<String> myGroupIds = (Set<String>) inv.getArgument(0);
            final List<Batch> matched = new ArrayList<>();
            for (Batch b : List.of(g1Batch, g2Batch, noGroupBatch)) {
                if (b.getGroupId() != null && myGroupIds.contains(b.getGroupId())) matched.add(b);
            }
            return matched;
        });
        when(batchRepository.findAllById(any())).thenAnswer(inv -> {
            final Iterable<?> ids = inv.getArgument(0);
            final List<Batch> out = new ArrayList<>();
            for (Object id : ids) {
                if (g1Batch.getId().equals(id)) out.add(g1Batch);
                else if (g2Batch.getId().equals(id)) out.add(g2Batch);
                else if (noGroupBatch.getId().equals(id)) out.add(noGroupBatch);
            }
            return out;
        });

        // Documents the search hits resolve to. Stub findAllById to surface their live status.
        when(documentRepository.findAllById(any())).thenAnswer(inv -> {
            final Iterable<?> ids = inv.getArgument(0);
            final List<Document> out = new ArrayList<>();
            for (Object id : ids) {
                final Document d = new Document();
                d.setId(String.valueOf(id));
                d.setStatus("APPROVED");
                d.setRiskScore(0.5);
                out.add(d);
            }
            return out;
        });

        final OpenSearchIndexService openSearchIndexService = new OpenSearchIndexService(generalSettingsService);
        final BatchAccessService batchAccessService = new BatchAccessService(batchRepository, userGroupsService);
        final AuditLogService auditLogService = mock(AuditLogService.class);
        // Default hashForAudit to "" so the controller's Map.of(...) calls don't NPE
        // on the mock's null return. Behaviour of the real HMAC is pinned in
        // AuditLogServiceTest; these tests don't care about the hash value itself.
        when(auditLogService.hashForAudit(anyString())).thenReturn("");

        apiController = new SearchController(openSearchIndexService, documentRepository,
                auditLogService, batchAccessService);
        viewController = new SearchViewController(openSearchIndexService, batchRepository,
                documentRepository, userGroupsService, auditLogService,
                new ai.philterd.workspace.service.SymmetricCipher(java.util.Base64.getEncoder().encodeToString(new byte[32])));
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    // ---------- group access enforcement: API ----------

    @Test
    void userInGroupSeesOnlyTheirBatchesViaApi() {
        // Mock OpenSearch returns three hits across all three batches. The terms filter on
        // the wire should restrict the results, but we set the mock to return everything
        // anyway to also exercise the controller's defense-in-depth post-filter.
        userGroupsService.put("alice@x.com", Set.of("g1"));
        server.respondToSearch(twoHits(200, List.of(
                hit("doc-1", g1Batch.getId(), "approved.txt"),
                hit("doc-2", g2Batch.getId(), "secret.txt"),
                hit("doc-3", noGroupBatch.getId(), "orphan.txt")
        )));

        final Map<String, Object> result = apiController.run("query", 0, 10, alice());

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("hits");
        assertEquals(1, hits.size(),
                "Alice is only in g1, so only the doc in b-g1 must surface — even when OpenSearch "
                        + "returns documents from other batches.");
        assertEquals("doc-1", hits.get(0).get("id"));
        assertEquals(g1Batch.getId(), hits.get(0).get("batchId"));
    }

    @Test
    void userInDifferentGroupCannotSeeForeignDocuments() {
        // Bob is in g2 only. The same search must surface only the b-g2 document.
        userGroupsService.put("bob@x.com", Set.of("g2"));
        server.respondToSearch(twoHits(200, List.of(
                hit("doc-1", g1Batch.getId(), "approved.txt"),
                hit("doc-2", g2Batch.getId(), "secret.txt")
        )));

        final Map<String, Object> result = apiController.run("query", 0, 10, bob());

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("hits");
        assertEquals(1, hits.size());
        assertEquals(g2Batch.getId(), hits.get(0).get("batchId"));
    }

    @Test
    void userInBothGroupsSeesBothBatches() {
        userGroupsService.put("eve@x.com", Set.of("g1", "g2"));
        server.respondToSearch(twoHits(200, List.of(
                hit("doc-1", g1Batch.getId(), "approved.txt"),
                hit("doc-2", g2Batch.getId(), "secret.txt"),
                hit("doc-3", noGroupBatch.getId(), "orphan.txt")
        )));

        final Map<String, Object> result = apiController.run("query", 0, 10, eve());

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("hits");
        assertEquals(2, hits.size(),
                "Eve is in both g1 and g2, so both batches' documents must surface.");
        // The orphan doc in the no-group batch must NOT — Eve isn't in any group it's tied to.
        assertTrue(hits.stream().noneMatch(h -> "doc-3".equals(h.get("id"))),
                "Documents in batches with no group must never appear for non-admin callers.");
    }

    @Test
    void userWithNoGroupMembershipSeesNothingAndDoesNotContactOpenSearch() {
        // Stranded user with no groups → empty allowedBatchIds → OpenSearchIndexService
        // short-circuits and never makes the HTTP call. Verify the search path is silent.
        userGroupsService.put("stranded@x.com", Set.of());
        server.respondToSearch(twoHits(200, List.of(
                hit("doc-1", g1Batch.getId(), "approved.txt"))));

        final Map<String, Object> result = apiController.run("query", 0, 10, stranded());

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("hits");
        assertTrue(hits.isEmpty());
        assertEquals(0L, ((Number) result.get("total")).longValue());
        assertEquals(0, server.searchCallCount(),
                "An empty allow-list must short-circuit before any OpenSearch HTTP call.");
    }

    // ---------- on-the-wire batch-id filter ----------

    @Test
    void searchQueryToOpenSearchIncludesBatchIdTermsFilterForRestrictedCallers() throws Exception {
        // Capture the raw POST body to /<index>/_search and verify the bool/filter/terms
        // batchId clause is present and lists exactly the batches the caller is allowed to see.
        userGroupsService.put("alice@x.com", Set.of("g1"));
        server.respondToSearch(twoHits(200, List.of()));

        apiController.run("query", 0, 10, alice());

        final String body = server.lastSearchBody();
        assertNotNull(body, "Expected a recorded /_search request");
        final JsonNode root = new ObjectMapper().readTree(body);
        final JsonNode terms = root.path("query").path("bool").path("filter").path("terms").path("batchId");
        assertTrue(terms.isArray() && !terms.isEmpty(),
                "Restricted callers must produce a {bool: {filter: {terms: {batchId: [...]}}}} clause "
                        + "on the wire so OpenSearch itself enforces group scope. Body: " + body);
        // The terms array must list ONLY the caller's allowed batch.
        final List<String> batchIds = new ArrayList<>();
        for (JsonNode n : terms) batchIds.add(n.asString());
        assertEquals(List.of(g1Batch.getId()), batchIds,
                "The terms filter must list exactly the caller's accessible batches — never any other.");
    }

    @Test
    void searchQueryToOpenSearchOmitsBatchIdFilterForAdmin() throws Exception {
        // Admin has cross-group scope: the OpenSearch query must NOT carry a batchId filter
        // because the controller passes allowedBatchIds=null. The query body must contain
        // a top-level match clause (no bool wrapper) so admin sees everything in the index.
        server.respondToSearch(twoHits(200, List.of()));

        apiController.run("query", 0, 10, admin());

        final String body = server.lastSearchBody();
        assertNotNull(body);
        final JsonNode root = new ObjectMapper().readTree(body);
        // The top-level query is the bare match clause when admin — no bool wrapper.
        assertTrue(root.path("query").has("match"),
                "Admin queries must use the top-level match form, not a bool with terms filter.");
        assertFalse(root.path("query").has("bool"),
                "Admin queries must not wrap the match in a bool/filter — that would silently apply scope.");
    }

    @Test
    void auditorIsTreatedAsCrossGroupLikeAdmin() {
        // Auditors share admin's read scope across groups. The wire query must omit the
        // batchId filter the same way admin's does.
        server.respondToSearch(twoHits(200, List.of(
                hit("doc-1", g1Batch.getId(), "a.txt"),
                hit("doc-2", g2Batch.getId(), "b.txt"))));

        final Map<String, Object> result = apiController.run("query", 0, 10, auditor());

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("hits");
        assertEquals(2, hits.size(),
                "Auditor sees the same cross-group scope as admin.");
    }

    // ---------- defense-in-depth post-filter ----------

    @Test
    void controllerSilentlyDropsHitsFromBatchesOutsideTheAllowList() {
        // Simulate an indexing bug or schema drift: OpenSearch returns a hit for a batch
        // outside the caller's allow-list even though the terms filter should have
        // prevented it. The controller's post-filter must drop the hit silently so the
        // count and the list both stay clean.
        userGroupsService.put("alice@x.com", Set.of("g1"));
        server.respondToSearch(twoHits(200, List.of(
                hit("doc-1", g1Batch.getId(), "approved.txt"),
                hit("doc-2", g2Batch.getId(), "leak.txt"))));

        final Map<String, Object> result = apiController.run("query", 0, 10, alice());

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("hits");
        assertEquals(1, hits.size(),
                "The controller's post-filter must drop foreign-batch hits even when OpenSearch "
                        + "returns them — defense in depth against indexing bugs and schema drift.");
        assertEquals("doc-1", hits.get(0).get("id"));
    }

    @Test
    void controllerSilentlyDropsHitsWithMissingBatchId() {
        // A hit with no batchId field at all is malformed; the controller must drop it
        // rather than fall through and surface it as a "no batch" result.
        userGroupsService.put("alice@x.com", Set.of("g1"));
        server.respondToSearch(twoHits(200, List.of(
                hit("doc-1", g1Batch.getId(), "approved.txt"),
                hit("doc-malformed", null, "broken.txt"))));

        final Map<String, Object> result = apiController.run("query", 0, 10, alice());
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("hits");
        assertEquals(1, hits.size());
        assertEquals("doc-1", hits.get(0).get("id"));
    }

    // ---------- web view (SearchViewController) ----------

    @Test
    void webPageScopesResultsToTheCallerUserGroup() {
        userGroupsService.put("alice@x.com", Set.of("g1"));
        server.respondToSearch(twoHits(200, List.of(
                hit("doc-1", g1Batch.getId(), "approved.txt"),
                hit("doc-2", g2Batch.getId(), "secret.txt"))));

        final ConcurrentModel model = new ConcurrentModel();
        viewController.render("query", 0, alice(), model);

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> rows = (List<Map<String, Object>>) model.getAttribute("results");
        assertEquals(1, rows.size(),
                "The web Search page must scope results to the caller's group memberships, "
                        + "just like the API.");
        assertEquals(g1Batch.getId(), rows.get(0).get("batchId"));
    }

    @Test
    void webPageReturnsNothingForUserWithNoGroupMembership() {
        userGroupsService.put("stranded@x.com", Set.of());
        server.respondToSearch(twoHits(200, List.of(
                hit("doc-1", g1Batch.getId(), "approved.txt"))));

        final ConcurrentModel model = new ConcurrentModel();
        viewController.render("query", 0, stranded(), model);

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> rows = (List<Map<String, Object>>) model.getAttribute("results");
        assertTrue(rows.isEmpty());
        assertEquals(0L, ((Number) model.getAttribute("total")).longValue());
    }

    @Test
    void webPageAdminSeesEveryBatch() {
        server.respondToSearch(twoHits(200, List.of(
                hit("doc-1", g1Batch.getId(), "a.txt"),
                hit("doc-2", g2Batch.getId(), "b.txt"))));

        final ConcurrentModel model = new ConcurrentModel();
        viewController.render("query", 0, admin(), model);

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> rows = (List<Map<String, Object>>) model.getAttribute("results");
        assertEquals(2, rows.size(),
                "Admin's cross-group scope must expose hits in every batch the index returns.");
    }

    // ---------- enabled-flag short-circuit ----------

    @Test
    void searchReturnsEmptyAndSkipsOpenSearchWhenFeatureIsDisabled() {
        // Even with credentials and a reachable mock server, when the feature is off the
        // search path must short-circuit to empty without any HTTP call.
        settings.setFullTextSearchEnabled(false);
        userGroupsService.put("alice@x.com", Set.of("g1"));
        server.respondToSearch(twoHits(200, List.of(
                hit("doc-1", g1Batch.getId(), "approved.txt"))));

        final Map<String, Object> result = apiController.run("query", 0, 10, alice());

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("hits");
        assertTrue(hits.isEmpty());
        assertEquals(0L, ((Number) result.get("total")).longValue());
        assertEquals(0, server.searchCallCount(),
                "Disabled feature must not produce ANY outbound search request.");
    }

    // ---------- helpers ----------

    private static Batch batch(final String id, final String groupId, final String name) {
        final Batch b = new Batch();
        b.setId(id);
        b.setGroupId(groupId);
        b.setName(name);
        return b;
    }

    private static Map<String, Object> hit(final String id, final String batchId, final String filename) {
        final Map<String, Object> h = new HashMap<>();
        h.put("id", id);
        h.put("batchId", batchId);
        h.put("filename", filename);
        return h;
    }

    private static String twoHits(final long total, final List<Map<String, Object>> hits) {
        // Build the OpenSearch _search response shape: {hits: {total: {value}, hits: [{_id, _source}]}}
        final ObjectMapper m = new ObjectMapper();
        try {
            final var root = m.createObjectNode();
            final var hitsNode = m.createObjectNode();
            final var totalNode = m.createObjectNode();
            totalNode.put("value", hits.size());
            hitsNode.set("total", totalNode);
            final var arr = m.createArrayNode();
            for (Map<String, Object> h : hits) {
                final var entry = m.createObjectNode();
                entry.put("_id", String.valueOf(h.get("id")));
                final var src = m.createObjectNode();
                if (h.get("batchId") != null) src.put("batchId", String.valueOf(h.get("batchId")));
                src.put("filename", String.valueOf(h.get("filename")));
                src.put("status", "APPROVED");
                entry.set("_source", src);
                arr.add(entry);
            }
            hitsNode.set("hits", arr);
            root.set("hits", hitsNode);
            return m.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static Authentication auditor() {
        return new UsernamePasswordAuthenticationToken("auditor@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
    }

    private static Authentication alice() {
        return new UsernamePasswordAuthenticationToken("alice@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static Authentication bob() {
        return new UsernamePasswordAuthenticationToken("bob@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static Authentication eve() {
        return new UsernamePasswordAuthenticationToken("eve@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static Authentication stranded() {
        return new UsernamePasswordAuthenticationToken("stranded@x.com", null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /**
     * Test double for {@link UserGroupsService} that returns whatever group set was put for
     * a given email. {@link UserGroupsService} requires repository constructor args, so we
     * pass mocks and override the one method the search path actually calls.
     */
    static final class TestUserGroupsService extends UserGroupsService {
        private final Map<String, Set<String>> byEmail = new HashMap<>();

        TestUserGroupsService() {
            super(mock(UserRepository.class), mock(GroupRepository.class));
        }

        void put(final String email, final Set<String> groupIds) {
            byEmail.put(email, groupIds);
        }

        @Override
        public Set<String> groupIdsForEmail(final String email) {
            return email == null ? Set.of() : byEmail.getOrDefault(email, Set.of());
        }
    }

    /**
     * In-process HTTP server pretending to be OpenSearch. The search path posts to
     * {@code /<index>/_search}; we record every inbound body and return whatever JSON the
     * test pre-loaded.
     */
    static final class MockOpenSearch {
        private final HttpServer http;
        private final AtomicReference<CannedResponse> searchResponse = new AtomicReference<>(
                new CannedResponse(200, "{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}"));
        private final List<String> searchBodies = new ArrayList<>();

        private MockOpenSearch(final HttpServer http) {
            this.http = http;
        }

        static MockOpenSearch start() throws IOException {
            final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            final MockOpenSearch self = new MockOpenSearch(http);
            http.createContext("/", self::handle);
            http.setExecutor(Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "mock-os-search");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            return self;
        }

        int port() { return http.getAddress().getPort(); }
        void stop() { http.stop(0); }

        void respondToSearch(final String body) {
            searchResponse.set(new CannedResponse(200, body));
        }

        synchronized String lastSearchBody() {
            return searchBodies.isEmpty() ? null : searchBodies.get(searchBodies.size() - 1);
        }

        synchronized int searchCallCount() {
            return searchBodies.size();
        }

        private void handle(final HttpExchange exchange) throws IOException {
            final String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/_search")) {
                final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                synchronized (this) {
                    searchBodies.add(body);
                }
                final CannedResponse resp = searchResponse.get();
                final byte[] payload = resp.body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(resp.status, payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
                return;
            }
            // Anything else gets a 404 — the search path should not be calling other endpoints.
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        record CannedResponse(int status, String body) {}
    }
}
