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

import ai.philterd.workspace.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class SecurityConfig {

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(final PhilterApiKeys philterApiKeys) {
        return new ApiKeyAuthFilter(philterApiKeys);
    }

    /**
     * The CSRF token store, a bean so the sign-in step that completes outside the form login (the MFA
     * code through Philter) can replace the token as the form login does.
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Tracks live sessions per principal so we can expire them on
     * password change, role change, or account deletion (R2-F12). Without this
     * an attacker who has hijacked a session keeps full access even after the
     * legitimate user rotates their credentials.
     *
     * <p>{@link HttpSessionEventPublisher} forwards servlet container session
     * lifecycle events to the registry so it stays in sync with reality.
     *
     * <p>Sessions are kept in Valkey through Spring Session, but this registry is in
     * memory: it only knows sessions created on this replica since it started. With
     * several replicas, or after a restart, expiring a principal's sessions misses the
     * others; with Philter sign-in the session check ends them at its next interval.
     * {@code SpringSessionBackedSessionRegistry} would track them all.
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /** People sign in through Philter, with their Philter username and password. */
    @Bean
    public AuthenticationProvider authenticationProvider(final PhilterSignInService philterSignInService,
                                                         final PhilterSignInFlow philterSignInFlow) {
        return new PhilterAuthenticationProvider(philterSignInService, philterSignInFlow);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http,
                                                   final ApiKeyAuthFilter apiKeyAuthFilter,
                                                   final AuthenticationProvider authenticationProvider,
                                                   final PhilterSignInService philterSignInService,
                                                   final PhilterSignInFlow philterSignInFlow,
                                                   final UserRepository userRepository,
                                                   final CsrfTokenRepository csrfTokenRepository,
                                                   @Value("${philter-workspace.sign-in.check-interval-seconds:60}") final long checkIntervalSeconds,
                                                   @Value("${philter-workspace.sign-in.unreachable-grace-seconds:300}") final long unreachableGraceSeconds)
            throws Exception {
        if (checkIntervalSeconds < 0) {
            throw new IllegalStateException("philter-workspace.sign-in.check-interval-seconds must be zero or more.");
        }
        if (unreachableGraceSeconds < 0) {
            throw new IllegalStateException("philter-workspace.sign-in.unreachable-grace-seconds must be zero or more.");
        }
        // Signing in counts as a successful check of the session, the start of its grace period (#82).
        final SavedRequestAwareAuthenticationSuccessHandler successHandler =
                new SavedRequestAwareAuthenticationSuccessHandler() {
                    @Override
                    public void onAuthenticationSuccess(final jakarta.servlet.http.HttpServletRequest request,
                                                        final jakarta.servlet.http.HttpServletResponse response,
                                                        final org.springframework.security.core.Authentication auth)
                            throws jakarta.servlet.ServletException, java.io.IOException {
                        PhilterSessionValidationFilter.recordCheck(request.getSession());
                        super.onAuthenticationSuccess(request, response, auth);
                    }
                };
        successHandler.setDefaultTargetUrl("/");

        // Paths that ADMIN-or-AUDITOR can read but only ADMIN may mutate. Listed once and
        // reused for the GET-vs-other-method split below so the two matchers stay in sync.
        // /batches and /batches/** are intentionally NOT in this list — those endpoints
        // also serve TEAM LEADs (per-group lead authority), and per-resource lead checks
        // are a per-endpoint decision the controller makes, not something the framework
        // matchers can express. They fall through to .anyRequest().authenticated() and the
        // controller gates writes via BatchAccessService.canLeadBatch.
        final String[] adminScopedPaths = {
                "/admin/**", "/reporting",
                "/policies", "/policies/**",
                "/api/v1/policies", "/api/v1/policies/**"
        };

        http
                // A manager of its own, with no parent: the global manager built from the same
                // provider bean would otherwise run the provider a second time after a failed
                // sign-in, counting each failure twice.
                .authenticationManager(new ProviderManager(authenticationProvider))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/sign-in/**",
                                "/css/**", "/js/**", "/images/**", "/webjars/**", "/docs/**", "/error").permitAll()
                        // Health endpoint. Unauthenticated so a probe can call it before any
                        // credential exists. Returns the aggregate status and build version
                        // only; that version is a reason to keep the port internal.
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        // Philter's webhook deliveries. Philter signs each one with the shared secret, and
                        // the controller refuses any whose signature or timestamp does not check out.
                        .requestMatchers(HttpMethod.POST, ai.philterd.workspace.api.controller.PhilterWebhookController.PATH)
                                .permitAll()
                        // Actuator, GET-only and unauthenticated, matching Philter. Only
                        // health and prometheus are exposed, so the wildcard cannot reach
                        // env, beans, loggers, or heapdump.
                        .requestMatchers(HttpMethod.GET, "/actuator/**").permitAll()
                        // OpenAPI / Swagger UI is admin-only (R2-F2). Previously
                        // permitAll(), which let any unauthenticated caller enumerate
                        // every admin endpoint and DTO shape — free reconnaissance.
                        // The /v3/api-docs and /swagger-ui paths are also disabled by
                        // default at the springdoc level (application.properties); set
                        // PHILTER_WORKSPACE_OPENAPI_ENABLED=true on a deployment that needs them.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasRole("ADMIN")
                        // Admin → Tools hosts destructive maintenance actions (today: data
                        // import job cleanup). Auditors are read-only and shouldn't even see
                        // the page, so this path is admin-only for both GETs and writes —
                        // listed BEFORE the auditor-readable adminScopedPaths matcher so the
                        // tighter rule wins.
                        .requestMatchers("/admin/tools", "/admin/tools/**").hasRole("ADMIN")
                        // Reads on admin-scoped paths: admins and auditors. Auditors are a
                        // global read role — they see the same cross-group data admins see
                        // (audit log, queue, batches, reports) but never mutate state.
                        .requestMatchers(HttpMethod.GET, adminScopedPaths).hasAnyRole("ADMIN", "AUDITOR")
                        // Anything else on the same paths (POST/PUT/PATCH/DELETE) — admin only.
                        .requestMatchers(adminScopedPaths).hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("username")
                        .successHandler(successHandler)
                        .failureHandler(new PhilterSignInFailureHandler())
                        .permitAll())
                // Plug the SessionRegistry into the security pipeline so the
                // password-change / role-change paths can expire other live
                // sessions for the principal (R2-F12). maximumSessions(-1) =
                // unlimited concurrent sessions — we're not enforcing a cap
                // here, just registering each session so it can be expired
                // on demand.
                .sessionManagement(s -> s
                        .sessionFixation().changeSessionId()
                        .maximumSessions(-1).sessionRegistry(sessionRegistry()).expiredUrl("/login?expired"))
                .logout(logout -> logout
                        .addLogoutHandler(new PhilterSignOutHandler(philterSignInFlow))
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.pathPattern("/api/**"))
                        .accessDeniedHandler(new ExpiredFormAccessDeniedHandler()))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // After the session's SecurityContext is loaded, before authorization: a person whose
                // Philter session key was rejected is signed out of Philter Workspace rather than served.
                .addFilterBefore(new PhilterSessionValidationFilter(philterSignInService, userRepository,
                        Clock.systemUTC(), Duration.ofSeconds(checkIntervalSeconds),
                        Duration.ofSeconds(unreachableGraceSeconds)),
                        AuthorizationFilter.class)
                // Drop session-cookie authentication on /api/** so only Bearer keys count;
                // runs after the Bearer filter (which marks the request) and before the
                // authorization gate that decides 200 vs 401.
                .addFilterBefore(new ApiSessionRejectingFilter(), AuthorizationFilter.class)
                // Enforce the AUDITOR read-only contract on every path the
                // authorizeHttpRequests matchers don't already gate to ADMIN. Runs after
                // both authentication filters so the SecurityContext is populated, and
                // before AuthorizationFilter so the 403 it returns is the one the user sees.
                .addFilterBefore(new AuditorWriteRejectFilter(), AuthorizationFilter.class)
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository).ignoringRequestMatchers("/api/**"))
                // Defence-in-depth response headers. Philter Workspace renders document text
                // and reviewer comments through Thymeleaf (escaped via th:text), but
                // any future regression introducing an unescaped sink would otherwise
                // execute with full document scope and could fetch /api/v1/** to
                // exfiltrate PII. The CSP below restricts script/object/base/form
                // sources to same-origin only, blocking the exfil chain.
                //
                // HSTS protects against first-visit downgrade for deployments behind
                // a TLS-terminating reverse proxy (production model — see
                // server.forward-headers-strategy=framework).
                // CSP is written by CspNonceFilter (per-request nonce so legitimate
                // inline scripts run while attacker-injected ones — which can't know
                // the nonce — are refused). It MUST run before view rendering so the
                // nonce is exposed as a request attribute Thymeleaf can read; a
                // HeaderWriter would fire too late (at response-commit time, after the
                // template has already resolved ${cspNonce} to null and dropped every
                // <script> tag's nonce attribute). Don't also write a static
                // script-src 'self' via the default DSL: the two would race and the
                // static one would either replace the nonced policy or land alongside
                // it, both of which neutralise the protection.
                .addFilterBefore(new CspNonceFilter(),
                        org.springframework.security.web.header.HeaderWriterFilter.class)
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)
                                .preload(true))
                        .referrerPolicy(rp -> rp.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicy(pp -> pp.policy(
                                "geolocation=(), microphone=(), camera=(), payment=(), "
                                        + "usb=(), midi=(), magnetometer=(), gyroscope=(), accelerometer=()")));
        return http.build();
    }
}
