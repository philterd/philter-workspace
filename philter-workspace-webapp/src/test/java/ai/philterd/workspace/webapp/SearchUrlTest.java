/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp;

import ai.philterd.workspace.api.controller.SearchController;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.BatchAccessService;
import ai.philterd.workspace.service.DocumentSearchService;
import ai.philterd.workspace.service.DocumentSearchService.SearchHit;
import ai.philterd.workspace.service.DocumentSearchService.SearchResults;
import ai.philterd.workspace.service.GeneralSettingsService;
import ai.philterd.workspace.service.SymmetricCipher;
import ai.philterd.workspace.service.UserGroupsService;
import ai.philterd.workspace.webapp.controllers.SearchViewController;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Search terms stay out of URLs, history, and logs (#78). */
@WebMvcTest(controllers = {SearchViewController.class, SearchController.class})
@Import({SecurityConfig.class, SearchUrlTest.Cipher.class})
class SearchUrlTest extends WebMvcSliceMocks {

    private static final String QUERY = "Jane Roe 555-01-2345";

    @TestConfiguration
    static class Cipher {
        @Bean
        SymmetricCipher symmetricCipher() {
            return new SymmetricCipher(Base64.getEncoder().encodeToString(new byte[32]));
        }
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DocumentSearchService searchService;
    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private UserGroupsService userGroupsService;
    @MockitoBean private BatchAccessService batchAccessService;
    @MockitoBean private GeneralSettingsService generalSettingsService;

    @BeforeEach
    void setUp() {
        final List<SearchHit> hits = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            hits.add(new SearchHit("d" + i, "b1", "file" + i + ".txt", "REVIEW_REQUIRED", List.of("snippet")));
        }
        when(searchService.search(eq(QUERY), anyInt(), anyInt(), any())).thenReturn(new SearchResults(25, 0, 10, hits));
        when(auditLogService.hashForAudit(anyString())).thenReturn("hash");
        when(generalSettingsService.load()).thenReturn(new ai.philterd.workspace.model.GeneralSettings());
    }

    private String submit(final MockHttpSession session, final String query) throws Exception {
        return mockMvc.perform(post("/search").param("q", query).session(session)
                        .with(signedIn("admin", "ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
    }

    @Test
    void theQueryIsPostedAndTheResultsAreReachedByAToken() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String results = submit(session, QUERY);

        assertTrue(results.matches("/search\\?r=[0-9a-f]{32}"), results);
        final String page = mockMvc.perform(get(results).session(session).with(signedIn("admin", "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("file0.txt"), "the results");
        assertTrue(page.contains("method=\"post\""), "the form posts");
        final String token = results.substring(results.indexOf("r=") + 2);
        assertTrue(page.contains("/search?r=" + token + "&amp;offset=10"), "the next page keeps the token");
        assertFalse(page.contains("q=Jane") || page.contains("q=" + QUERY.replace(" ", "+")), "no query in a link");

        // The session holds the query encrypted, never as written.
        final Map<?, ?> kept = (Map<?, ?>) session.getAttribute(SearchViewController.class.getName() + ".searches");
        assertEquals(1, kept.size());
        assertFalse(kept.values().iterator().next().toString().contains("Jane"));
    }

    @Test
    void aQueryInTheUrlIsNotRun() throws Exception {
        mockMvc.perform(get("/search").param("q", QUERY).with(signedIn("admin", "ADMIN")))
                .andExpect(status().isOk());
        verify(searchService, never()).search(anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void anUnknownOrEvictedTokenSaysTheSearchEnded() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String first = submit(session, QUERY);
        for (int i = 0; i < 10; i++) {
            submit(session, QUERY + " " + i);
        }

        final String page = mockMvc.perform(get(first).session(session).with(signedIn("admin", "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("This search has ended."), "the oldest of eleven is gone");
        assertEquals(10, ((Map<?, ?>) session.getAttribute(SearchViewController.class.getName() + ".searches"))
                .size());
    }

    @Test
    void anEmptyQueryReturnsToTheForm() throws Exception {
        mockMvc.perform(post("/search").param("q", "  ").with(signedIn("admin", "ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/search"));
    }

    @Test
    void theApiTakesTheQueryInAJsonBody() throws Exception {
        when(philterApiKeys.resolve("sk_valid")).thenReturn(
                new ai.philterd.workspace.webapp.security.PhilterApiKeys.Caller("ci", "ADMIN"));

        final String body = mockMvc.perform(post("/api/v1/search").header("Authorization", "Bearer sk_valid")
                        .contentType("application/json").content("{\"q\":\"" + QUERY + "\",\"offset\":0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"total\":25") && body.contains("file0.txt"), body);

        mockMvc.perform(post("/api/v1/search").header("Authorization", "Bearer sk_valid")
                        .contentType("application/json").content("{\"q\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theApiNoLongerTakesTheQueryInTheUrl() throws Exception {
        when(philterApiKeys.resolve("sk_valid")).thenReturn(
                new ai.philterd.workspace.webapp.security.PhilterApiKeys.Caller("ci", "ADMIN"));

        mockMvc.perform(get("/api/v1/search").param("q", QUERY).header("Authorization", "Bearer sk_valid"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error")
                        .value(org.hamcrest.Matchers.containsString("POST /api/v1/search")));
        verify(searchService, never()).search(anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void theSearchFormNeedsACsrfToken() throws Exception {
        mockMvc.perform(post("/search").param("q", QUERY).with(signedIn("admin", "ADMIN")))
                .andExpect(status().isForbidden());
    }
}
