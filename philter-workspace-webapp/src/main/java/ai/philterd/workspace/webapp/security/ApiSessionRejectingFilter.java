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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects session-cookie authentication for the small set of {@code /api/**} endpoints
 * intended for programmatic (Bearer-only) clients.
 *
 * <p>{@code /api/**} hosts two kinds of endpoint:
 * <ol>
 *   <li><strong>Browser-UI endpoints</strong> (queue, batches, span CRUD, document
 *       comments / explain / certificate / history, policies editor, Ollama models,
 *       review locks). These are AJAX calls from logged-in users' browsers and rely
 *       on the same session cookie the rest of the UI uses. Cross-origin attacks on
 *       these are blocked by the browser's same-origin policy plus the absence of a
 *       permissive CORS configuration — a malicious page can't trigger a JSON
 *       request because Philter Workspace doesn't allow any cross-origin requests.</li>
 *   <li><strong>External programmatic endpoints</strong> (the documented ingest,
 *       search, finalize, and per-document audit-export endpoints). These are
 *       intended to be called by scripts and integrations holding a personal API
 *       key, and must <em>not</em> accept the session cookie of a logged-in
 *       admin who happens to visit a malicious page — a CSRF on {@code /ingest}
 *       could push fake documents into a batch under that admin's identity.</li>
 * </ol>
 *
 * <p>This filter enforces Bearer-only authentication on category 2 only. The
 * companion {@link ApiKeyAuthFilter} marks Bearer-authenticated requests via the
 * {@link ApiKeyAuthFilter#BEARER_AUTH_ATTR} request attribute. For each request
 * matching one of the programmatic-only paths, this filter checks that attribute;
 * if the request is authenticated but was <em>not</em> Bearer-authenticated, it
 * clears the SecurityContext so the downstream {@code AuthorizationFilter}
 * treats the call as anonymous and returns 401. Category 1 (browser UI) and
 * non-{@code /api/**} requests are passed through untouched.
 */
public class ApiSessionRejectingFilter extends OncePerRequestFilter {

    /**
     * The Bearer-only paths. They are matched on the decoded path with the same path patterns Spring MVC dispatches
     * with, so a percent-encoded form of a path, such as {@code /api/v1/%73earch}, is matched like the path itself
     * (#81). Each ends in {@code /**}, which also matches the path alone and with a trailing slash.
     */
    private static final RequestMatcher BEARER_ONLY = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern("/api/v1/ingest/**"),
            PathPatternRequestMatcher.pathPattern("/api/v1/search/**"),
            // Export endpoints that share /api/v1/documents/ with browser-UI document endpoints.
            PathPatternRequestMatcher.pathPattern("/api/v1/documents/*/finalize/**"),
            PathPatternRequestMatcher.pathPattern("/api/v1/documents/*/audit/**"));

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        if (isBearerOnly(request)) {
            final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !Boolean.TRUE.equals(request.getAttribute(ApiKeyAuthFilter.BEARER_AUTH_ATTR))) {
                // Authenticated, but not via the Bearer filter. The only other source is the
                // session cookie via SecurityContextHolderFilter — drop it for these paths so
                // the request is treated as anonymous downstream and gets a 401.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isBearerOnly(final HttpServletRequest request) {
        return BEARER_ONLY.matches(request);
    }
}
