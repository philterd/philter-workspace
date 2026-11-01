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

import ai.philterd.workspace.model.User;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.philter.model.CurrentUser;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ends the Philter Workspace session of a person signed in through Philter once Philter rejects their session key:
 * when it passes its idle timeout or maximum lifetime, is revoked, or is revoked by a password reset or
 * deactivation. Also ends it when the person's role has changed, in Philter or in Philter Workspace, or their
 * Philter Workspace record was deleted, so they sign in again with the new role, and ends any session left from
 * Philter Workspace's removed local sign-in. Checks the key with {@code GET /api/users/me} at most
 * once per interval ({@code philter-workspace.sign-in.check-interval-seconds}, default one minute; zero checks
 * every request), which also keeps Philter's idle timeout moving while the person uses Philter Workspace.
 *
 * <p>If Philter cannot be reached (unreachable, a timeout, a server error), the session keeps working for a grace
 * period after its last successful check ({@code philter-workspace.sign-in.unreachable-grace-seconds}, default five
 * minutes), and each request checks again. After that, requests are refused with 503 until a check succeeds, so a
 * person deactivated in Philter cannot keep their session through an outage (#82). The session is kept, so the person
 * carries on without signing in again once Philter is back. Only a rejected key ends the session. Signing in counts as
 * a successful check ({@link #recordCheck}).
 */
public class PhilterSessionValidationFilter extends OncePerRequestFilter {

    /** Session attribute: when the session key was last checked with Philter, in epoch milliseconds. */
    public static final String VALIDATED_AT = "PHILTER_SESSION_VALIDATED_AT";
    /** The default for {@code philter-workspace.sign-in.check-interval-seconds}. */
    static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);
    /** The default for {@code philter-workspace.sign-in.unreachable-grace-seconds}. */
    static final Duration DEFAULT_GRACE = Duration.ofMinutes(5);
    /** The API's answer once the grace period is over. */
    static final String UNREACHABLE_JSON = "{\"error\":\"Philter Workspace cannot reach Philter to check your"
            + " session. Try again shortly.\"}";

    private static final Logger log = LoggerFactory.getLogger(PhilterSessionValidationFilter.class);

    private final PhilterSignInService signInService;
    private final UserRepository userRepository;
    private final Clock clock;
    private final Duration interval;
    private final Duration grace;

    public PhilterSessionValidationFilter(final PhilterSignInService signInService,
                                          final UserRepository userRepository,
                                          final Clock clock) {
        this(signInService, userRepository, clock, DEFAULT_INTERVAL, DEFAULT_GRACE);
    }

    public PhilterSessionValidationFilter(final PhilterSignInService signInService,
                                          final UserRepository userRepository,
                                          final Clock clock,
                                          final Duration interval) {
        this(signInService, userRepository, clock, interval, DEFAULT_GRACE);
    }

    public PhilterSessionValidationFilter(final PhilterSignInService signInService,
                                          final UserRepository userRepository,
                                          final Clock clock,
                                          final Duration interval,
                                          final Duration grace) {
        this.signInService = signInService;
        this.userRepository = userRepository;
        this.clock = clock;
        this.interval = interval;
        this.grace = grace;
    }

    /** Records a successful check of the session's key, as signing in is: the start of the grace period (#82). */
    public static void recordCheck(final HttpSession session) {
        session.setAttribute(VALIDATED_AT, System.currentTimeMillis());
    }

    /**
     * Public static files, which carry no data. Not checked, so a page's scripts and images load while Philter is
     * unreachable, including on the "Philter cannot be reached" page itself, and each page view checks once, not once
     * per file (#82).
     */
    private static final org.springframework.security.web.util.matcher.RequestMatcher STATIC_FILES =
            new org.springframework.security.web.util.matcher.OrRequestMatcher(
                    java.util.stream.Stream.of("/css/**", "/js/**", "/images/**", "/webjars/**", "/docs/**")
                            .map(path -> (org.springframework.security.web.util.matcher.RequestMatcher)
                                    org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
                                            .pathPattern(path))
                            .toList());

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return STATIC_FILES.matches(request);
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        final HttpSession session = request.getSession(false);
        if (session == null || auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken
                || Boolean.TRUE.equals(request.getAttribute(ApiKeyAuthFilter.BEARER_AUTH_ATTR))) {
            chain.doFilter(request, response);
            return;
        }
        // Every session is a Philter one. Anything else is left from Philter Workspace's removed local sign-in.
        if (!(auth instanceof PhilterSessionAuthentication philter)) {
            end(request, response, session, auth.getName(), null, "sign_in_mode_changed");
            return;
        }
        final long now = clock.millis();
        if (session.getAttribute(VALIDATED_AT) instanceof Long last && now - last < interval.toMillis()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            final CurrentUser me = signInService.currentUser(philter.getEncryptedSessionKey());
            if (me == null || !me.isActive()) {
                end(request, response, session, philter.getName(), philter.getEncryptedSessionKey(),
                        "philter_session_ended");
                return;
            }
            // The role is set at sign-in from Philter's role and Philter Workspace's record. Ending the session
            // when either has changed makes the change apply at the next check, not the next sign-in.
            final User user = userRepository.findByEmail(philter.getName()).orElse(null);
            if (user == null || !hasRole(philter, PhilterUserProvisioner.roleFor(me.getRole(), user.getRoles()))) {
                end(request, response, session, philter.getName(), philter.getEncryptedSessionKey(),
                        "role_changed");
                return;
            }
            session.setAttribute(VALIDATED_AT, now);
        } catch (UnauthorizedException e) {
            end(request, response, session, philter.getName(), null, "philter_session_ended");
            return;
        } catch (Exception e) {
            log.warn("Could not check a Philter session key: {}", PhilterClients.describe(e));
            // Served for the grace period after the last successful check, then refused until a check succeeds.
            if (!(session.getAttribute(VALIDATED_AT) instanceof Long last) || now - last > grace.toMillis()) {
                refuseUnreachable(request, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Answers 503, keeping the session: the API with a JSON reason, a page with {@code error/503.html}. */
    private static void refuseUnreachable(final HttpServletRequest request, final HttpServletResponse response)
            throws IOException {
        response.setHeader("Retry-After", "30");
        final String path = request.getRequestURI();
        if (path != null && path.startsWith(request.getContextPath() + "/api/")) {
            // Written here rather than with sendError, which would drop the reason (error messages are not sent).
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(UNREACHABLE_JSON);
        } else {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
    }

    private static boolean hasRole(final PhilterSessionAuthentication philter, final String role) {
        final Set<String> authorities = philter.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        return authorities.equals(Set.of("ROLE_" + role));
    }

    /**
     * Ends the Philter Workspace session, and revokes the Philter key ({@code keyToRevoke}) when Philter has not
     * already rejected it, so it does not stay usable until Philter's timeouts.
     */
    private void end(final HttpServletRequest request, final HttpServletResponse response,
                     final HttpSession session, final String username, final String keyToRevoke,
                     final String reason) throws IOException {
        if (keyToRevoke != null) {
            try {
                signInService.signOut(keyToRevoke);
            } catch (Exception e) {
                log.warn("Could not revoke a Philter session key: {}", PhilterClients.describe(e));
            }
        }
        // Philter's audit log records the key's revocation or expiry, so the session's end is not audited here.
        log.info("Ended a Philter Workspace session: {}", reason);
        session.invalidate();
        SecurityContextHolder.clearContext();
        final String path = request.getRequestURI();
        if (path != null && path.startsWith(request.getContextPath() + "/api/")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } else {
            response.sendRedirect(request.getContextPath() + "/login?expired");
        }
    }
}
