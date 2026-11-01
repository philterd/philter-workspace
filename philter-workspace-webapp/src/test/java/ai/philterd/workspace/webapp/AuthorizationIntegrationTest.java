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

import ai.philterd.workspace.repository.AuditLogRepository;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentCommentRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.GeneralSettingsRepository;
import ai.philterd.workspace.repository.GroupRepository;
import ai.philterd.workspace.repository.InboxMessageRepository;
import ai.philterd.workspace.repository.LlmJudgeDefaultsRepository;
import ai.philterd.workspace.repository.NotificationSettingsRepository;
import ai.philterd.workspace.repository.OllamaInstanceRepository;
import ai.philterd.workspace.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.workspace.repository.LocalDirectoryDestinationRepository;
import ai.philterd.workspace.repository.OpenSearchDataSourceRepository;
import ai.philterd.workspace.repository.PendingUploadRepository;
import ai.philterd.workspace.repository.RelationalDbDataSourceRepository;
import ai.philterd.workspace.repository.S3DataSourceRepository;
import ai.philterd.workspace.repository.S3DestinationRepository;
import ai.philterd.workspace.repository.RedactionCertificateRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.repository.UserSettingsRepository;
import ai.philterd.workspace.repository.WeightSetRepository;
import ai.philterd.workspace.service.DestinationTester;
import ai.philterd.workspace.service.RedactionService;
import ai.philterd.workspace.webapp.controllers.AdminComplianceProfileController;
import ai.philterd.workspace.webapp.controllers.AdminGeneralController;
import ai.philterd.workspace.webapp.controllers.AdminGroupController;
import ai.philterd.workspace.webapp.controllers.AdminNotificationsController;
import ai.philterd.workspace.webapp.controllers.AdminOllamaController;
import ai.philterd.workspace.webapp.controllers.AdminPhilterController;
import ai.philterd.workspace.webapp.controllers.AdminToolsController;
import ai.philterd.workspace.webapp.controllers.AdminWeightSetController;
import ai.philterd.workspace.webapp.controllers.AuditLogAdminController;
import ai.philterd.workspace.webapp.controllers.BatchController;
import ai.philterd.workspace.webapp.controllers.PolicyController;
import ai.philterd.workspace.webapp.controllers.RedactionController;
import ai.philterd.workspace.webapp.controllers.ReportingController;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test focused on permissions and authorization. Loads several controllers behind the
 * real {@link SecurityConfig} so the URL-pattern rules — admin gating on {@code /admin/**},
 * {@code /policies/**}, {@code /api/v1/policies/**} and {@code /reporting}, plus the
 * authenticated-by-default rule for everything else — are exercised end-to-end.
 */
@WebMvcTest(controllers = {
        AdminComplianceProfileController.class,
        AdminGeneralController.class,
        AdminGroupController.class,
        AdminNotificationsController.class,
        AdminOllamaController.class,
        AdminPhilterController.class,
        AdminToolsController.class,
        AdminWeightSetController.class,
        AuditLogAdminController.class,
        BatchController.class,
        PolicyController.class,
        ReportingController.class,
        RedactionController.class,
        ai.philterd.workspace.api.controller.HealthController.class,
})
@Import({SecurityConfig.class,
        ai.philterd.workspace.service.BatchAccessService.class,
        ai.philterd.workspace.service.DocumentAccessService.class})
public class AuthorizationIntegrationTest extends WebMvcSliceMocks {

    /** Philter answers a policy listing with no policies. */
    @org.junit.jupiter.api.BeforeEach
    void stubPhilter() throws Exception {
        final ai.philterd.philter.PhilterClient client = org.mockito.Mockito.mock(ai.philterd.philter.PhilterClient.class);
        when(client.listPolicies(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(ai.philterd.philter.model.ListOptions.class)))
                .thenReturn(new ai.philterd.philter.model.Page<>(List.of(), 0));
        when(philterSettings.client()).thenReturn(client);
    }

    @Autowired private MockMvc mockMvc;


    // ---------------------------------------------------------------------
    // Anonymous: form-based UI redirects to /login; /api/** returns 401.
    // ---------------------------------------------------------------------

    @Test
    void anonymousIsBlockedFromUiPaths() throws Exception {
        // Anonymous must not be served any authenticated page — either by redirect
        // to /login (form-login entry point) or by HTTP 401, depending on which entry
        // point Spring Security activates.
        for (String path : new String[]{"/", "/batches", "/upload",
                "/admin/general", "/policies", "/reporting"}) {
            mockMvc.perform(get(path)).andExpect(notOk());
        }
    }

    private static org.springframework.test.web.servlet.ResultMatcher notOk() {
        return result -> {
            final int s = result.getResponse().getStatus();
            org.junit.jupiter.api.Assertions.assertTrue(
                    s == 401 || (s >= 300 && s < 400),
                    "anonymous request should be 401 or redirect, was " + s);
        };
    }

    @Test
    void anonymousGetsUnauthorizedFromApi() throws Exception {
        // /api/** returns HTTP 401 (no redirect) — see exceptionHandling rule in SecurityConfig.
        mockMvc.perform(get("/api/v1/policies")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/policies/content?name=Default"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousCanReachHealth() throws Exception {
        // The one /api/** path deliberately open to unauthenticated callers, so a container
        // runtime, load balancer, or the appliance console can probe it. No BuildProperties
        // bean exists in this slice, so the version falls back to "unknown".
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.applicationVersion").value("unknown"));
    }

    @Test
    void anonymousIsNotBlockedFromActuator() throws Exception {
        // Actuator is not mapped in this slice, so this asserts the security matcher only: the
        // request reaches the dispatcher and 404s rather than being bounced to /login.
        // ActuatorExposureTest covers which endpoints exist.
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
    }

    @Test
    void actuatorRejectsNonGetMethods() throws Exception {
        // permitAll covers GET only, matching the /api/health rule.
        mockMvc.perform(post("/actuator/health").with(csrf())).andExpect(notOk());
    }

    @Test
    void healthRejectsNonGetMethods() throws Exception {
        // permitAll covers GET only; anything else falls through to authenticated().
        mockMvc.perform(post("/api/health")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------
    // Non-admin (USER role): admin endpoints are forbidden, regular endpoints work.
    // ---------------------------------------------------------------------

    @Test
    void userRoleForbiddenFromAdminPaths() throws Exception {
        mockMvc.perform(get("/admin/general").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/users").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/groups").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/notifications").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/audit").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/philter").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/llm-judge").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/weights").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void userRoleForbiddenFromPoliciesAndReporting() throws Exception {
        mockMvc.perform(get("/policies").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/policies/some-id/edit").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
        // /api/v1/policies is reachable from the browser UI via session cookie, so
        // a USER hitting it via the framework matcher gets 403 (not 401) — the
        // session is preserved, the role check fails. The Bearer-only paths
        // (/api/v1/ingest etc.) still strip session auth and 401 — see
        // cookieAuthRejectedOnApiPaths below.
        mockMvc.perform(get("/api/v1/policies").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/policies/content?name=Default")
                .with(signedIn("u", "USER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/reporting").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
        // /batches is intentionally NOT in this list — it's open to .authenticated() so
        // team leads can reach it. Non-lead USERs see the page filtered to their groups
        // (empty by default) but the framework no longer blocks the request at 403. The
        // write-side gate is now controller-level (BatchAccessService.canLeadBatch) — see
        // userRoleCannotModifyBatches for that boundary.
    }

    @Test
    void userRoleAllowedOnRegularPaths() throws Exception {
        mockMvc.perform(get("/").with(signedIn("u", "USER"))).andExpect(status().isOk());
        mockMvc.perform(get("/upload").with(signedIn("u", "USER"))).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------
    // Admin role: previously-forbidden URLs are reachable.
    // ---------------------------------------------------------------------

    @Test
    void adminRoleReachesAdminPaths() throws Exception {
        mockMvc.perform(get("/admin/general").with(signedIn("a", "ADMIN")))
                .andExpect(status().isOk())
                // Reviewer-confirmed detection rules reach every group (#84).
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.containsString("changes what is redacted in every")));
        mockMvc.perform(get("/admin/users").with(signedIn("a", "ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/policies").with(signedIn("a", "ADMIN")))
                .andExpect(status().isOk());
        // /api/v1/policies is browser-UI accessible (the policy editor reads it
        // via fetch with a session cookie) and the framework matcher requires
        // ADMIN, so an admin reaches it just like any UI page.
        mockMvc.perform(get("/api/v1/policies").with(signedIn("a", "ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reporting").with(signedIn("a", "ADMIN")))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------
    // /api/** has two kinds of endpoint:
    //   - Browser-UI endpoints (queue, batches, policies, spans, etc.) accept
    //     session cookies — the in-page JS calls them with the same cookie the
    //     surrounding page is authenticated with.
    //   - External programmatic endpoints (ingest, search, document finalize,
    //     document audit export) are Bearer-only — session cookies are dropped
    //     by ApiSessionRejectingFilter so a CSRF on a logged-in admin can't
    //     reach them.
    // ---------------------------------------------------------------------

    @Test
    void cookieAuthRejectedOnBearerOnlyApiPaths_admin() throws Exception {
        // Even an authenticated admin gets 401 on the truly programmatic API
        // endpoints without a Bearer token — the session cookie is dropped
        // before the framework's role check has a chance to grant access.
        mockMvc.perform(post("/api/v1/ingest").with(signedIn("a", "ADMIN")).with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/search").contentType("application/json").content("{\"q\":\"hi\"}")
                        .with(signedIn("a", "ADMIN")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cookieAuthRejectedOnBearerOnlyApiPaths_user() throws Exception {
        mockMvc.perform(post("/api/v1/ingest").with(signedIn("u", "USER")).with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/search").contentType("application/json").content("{\"q\":\"hi\"}")
                        .with(signedIn("u", "USER")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cookieAuthRejectedOnPercentEncodedFormsOfBearerOnlyApiPaths() throws Exception {
        // #81: Spring MVC dispatches on the decoded path, so an encoded letter must not slip past the filter.
        final String[][] requests = {
                {"POST", "/api/v1/%69ngest"},
                {"POST", "/api/v1/ingest%2F"},
                {"POST", "/api/v1/%73earch"},
                {"POST", "/api/v1/documents/d1/%66inalize"},
                {"POST", "/api/v1/documents/d1/finaliz%65"},
                {"GET", "/api/v1/documents/d1/%61udit"},
                {"GET", "/api/v1/%64ocuments/d1/audit"},
        };
        for (String[] r : requests) {
            final java.net.URI uri = java.net.URI.create(r[1]);
            final org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                    "GET".equals(r[0]) ? get(uri) : post(uri);
            final int status = mockMvc.perform(request.contentType("application/json").content("{\"q\":\"hi\"}")
                            .with(signedIn("a", "ADMIN")).with(csrf()))
                    .andReturn().getResponse().getStatus();
            // An encoded slash never reaches the filter: Spring Security's firewall refuses it with 400.
            org.junit.jupiter.api.Assertions.assertEquals(r[1].contains("%2F") ? 400 : 401, status, r[0] + " " + r[1]);
        }
    }

    @Test
    void cookieAuthAllowedOnBrowserUiApiPaths() throws Exception {
        // Browser-UI endpoints accept the session cookie. /api/v1/queue and
        // /api/v1/batches are called by the Documents-to-Review page; if the
        // filter strips session auth here, the page returns 401 in the JS.
        org.mockito.Mockito.when(batchRepository.findAll(org.mockito.ArgumentMatchers.any(
                        org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));
        mockMvc.perform(get("/api/v1/queue").with(signedIn("u", "USER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/batches").with(signedIn("u", "USER")))
                .andExpect(status().isOk());
    }

    @Test
    void cookieAuthStillWorksOnNonApiPaths() throws Exception {
        // Regression: cookie/session auth still works fine on the UI side.
        mockMvc.perform(get("/policies").with(signedIn("a", "ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/").with(signedIn("u", "USER")))
                .andExpect(status().isOk());
    }

    @Test
    void bearerTokenAuthAllowedOnApiPathsWhenRolePermits() throws Exception {
        org.mockito.Mockito.when(philterApiKeys.resolve("some-api-key"))
                .thenReturn(new ai.philterd.workspace.webapp.security.PhilterApiKeys.Caller("admin", "ADMIN"));

        mockMvc.perform(get("/api/v1/policies")
                        .header("Authorization", "Bearer some-api-key"))
                .andExpect(status().isOk());
    }

    @Test
    void bearerTokenAuthEnforcesRoleOnApiPaths() throws Exception {
        // A USER-role bearer token still gets 403 on the admin-gated /api/v1/policies —
        // bearer auth doesn't bypass authorization, just authentication.
        org.mockito.Mockito.when(philterApiKeys.resolve("some-api-key"))
                .thenReturn(new ai.philterd.workspace.webapp.security.PhilterApiKeys.Caller("reviewer", "USER"));

        mockMvc.perform(get("/api/v1/policies")
                        .header("Authorization", "Bearer some-api-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidBearerTokenStillReturnsUnauthorized() throws Exception {
        org.mockito.Mockito.when(philterApiKeys.resolve("not-a-real-key")).thenThrow(
                new ai.philterd.workspace.webapp.security.PhilterApiKeys.RefusedException(
                        ai.philterd.workspace.webapp.security.PhilterApiKeys.Refusal.INVALID));

        mockMvc.perform(get("/api/v1/policies")
                        .header("Authorization", "Bearer not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cookieAndInvalidBearerTogetherStillRejected() throws Exception {
        // On a Bearer-only endpoint, a present-but-invalid Bearer header doesn't
        // upgrade the request — the session cookie is still dropped, and the
        // bad Bearer fails the lookup, so the request is anonymous and 401s.
        org.mockito.Mockito.when(philterApiKeys.resolve("not-a-real-key")).thenThrow(
                new ai.philterd.workspace.webapp.security.PhilterApiKeys.RefusedException(
                        ai.philterd.workspace.webapp.security.PhilterApiKeys.Refusal.INVALID));

        mockMvc.perform(post("/api/v1/search").contentType("application/json").content("{\"q\":\"x\"}")
                        .with(signedIn("a", "ADMIN"))
                        .header("Authorization", "Bearer not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------
    // CSRF: non-API POSTs require a CSRF token; /api/** is exempt.
    // ---------------------------------------------------------------------

    @Test
    void postWithoutCsrfRejectedOnUiPaths() throws Exception {
        mockMvc.perform(post("/policies").with(signedIn("a", "ADMIN"))
                        .param("name", "p").param("content", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/general/url").with(signedIn("a", "ADMIN"))
                        .param("workspaceUrl", "http://example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postWithCsrfAcceptedOnUiPaths() throws Exception {
        // Admin POSTs work when CSRF token is present.
        mockMvc.perform(post("/admin/general/url").with(signedIn("a", "ADMIN")).with(csrf())
                        .param("workspaceUrl", "http://example.com"))
                .andExpect(status().is3xxRedirection());
    }

    // ---------------------------------------------------------------------
    // /batches now lives on the .authenticated() tier (so team leads can reach
    // their group's batches). Per-endpoint controller checks reject non-leads
    // for mutations: a USER POST returns a 302 redirect with a flash error
    // rather than a framework-layer 403.
    // ---------------------------------------------------------------------

    @Test
    void userRoleCannotModifyBatches() throws Exception {
        // Non-lead USERs trying to modify a batch get bounced via the flash-redirect
        // pattern that BatchController uses for in-controller authorization failures.
        // The mutation does not happen — that's the security guarantee being asserted —
        // even though the HTTP status is 302 instead of 403.
        mockMvc.perform(post("/batches/abc/group").with(signedIn("u", "USER")).with(csrf())
                        .param("groupId", "g1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/batches"));
    }

    // ---------------------------------------------------------------------
    // Admin → Tools page is admin-only on both GET and POST. Unlike the rest of
    // /admin/** (where AUDITOR has read access), the Tools page only hosts
    // destructive maintenance actions, so AUDITORs are forbidden from even
    // viewing it. The SecurityConfig matcher for /admin/tools/** is the only
    // authoritative gate — there's no @PreAuthorize on the controller. These
    // tests pin that gate so a future refactor can't silently relax it.
    // ---------------------------------------------------------------------

    @Test
    void anonymousCannotReachAdminToolsPage() throws Exception {
        final int status = mockMvc.perform(get("/admin/tools"))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || (status >= 300 && status < 400),
                "anonymous request should be 401 or redirect, was " + status);
    }

    @Test
    void userRoleForbiddenFromAdminToolsPage() throws Exception {
        mockMvc.perform(get("/admin/tools").with(signedIn("u", "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditorRoleForbiddenFromAdminToolsPage() throws Exception {
        // AUDITOR has read access to /admin/general, /admin/audit, /admin/users,
        // etc. but NOT to /admin/tools — the Tools page hosts destructive
        // actions only, so auditors shouldn't see it.
        mockMvc.perform(get("/admin/tools").with(signedIn("a", "AUDITOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRoleCanReachAdminToolsPage() throws Exception {
        mockMvc.perform(get("/admin/tools").with(signedIn("a", "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCannotPostCleanupDataImports() throws Exception {
        final int status = mockMvc.perform(post("/admin/tools/cleanup-data-imports").with(csrf()))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || (status >= 300 && status < 400),
                "anonymous POST should be 401 or redirect, was " + status);
    }

    @Test
    void userRoleForbiddenFromCleanupDataImports() throws Exception {
        mockMvc.perform(post("/admin/tools/cleanup-data-imports")
                        .with(signedIn("u", "USER")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditorRoleForbiddenFromCleanupDataImports() throws Exception {
        // AUDITOR can normally read admin pages; making sure a forged POST to the
        // cleanup endpoint with a valid AUDITOR session still 403s. The default
        // /admin/** matcher would already forbid POSTs from AUDITOR (read-only
        // role), but pinning this with an explicit test means a refactor that
        // splits the matchers can't accidentally drop the guarantee.
        mockMvc.perform(post("/admin/tools/cleanup-data-imports")
                        .with(signedIn("a", "AUDITOR")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRoleCanPostCleanupDataImports() throws Exception {
        // Sanity check: a valid admin POST passes the framework gate and reaches
        // the controller, which redirects back to /admin/tools.
        mockMvc.perform(post("/admin/tools/cleanup-data-imports")
                        .with(signedIn("a", "ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/tools"));
    }

    @Test
    void postCleanupWithoutCsrfRejectedEvenForAdmin() throws Exception {
        // Belt-and-suspenders: even an admin POST is rejected (403) when the CSRF
        // token is missing, matching the pattern enforced on every other UI POST.
        mockMvc.perform(post("/admin/tools/cleanup-data-imports").with(signedIn("a", "ADMIN")))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------
    // RDB watermark write endpoints (reset / set-manual): admin-only, CSRF-protected
    // ---------------------------------------------------------------------
    //
    // These endpoints sit under /admin/data-sources/rdb/{id}/(reset|set)-watermark
    // and inherit the standard /admin/** gating:
    //   - GET on /admin/** — admin OR auditor
    //   - any other method on /admin/** — admin only
    //   - AuditorWriteRejectFilter denies any auditor write that slipped through
    //   - CSRF token required (admin/** is NOT in csrf.ignoringRequestMatchers)
    //
    // AdminDataSourceController is not in this @WebMvcTest's controller list, so
    // a request that passes the security gate reaches Spring MVC's dispatcher and
    // 404s. That's the signal we use to assert "security allowed it through" —
    // anything that does NOT 404 (i.e. 401/403/302) tells us the security gate
    // refused before the controller would have run.

    @Test
    void anonymousCannotPostRdbWatermarkReset() throws Exception {
        final int status = mockMvc.perform(
                        post("/admin/data-sources/rdb/some-id/reset-watermark").with(csrf()))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || (status >= 300 && status < 400),
                "anonymous POST should be 401 or redirect, was " + status);
    }

    @Test
    void anonymousCannotPostRdbWatermarkSet() throws Exception {
        final int status = mockMvc.perform(
                        post("/admin/data-sources/rdb/some-id/set-watermark")
                                .param("watermark", "12345").with(csrf()))
                .andReturn().getResponse().getStatus();
        assertTrue(status == 401 || (status >= 300 && status < 400),
                "anonymous POST should be 401 or redirect, was " + status);
    }

    @Test
    void userRoleForbiddenFromRdbWatermarkWrites() throws Exception {
        // Reviewers / annotators / API-key holders must never reach admin writes —
        // these endpoints can move the cursor on data ingestion, which is an
        // operator concern by design.
        mockMvc.perform(post("/admin/data-sources/rdb/some-id/reset-watermark")
                        .with(signedIn("u", "USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/data-sources/rdb/some-id/set-watermark")
                        .param("watermark", "12345")
                        .with(signedIn("u", "USER")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    /**
     * An approver (#5) is a reviewer with a per-group designation, not a role, so configuration and users stay
     * with administrators; and auditors cannot adjudicate, release, or approve.
     */
    @Test
    void approversStayOutOfConfigurationAndAuditorsCannotActAsApprovers() throws Exception {
        for (String path : List.of("/admin/groups", "/admin/rules", "/admin/general/detection-rules")) {
            mockMvc.perform(post(path).with(signedIn("ann", "USER")).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/review/d1/break-lock").with(signedIn("a", "AUDITOR")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/review/d1/approve").with(signedIn("a", "AUDITOR")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/spans/s1")
                        .with(signedIn("a", "AUDITOR")).with(csrf())
                        .contentType("application/json").content("{\"status\":\"APPROVED\",\"adjudicate\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditorRoleForbiddenFromRdbWatermarkWrites() throws Exception {
        // Auditors can READ admin pages (the watermark value is visible on the
        // data-sources listing), but cannot WRITE to them. Two filters enforce
        // this — the path matcher's other-than-GET rule and AuditorWriteRejectFilter
        // — so even if a refactor removed one, the other would still hold.
        mockMvc.perform(post("/admin/data-sources/rdb/some-id/reset-watermark")
                        .with(signedIn("a", "AUDITOR")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/data-sources/rdb/some-id/set-watermark")
                        .param("watermark", "12345")
                        .with(signedIn("a", "AUDITOR")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminWithoutCsrfRejectedFromRdbWatermarkWrites() throws Exception {
        // CSRF token requirement isn't path-specific — every admin POST needs it.
        // Pinning the explicit test here means a refactor that accidentally adds
        // /admin/data-sources/** to the CSRF ignoring list trips immediately.
        mockMvc.perform(post("/admin/data-sources/rdb/some-id/reset-watermark")
                        .with(signedIn("a", "ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/data-sources/rdb/some-id/set-watermark")
                        .param("watermark", "12345")
                        .with(signedIn("a", "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminWithCsrfPassesGateForRdbWatermarkWrites() throws Exception {
        // Sanity check: a valid admin POST with CSRF passes the security gate.
        // The AdminDataSourceController isn't loaded in this @WebMvcTest, so we
        // get a 404 from the dispatcher — but specifically NOT a 403, which
        // would mean the security gate denied. Anything other than 403 proves
        // the security gate allowed it.
        mockMvc.perform(post("/admin/data-sources/rdb/some-id/reset-watermark")
                        .with(signedIn("a", "ADMIN")).with(csrf()))
                .andExpect(result -> {
                    final int s = result.getResponse().getStatus();
                    assertTrue(s != 403 && s != 401,
                            "admin POST with CSRF must pass the security gate (saw " + s + ")");
                });
        mockMvc.perform(post("/admin/data-sources/rdb/some-id/set-watermark")
                        .param("watermark", "12345")
                        .with(signedIn("a", "ADMIN")).with(csrf()))
                .andExpect(result -> {
                    final int s = result.getResponse().getStatus();
                    assertTrue(s != 403 && s != 401,
                            "admin POST with CSRF must pass the security gate (saw " + s + ")");
                });
    }

    // ---------------------------------------------------------------------
    // Defence-in-depth response headers (finding #8)
    // ---------------------------------------------------------------------

    @Test
    void securityHeadersPresentOnEveryResponse() throws Exception {
        // A regression dropping these headers — or relaxing the CSP — opens the
        // PII UI to XSS / clickjacking / downgrade attacks. We assert against
        // /login (a permitAll endpoint) so the test doesn't need authentication;
        // the .headers(...) chain in SecurityConfig applies to every path.
        //
        // .secure(true) tells MockMvc the request is over TLS, matching the
        // production model (TLS-terminating reverse proxy + forward-headers).
        // Spring's default HSTS writer suppresses the header on plain HTTP.
        mockMvc.perform(get("/login").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("default-src 'self'"),
                                // script-src now uses a per-request nonce + 'strict-dynamic'
                                // (R2-F1). Plain 'self' alone would refuse the inline
                                // <script> blocks in review.html / queue.html and force
                                // operators to add 'unsafe-inline', defeating the policy.
                                org.hamcrest.Matchers.matchesRegex(
                                        "(?s).*script-src 'self' 'nonce-[A-Za-z0-9_-]+' 'strict-dynamic'.*"),
                                org.hamcrest.Matchers.containsString("object-src 'none'"),
                                org.hamcrest.Matchers.containsString("frame-ancestors 'none'"),
                                org.hamcrest.Matchers.containsString("form-action 'self'"),
                                org.hamcrest.Matchers.containsString("base-uri 'self'"))))
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("max-age=31536000"),
                                org.hamcrest.Matchers.containsString("includeSubDomains"),
                                org.hamcrest.Matchers.containsString("preload"))))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("geolocation=()"),
                                org.hamcrest.Matchers.containsString("camera=()"),
                                org.hamcrest.Matchers.containsString("microphone=()"))))
                // Spring defaults we rely on staying in place:
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void securityHeadersPresentOnApiResponsesToo() throws Exception {
        // Confirms the headers chain applies to the API namespace as well —
        // without it, a 401 response body served from /api/v1/** would lack
        // the defence-in-depth headers and any future regression printing
        // request data into the error response would be unprotected.
        mockMvc.perform(get("/api/v1/policies").secure(true))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().exists("Strict-Transport-Security"))
                .andExpect(header().exists("Referrer-Policy"))
                .andExpect(header().exists("Permissions-Policy"));
    }

    // ---------------------------------------------------------------------
    // Swagger / OpenAPI gating (R2-F2)
    // ---------------------------------------------------------------------

    @Test
    void anonymousIsRejectedFromOpenApiAndSwaggerUi() throws Exception {
        // Pre-R2-F2 these were permitAll() — anyone reachable at the URL could
        // enumerate the entire admin endpoint surface and DTO shapes.
        for (String path : new String[]{
                "/v3/api-docs", "/v3/api-docs/swagger-config",
                "/swagger-ui/index.html", "/swagger-ui.html"}) {
            mockMvc.perform(get(path)).andExpect(notOk());
        }
    }

    @Test
    void nonAdminUserIsForbiddenFromOpenApiAndSwaggerUi() throws Exception {
        // ROLE_USER reviewers — even authenticated — must not enumerate the API
        // surface. The OpenAPI doc is admin-only.
        for (String path : new String[]{"/v3/api-docs", "/swagger-ui/index.html"}) {
            mockMvc.perform(get(path).with(signedIn("u", "USER")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void cspNonceVariesPerRequest() throws Exception {
        // R2-F1: a static nonce would let an attacker who captures one response
        // pre-compute an injection payload for any subsequent request. Each
        // request must mint a fresh nonce. We extract the nonce from two
        // back-to-back responses and assert they differ.
        final java.util.regex.Pattern nonceRegex =
                java.util.regex.Pattern.compile("'nonce-([A-Za-z0-9_-]+)'");
        final String cspA = mockMvc.perform(get("/login").secure(true))
                .andReturn().getResponse().getHeader("Content-Security-Policy");
        final String cspB = mockMvc.perform(get("/login").secure(true))
                .andReturn().getResponse().getHeader("Content-Security-Policy");
        org.junit.jupiter.api.Assertions.assertNotNull(cspA, "first CSP must be set");
        org.junit.jupiter.api.Assertions.assertNotNull(cspB, "second CSP must be set");

        final java.util.regex.Matcher mA = nonceRegex.matcher(cspA);
        final java.util.regex.Matcher mB = nonceRegex.matcher(cspB);
        org.junit.jupiter.api.Assertions.assertTrue(mA.find(), "first CSP missing nonce: " + cspA);
        org.junit.jupiter.api.Assertions.assertTrue(mB.find(), "second CSP missing nonce: " + cspB);
        org.junit.jupiter.api.Assertions.assertNotEquals(mA.group(1), mB.group(1),
                "nonces must vary per request — a static value defeats the protection");
        // CSP3 minimum entropy is 128 bits = 22 base64url chars without padding.
        // Allow some slack in either direction in case the encoder leaves padding;
        // strictly we mint 16 bytes so length should be exactly 22.
        org.junit.jupiter.api.Assertions.assertTrue(mA.group(1).length() >= 16,
                "nonce too short to provide 128 bits of entropy: " + mA.group(1));
    }

    @Test
    void cspNonceIsStampedOnScriptTagsInRenderedBody() throws Exception {
        // The CSP says script-src 'self' 'nonce-XXX' 'strict-dynamic'.
        // 'strict-dynamic' causes Chrome to IGNORE 'self', so a <script src="...">
        // without a matching nonce attribute is refused by the browser. This test
        // verifies the rendered HTML actually contains nonce="<csp-nonce>" so
        // tailwind.js (and every other src'd script) loads.
        final org.springframework.test.web.servlet.MvcResult result =
                mockMvc.perform(get("/login").secure(true))
                        .andExpect(status().isOk())
                        .andReturn();
        final String csp = result.getResponse().getHeader("Content-Security-Policy");
        final String body = result.getResponse().getContentAsString();
        final java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("'nonce-([A-Za-z0-9_-]+)'").matcher(csp);
        org.junit.jupiter.api.Assertions.assertTrue(m.find(), "CSP missing nonce");
        final String nonce = m.group(1);
        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("nonce=\"" + nonce + "\""),
                "rendered body missing nonce=\"" + nonce + "\". body excerpt: "
                        + body.substring(0, Math.min(body.length(), 800)));
    }

    // ---------------------------------------------------------------------
    // Admin -> Philter.
    // ---------------------------------------------------------------------

    @Test
    void adminPhilterPageShowsTheUrlButNotTheKey() throws Exception {
        when(philterSettings.url()).thenReturn("https://philter.example.com");
        when(philterSettings.apiKey()).thenReturn("sk_key_marker");

        final String body = mockMvc.perform(get("/admin/philter").with(signedIn("a", "ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("https://philter.example.com"));
        assertFalse(body.contains("sk_key_marker"));
    }

    @Test
    void philterTestRequiresCsrfAndAnAdministrator() throws Exception {
        mockMvc.perform(post("/admin/philter/test").with(signedIn("a", "ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/philter/test").with(signedIn("a", "AUDITOR")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    /**
     * One failed sign-in is one call to Philter. The filter chain has an authentication manager of its own;
     * with the global one as its parent, the same provider ran twice and each failure counted double toward
     * Philter's lockout.
     */
    @Test
    void failedSignInCallsPhilterOnce() throws Exception {
        when(philterSignInService.signIn(eq("someone"), eq("wrong-password"), any(), any()))
                .thenThrow(new ai.philterd.philter.model.exceptions.UnauthorizedException("no"));
        when(philterSignInFlow.refused(eq("someone"), any()))
                .thenReturn(new org.springframework.security.authentication.BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/login").with(csrf())
                        .param("username", "someone")
                        .param("password", "wrong-password"))
                .andExpect(redirectedUrl("/login?error"));

        verify(philterSignInService, times(1)).signIn(eq("someone"), eq("wrong-password"), any(), any());
    }

    /** A sign-in page left open while its session ended goes back to sign-in, not a bare 403. */
    @Test
    void anExpiredSignInFormIsSentBackToSignIn() throws Exception {
        final org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        mockMvc.perform(post("/login").session(session).with(csrf().useInvalidToken())
                        .with(r -> { r.setRequestedSessionId(session.getId()); return r; })
                        .param("username", "someone").param("password", "a-password-0000"))
                .andExpect(redirectedUrl("/login?expired"));
        // No session cookie at all: the browser did not keep it.
        mockMvc.perform(post("/login").param("username", "someone").param("password", "a-password-0000"))
                .andExpect(redirectedUrl("/login?cookie"));
        verify(philterSignInService, times(0)).signIn(any(), any(), any(), any());

        // Someone signed in with a bad token is refused as before.
        mockMvc.perform(post("/settings").with(signedIn("reviewer", "USER")).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
    }

    /** A session left from Philter Workspace's removed local sign-in is ended, not served. */
    @Test
    void sessionFromLocalSignInIsEnded() throws Exception {
        final org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        final org.springframework.security.core.context.SecurityContext context =
                org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin@example.com", null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository
                .SPRING_SECURITY_CONTEXT_KEY, context);

        mockMvc.perform(get("/admin/general").accept("text/html").session(session))
                .andExpect(redirectedUrl("/login?expired"));
        assertTrue(session.isInvalid());
    }
}
