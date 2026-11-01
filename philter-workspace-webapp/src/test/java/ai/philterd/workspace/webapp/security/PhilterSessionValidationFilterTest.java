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

import ai.philterd.workspace.model.Roles;
import ai.philterd.workspace.model.User;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.philter.model.CurrentUser;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhilterSessionValidationFilterTest {

    private static final long NOW = 1_800_000_000_000L;

    private PhilterSignInService signInService;
    private UserRepository userRepository;
    private PhilterSessionValidationFilter filter;
    private MockHttpSession session;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        signInService = mock(PhilterSignInService.class);
        userRepository = mock(UserRepository.class);
        stored(Roles.USER);
        filter = new PhilterSessionValidationFilter(signInService, userRepository,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        session = new MockHttpSession();
        request = new MockHttpServletRequest("GET", "/queue");
        request.setSession(session);
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
        SecurityContextHolder.getContext().setAuthentication(new PhilterSessionAuthentication("jdoe", "enc-key",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void stored(final String role) {
        final User user = new User();
        user.setEmail("jdoe");
        user.setRoles(Set.of(role));
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.of(user));
    }

    private void signedInAs(final String role) {
        SecurityContextHolder.getContext().setAuthentication(new PhilterSessionAuthentication("jdoe", "enc-key",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static CurrentUser active(final boolean active) {
        final CurrentUser me = new CurrentUser();
        me.setUsername("jdoe");
        me.setRole("user");
        me.setActive(active);
        return me;
    }

    @Test
    void acceptedKeyIsCheckedAndTheRequestContinues() throws Exception {
        when(signInService.currentUser("enc-key")).thenReturn(active(true));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(NOW, session.getAttribute(PhilterSessionValidationFilter.VALIDATED_AT));
    }

    @Test
    void keyIsNotCheckedAgainWithinTheInterval() throws Exception {
        session.setAttribute(PhilterSessionValidationFilter.VALIDATED_AT, NOW - 30_000);

        filter.doFilter(request, response, chain);

        verify(signInService, never()).currentUser(any());
        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectedKeyEndsTheSessionAndReturnsToSignIn() throws Exception {
        session.setAttribute(PhilterSessionValidationFilter.VALIDATED_AT, NOW - 61_000);
        when(signInService.currentUser("enc-key")).thenThrow(new UnauthorizedException("expired"));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertTrue(session.isInvalid());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("/login?expired", response.getRedirectedUrl());
    }

    @Test
    void rejectedKeyOnTheApiIsA401() throws Exception {
        request.setRequestURI("/api/v1/queue");
        when(signInService.currentUser("enc-key")).thenThrow(new UnauthorizedException("revoked"));

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(session.isInvalid());
    }

    @Test
    void deactivatedUserEndsTheSession() throws Exception {
        when(signInService.currentUser("enc-key")).thenReturn(active(false));

        filter.doFilter(request, response, chain);

        assertTrue(session.isInvalid());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void unreachablePhilterWithinTheGracePeriodIsServedAndCheckedAgainNextTime() throws Exception {
        // #82: last checked two minutes ago, within the five-minute grace period.
        session.setAttribute(PhilterSessionValidationFilter.VALIDATED_AT, NOW - 120_000);
        when(signInService.currentUser("enc-key")).thenThrow(new IOException("connection refused"));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(NOW - 120_000, session.getAttribute(PhilterSessionValidationFilter.VALIDATED_AT),
                "not a successful check, so the grace period still runs from the last one");
    }

    @Test
    void unreachablePhilterBeyondTheGracePeriodIsRefusedButTheSessionIsKept() throws Exception {
        session.setAttribute(PhilterSessionValidationFilter.VALIDATED_AT, NOW - 301_000);
        when(signInService.currentUser("enc-key")).thenThrow(new IOException("connection refused"));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(503, response.getStatus());
        assertEquals("30", response.getHeader("Retry-After"));
        assertFalse(session.isInvalid(), "kept, so the person carries on once Philter is back");
        verify(signInService, never()).signOut(any());

        // Philter is back: the next request is checked and served.
        org.mockito.Mockito.doReturn(active(true)).when(signInService).currentUser("enc-key");
        final MockHttpServletResponse next = new MockHttpServletResponse();
        filter.doFilter(request, next, chain);
        verify(chain).doFilter(request, next);
        assertEquals(NOW, session.getAttribute(PhilterSessionValidationFilter.VALIDATED_AT));
    }

    @Test
    void unreachablePhilterBeyondTheGracePeriodIsAJson503OnTheApi() throws Exception {
        session.setAttribute(PhilterSessionValidationFilter.VALIDATED_AT, NOW - 301_000);
        when(signInService.currentUser("enc-key")).thenThrow(new IOException("timed out"));
        final MockHttpServletRequest api = new MockHttpServletRequest("GET", "/api/v1/queue");
        api.setSession(session);

        filter.doFilter(api, response, chain);

        assertEquals(503, response.getStatus());
        assertEquals("application/json", response.getContentType().replaceAll(";.*", ""));
        assertTrue(response.getContentAsString().contains("cannot reach Philter"), response.getContentAsString());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void staticFilesAreServedWithoutACheckSoTheRefusalPageLoads() throws Exception {
        session.setAttribute(PhilterSessionValidationFilter.VALIDATED_AT, NOW - 301_000);
        for (String path : List.of("/js/tailwind.js", "/css/app.css", "/images/philterd-wordmark.svg")) {
            final MockHttpServletRequest asset = new MockHttpServletRequest("GET", path);
            asset.setSession(session);
            final MockHttpServletResponse served = new MockHttpServletResponse();

            filter.doFilter(asset, served, chain);

            verify(chain).doFilter(asset, served);
        }
        verify(signInService, never()).currentUser(any());
    }

    @Test
    void aSessionWithNoSuccessfulCheckIsRefusedWhilePhilterIsUnreachable() throws Exception {
        // Signing in records a check, so only a session from before that has none.
        when(signInService.currentUser("enc-key")).thenThrow(new IOException("connection refused"));

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void theGracePeriodIsConfigurable() throws Exception {
        final PhilterSessionValidationFilter tight = new PhilterSessionValidationFilter(signInService, userRepository,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), java.time.Duration.ofMinutes(1),
                java.time.Duration.ofSeconds(90));
        session.setAttribute(PhilterSessionValidationFilter.VALIDATED_AT, NOW - 120_000);
        when(signInService.currentUser("enc-key")).thenThrow(new IOException("connection refused"));

        tight.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
    }

    @Test
    void signingInRecordsASuccessfulCheck() {
        PhilterSessionValidationFilter.recordCheck(session);
        assertTrue(session.getAttribute(PhilterSessionValidationFilter.VALIDATED_AT) instanceof Long);
    }

    @Test
    void sessionFromBeforeTheSwitchToPhilterSignInIsEnded() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("a@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        filter.doFilter(request, response, chain);

        assertTrue(session.isInvalid());
        verify(signInService, never()).currentUser(any());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void anonymousRequestsAreLeftAlone() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void adminRoleChangedInPhilterEndsTheSession() throws Exception {
        final CurrentUser promoted = active(true);
        promoted.setRole("admin");
        when(signInService.currentUser("enc-key")).thenReturn(promoted);

        filter.doFilter(request, response, chain);

        assertTrue(session.isInvalid());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void adminDemotedInPhilterEndsTheSession() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new PhilterSessionAuthentication("jdoe", "enc-key",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(signInService.currentUser("enc-key")).thenReturn(active(true));

        filter.doFilter(request, response, chain);

        assertTrue(session.isInvalid());
    }

    @Test
    void auditorStaysSignedIn() throws Exception {
        signedInAs(Roles.AUDITOR);
        stored(Roles.AUDITOR);
        when(signInService.currentUser("enc-key")).thenReturn(active(true));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void roleNameCaseFromPhilterDoesNotMatter() throws Exception {
        signedInAs(Roles.ADMIN);
        final CurrentUser me = active(true);
        me.setRole("Admin");
        when(signInService.currentUser("enc-key")).thenReturn(me);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void roleChangedInWorkspaceEndsTheSessionAndRevokesTheKey() throws Exception {
        stored(Roles.AUDITOR);
        when(signInService.currentUser("enc-key")).thenReturn(active(true));

        filter.doFilter(request, response, chain);

        assertTrue(session.isInvalid());
        verify(signInService).signOut("enc-key");
    }

    @Test
    void deletedWorkspaceRecordEndsTheSession() throws Exception {
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.empty());
        when(signInService.currentUser("enc-key")).thenReturn(active(true));

        filter.doFilter(request, response, chain);

        assertTrue(session.isInvalid());
    }

    @Test
    void keyAlreadyRejectedByPhilterIsNotRevokedAgain() throws Exception {
        when(signInService.currentUser("enc-key")).thenThrow(new UnauthorizedException("expired"));

        filter.doFilter(request, response, chain);

        verify(signInService, never()).signOut(any());
    }

    @Test
    void bearerRequestsAreLeftToTheBearerFilter() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("api@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        request.setAttribute(ApiKeyAuthFilter.BEARER_AUTH_ATTR, Boolean.TRUE);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void intervalOfZeroChecksEveryRequest() throws Exception {
        final PhilterSessionValidationFilter everyRequest = new PhilterSessionValidationFilter(signInService,
                userRepository, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                java.time.Duration.ZERO);
        when(signInService.currentUser("enc-key")).thenReturn(active(true));

        everyRequest.doFilter(request, response, chain);
        everyRequest.doFilter(request, new MockHttpServletResponse(), chain);

        verify(signInService, org.mockito.Mockito.times(2)).currentUser("enc-key");
    }
}
