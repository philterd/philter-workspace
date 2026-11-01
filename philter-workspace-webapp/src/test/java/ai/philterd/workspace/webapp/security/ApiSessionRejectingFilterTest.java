/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApiSessionRejectingFilterTest {

    private ApiSessionRejectingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiSessionRejectingFilter();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication sessionAuth(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static MockHttpServletRequest req(final String uri) {
        final MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRequestURI(uri);
        return r;
    }

    private static MockHttpServletRequest bearerReq(final String uri) {
        final MockHttpServletRequest r = req(uri);
        r.setAttribute(ApiKeyAuthFilter.BEARER_AUTH_ATTR, Boolean.TRUE);
        return r;
    }

    // ----- Programmatic Bearer-only endpoints: session auth must be stripped -----

    @Test
    void stripsSessionAuthOnIngestPath() throws Exception {
        // /api/v1/ingest is the canonical external API write — it accepts only
        // Bearer auth, so a logged-in admin's session cookie is dropped here so
        // a malicious page can't trick them into POSTing fake documents.
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/ingest"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void stripsSessionAuthOnSearchPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/search"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void stripsSessionAuthOnDocumentFinalize() throws Exception {
        // /api/v1/documents/{id}/finalize is an external Export endpoint that
        // shares a URL prefix with browser-UI document endpoints. Match by suffix
        // so the Bearer-only rule applies.
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/documents/doc-123/finalize"),
                new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void stripsSessionAuthOnDocumentAudit() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/documents/doc-123/audit"),
                new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void preservesBearerAuthOnIngestPath() throws Exception {
        // Bearer-authenticated requests pass through even on Bearer-only paths —
        // that's the intended way to call them.
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(bearerReq("/api/v1/ingest"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ----- Browser-UI /api/v1/** endpoints: session auth must be preserved -----

    @Test
    void preservesSessionAuthOnQueuePath() throws Exception {
        // /api/v1/queue is called by the Documents to Review page. The page is
        // authenticated by session cookie, so the AJAX call inherits the same.
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/queue?page=0"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void preservesSessionAuthOnBatchesPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/batches"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void preservesSessionAuthOnDocumentSpansPath() throws Exception {
        // The review page reads spans via this path. /finalize and /audit on
        // the same prefix are Bearer-only (covered above); /spans is UI-only.
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/documents/doc-123/spans"),
                new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void preservesSessionAuthOnDocumentHistoryPath() throws Exception {
        // /history is browser-UI; /audit is the Bearer-only export. They share
        // a URL prefix so the suffix-based match has to be exact.
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/documents/doc-123/history"),
                new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void preservesSessionAuthOnSpanPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/spans/span-1"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void preservesSessionAuthOnPulsePath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/api/v1/review/doc1/pulse"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ----- Non-API paths: always untouched -----

    @Test
    void preservesSessionAuthOnUiPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));

        filter.doFilter(req("/queue"), new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ----- Filter chain always called -----

    @Test
    void alwaysCallsFilterChain() throws Exception {
        final FilterChain chain = mock(FilterChain.class);
        final MockHttpServletRequest request = req("/api/v1/queue");
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    // ----- Context-path stripping -----

    @Test
    void stripsSessionAuthOnIngestPathWithContextPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));
        final MockHttpServletRequest r = new MockHttpServletRequest();
        r.setContextPath("/philter-workspace");
        r.setRequestURI("/philter-workspace/api/v1/ingest");

        filter.doFilter(r, new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void preservesSessionAuthForBatchesPathWithContextPath() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(sessionAuth("user@x.com"));
        final MockHttpServletRequest r = new MockHttpServletRequest();
        r.setContextPath("/philter-workspace");
        r.setRequestURI("/philter-workspace/api/v1/batches");

        filter.doFilter(r, new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
