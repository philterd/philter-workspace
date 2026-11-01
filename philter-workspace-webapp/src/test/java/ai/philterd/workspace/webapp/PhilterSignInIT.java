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

import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.service.SymmetricCipher;
import ai.philterd.workspace.webapp.security.PhilterSessionAuthentication;
import ai.philterd.philter.PhilterClient;
import ai.philterd.philter.model.CreateUserRequest;
import ai.philterd.philter.model.MfaEnrollment;
import ai.philterd.philter.model.SignInResponse;
import ai.philterd.philter.model.UpdateAdminSettingsRequest;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-in through Philter, end to end: the real Philter Workspace (SQLite, in-memory sessions) against a
 * {@code philterd/philter:4.0.0} container with password sign-in enabled. Needs Docker; runs in
 * {@code mvn verify}.
 *
 * <p>Philter runs with a one-minute idle timeout for session keys and Philter Workspace checks the key on every
 * request, so expiry can be seen in about a minute. Each test signs in from its own address, which
 * Philter believes since every address is a trusted proxy here, so Philter's per-address rate limit is
 * not shared between tests.</p>
 */
// Set here rather than in @DynamicPropertySource, which is applied after Philter Workspace's store post-processor
// reads philter-workspace.store. Spring Session is left out, so sessions are in memory and no Valkey is needed.
// On a real port, so Philter can deliver its webhook to Philter Workspace.
@SpringBootTest(classes = PhilterWorkspaceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "philter-workspace.sign-in.check-interval-seconds=0",
        "philter-workspace.store=sqlite",
        "philter-workspace.demo-data.enabled=false",
        "philter-workspace.delivery.poll-millis=500",
        "philter-workspace.data-sources.allowed-hosts=localhost,127.0.0.1",
        "spring.autoconfigure.exclude=org.springframework.boot.session.autoconfigure.SessionAutoConfiguration,"
                + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PhilterSignInIT {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BOOTSTRAP_KEY = "sk_" + HexFormat.of().formatHex(bytes(16));
    private static final String ADMIN_PASSWORD = "admin-" + HexFormat.of().formatHex(bytes(12));
    private static final AtomicInteger ADDRESSES = new AtomicInteger(1);

    static final GenericContainer<?> PHILTER = new GenericContainer<>("philterd/philter:4.0.0")
            .withExposedPorts(8080)
            .withEnv("PHILTER_ENCRYPTION_KEY", Base64.getEncoder().encodeToString(bytes(32)))
            .withEnv("PHILTER_BOOTSTRAP_API_KEY", BOOTSTRAP_KEY)
            .withEnv("PASSWORD_SIGN_IN_ENABLED", "true")
            .withEnv("SSL_ENABLED", "false")
            .withEnv("SESSION_KEY_IDLE_TIMEOUT_MINUTES", "1")
            .withEnv("TRUSTED_PROXIES", "0.0.0.0/0,::/0")
            .waitingFor(Wait.forHttp("/api/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    /** The port Philter Workspace listens on, which the Philter container reaches as host.testcontainers.internal. */
    static final int PORT = freePort();
    static final String WEBHOOK_SECRET = "webhook-" + HexFormat.of().formatHex(bytes(16));

    static {
        org.testcontainers.Testcontainers.exposeHostPorts(PORT);
        PHILTER.start();
        awaitBootstrapKey();
    }

    /**
     * Philter answers /api/health before it has seeded the bootstrap key: it seeds the admin user, its
     * managed policies, and then the key once the application is ready, after the web server is up. So
     * wait until the key is accepted, or the first call with it can fail as unauthorized.
     */
    private static void awaitBootstrapKey() {
        final long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (true) {
            try {
                bootstrap().getLimits();
                return;
            } catch (Exception e) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Philter did not accept its bootstrap API key within two minutes", e);
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ai.philterd.workspace.repository.RedactionCertificateRepository certificateRepository;
    @Autowired private ai.philterd.workspace.service.AuditLogQueryService auditLogQueryService;
    @Autowired private SymmetricCipher cipher;
    @Autowired private ai.philterd.workspace.repository.UserRepository userRepository;
    @Autowired private ai.philterd.workspace.service.RedactionService redactionService;
    @Autowired private ai.philterd.workspace.repository.BatchRepository batchRepository;
    @Autowired private ai.philterd.workspace.repository.DocumentRepository documentRepository;
    @Autowired private ai.philterd.workspace.service.IngestQueueService ingestQueueService;
    @Autowired private ai.philterd.workspace.repository.PendingUploadRepository pendingUploadRepository;
    @Autowired private ai.philterd.workspace.repository.SpanRepository spanRepository;
    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private ai.philterd.workspace.repository.PhilterWebhookDeliveryRepository webhookDeliveries;
    @Autowired private ai.philterd.workspace.service.DetectionRuleService detectionRuleService;
    @Autowired private ai.philterd.workspace.service.GeneralSettingsService generalSettingsService;
    @Autowired private ai.philterd.workspace.service.DocumentSearchService documentSearchService;
    @Autowired private ai.philterd.workspace.service.SearchIndexRebuildService searchIndexRebuildService;
    @Autowired private ai.philterd.workspace.repository.LocalDirectoryDestinationRepository localDestinations;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) throws Exception {
        registry.add("philter-workspace.philter.url", PhilterSignInIT::philterUrl);
        // Check API keys with Philter on every call, so a revocation applies to the next one.
        registry.add("philter-workspace.api-keys.cache-seconds", () -> "0");
        // An administrator's key, so Admin → Philter's check has something to flag.
        registry.add("philter-workspace.philter.api-key", () -> BOOTSTRAP_KEY);
        final String database = Files.createTempDirectory("philter-workspace-it").resolve("philter-workspace.db").toString();
        registry.add("philter-workspace.sqlite.path", () -> database);
        registry.add("server.port", () -> PORT);
        registry.add("philter-workspace.philter.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("philter-workspace.philter.webhook-url",
                () -> "http://host.testcontainers.internal:" + PORT + "/api/philter/webhook");
    }

    @BeforeAll
    static void setUpPhilter() throws Exception {
        bootstrap().setPassword("admin", ADMIN_PASSWORD);
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(final int n) {
        final byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String philterUrl() {
        return "http://" + PHILTER.getHost() + ":" + PHILTER.getMappedPort(8080);
    }

    private static PhilterClient bootstrap() {
        return PhilterClients.build(philterUrl(), BOOTSTRAP_KEY, (javax.net.ssl.SSLContext) null);
    }

    private static String password() {
        return "pw-" + HexFormat.of().formatHex(bytes(12));
    }

    /** A user created in Philter with a password they must change, as an administrator creates one. */
    private static String createUser(final String prefix, final String password) throws Exception {
        final String username = prefix + "-" + HexFormat.of().formatHex(bytes(3));
        final CreateUserRequest request = new CreateUserRequest();
        request.setUsername(username);
        request.setRole("user");
        request.setPassword(password);
        bootstrap().createUser(request);
        return username;
    }

    /** A user who has already chosen their own password. */
    private static String createSignedUpUser(final String prefix, final String password) throws Exception {
        final String first = password();
        final String username = createUser(prefix, first);
        final SignInResponse limited = bootstrap().signIn(username, first);
        PhilterClients.build(philterUrl(), limited.getApiKey(), (javax.net.ssl.SSLContext) null)
                .changePassword(first, password);
        return username;
    }

    /** Each test signs in from its own address, so Philter's per-address rate limit is its own. */
    private static RequestPostProcessor from(final String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String newAddress() {
        return "10.20." + (ADDRESSES.get() / 250) + "." + (ADDRESSES.getAndIncrement() % 250 + 1);
    }

    private MvcResult signIn(final MockHttpSession session, final String address, final String username,
                             final String password) throws Exception {
        return mockMvc.perform(post("/login").session(session).with(csrf()).with(from(address))
                .param("username", username).param("password", password)).andReturn();
    }

    private String encryptedKey(final MockHttpSession session) {
        final SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertNotNull(context, "signed in");
        return ((PhilterSessionAuthentication) context.getAuthentication()).getEncryptedSessionKey();
    }

    private static String code(final String secret, final long step) throws Exception {
        return new DefaultCodeGenerator().generate(secret, step);
    }

    private static long currentStep() {
        return Instant.now().getEpochSecond() / 30;
    }

    /** Waits for the next 30-second step, since Philter accepts each code once. */
    private static long nextStep(final long used) throws InterruptedException {
        while (currentStep() <= used) {
            Thread.sleep(1000);
        }
        return currentStep();
    }

    @Test
    void signInKeepsTheKeyOnTheServerAndSignOutRevokesIt(final CapturedOutput output) throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        final MvcResult result = signIn(session, address, "admin", ADMIN_PASSWORD);
        assertEquals("/", result.getResponse().getRedirectedUrl());
        final String sessionKey = cipher.decrypt(encryptedKey(session));
        assertFalse(result.getResponse().getContentAsString().contains(sessionKey));
        assertTrue(result.getResponse().getCookies().length == 0
                || java.util.Arrays.stream(result.getResponse().getCookies()).noneMatch(c -> c.getValue().contains(sessionKey)));
        mockMvc.perform(get("/admin/users").session(session).with(from(address))).andExpect(status().isOk());

        mockMvc.perform(post("/logout").session(session).with(csrf()).with(from(address)))
                .andExpect(redirectedUrl("/login?logout"));

        assertThrows(UnauthorizedException.class,
                () -> PhilterClients.build(philterUrl(), sessionKey, (javax.net.ssl.SSLContext) null).getCurrentUser());
        assertFalse(output.getAll().contains(ADMIN_PASSWORD), "the password is never logged");
        assertFalse(output.getAll().contains(sessionKey), "the session key is never logged");
    }

    @Test
    void wrongPasswordAndUnknownUserAreTheSameAndLockAlike(final CapturedOutput output) throws Exception {
        final String password = password();
        final String user = createSignedUpUser("lock", password);
        final String address = newAddress();
        assertEquals("/login?error", signIn(new MockHttpSession(), address, user, "wrong-" + password)
                .getResponse().getRedirectedUrl());
        assertEquals("/login?error", signIn(new MockHttpSession(), address, "nobody-" + user, password)
                .getResponse().getRedirectedUrl());

        for (int i = 0; i < 4; i++) {
            signIn(new MockHttpSession(), address, user, "wrong-" + password);
            signIn(new MockHttpSession(), address, "nobody-" + user, password);
        }
        assertEquals("/login?locked", signIn(new MockHttpSession(), address, user, password)
                .getResponse().getRedirectedUrl(), "locked even with the right password");
        assertEquals("/login?locked", signIn(new MockHttpSession(), address, "nobody-" + user, password)
                .getResponse().getRedirectedUrl());
        assertFalse(output.getAll().contains("wrong-" + password), "passwords are never logged");
    }

    @Test
    void forcedPasswordChangeComesBeforeAnyOtherPage() throws Exception {
        final String first = password();
        final String user = createUser("change", first);
        final String address = newAddress();
        final MockHttpSession session = new MockHttpSession();

        assertEquals("/sign-in/password", signIn(session, address, user, first).getResponse().getRedirectedUrl());
        mockMvc.perform(get("/settings").session(session).with(from(address)).accept("text/html"))
                .andExpect(redirectedUrl("/login"));

        final String chosen = password();
        mockMvc.perform(post("/sign-in/password").session(session).with(csrf()).with(from(address))
                        .param("currentPassword", first).param("newPassword", chosen).param("confirmPassword", chosen))
                .andExpect(redirectedUrl("/login?passwordChanged"));

        assertEquals("/", signIn(new MockHttpSession(), address, user, chosen).getResponse().getRedirectedUrl());
    }

    @Test
    void mfaEnrollmentAndSignInWithACode() throws Exception {
        final String password = password();
        final String user = createSignedUpUser("mfa", password);
        final String address = newAddress();
        final UpdateAdminSettingsRequest required = new UpdateAdminSettingsRequest();
        required.setMfaAvailable(true);
        required.setMfaRequired(true);
        bootstrap().updateAdminSettings(required);
        try {
            final MockHttpSession session = new MockHttpSession();
            assertEquals("/sign-in/mfa-enrollment",
                    signIn(session, address, user, password).getResponse().getRedirectedUrl());
            final String page = mockMvc.perform(post("/sign-in/mfa-enrollment/start").session(session).with(csrf())
                            .with(from(address)).param("currentPassword", password))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            final java.util.regex.Matcher secret = java.util.regex.Pattern
                    .compile("<span class=\"font-mono\">([A-Z2-7]+)</span>").matcher(page);
            assertTrue(secret.find(), "the page shows the key to enter");
            final long enrolledAt = currentStep();
            mockMvc.perform(post("/sign-in/mfa-enrollment/confirm").session(session).with(csrf()).with(from(address))
                            .param("code", code(secret.group(1), enrolledAt)))
                    .andExpect(redirectedUrl("/login?mfaEnrolled"));

            final MockHttpSession wrong = new MockHttpSession();
            assertEquals("/sign-in/mfa", signIn(wrong, address, user, password).getResponse().getRedirectedUrl());
            mockMvc.perform(post("/sign-in/mfa").session(wrong).with(csrf()).with(from(address)).param("code", "000000"))
                    .andExpect(redirectedUrl("/login?code"));

            final MockHttpSession right = new MockHttpSession();
            assertEquals("/sign-in/mfa", signIn(right, address, user, password).getResponse().getRedirectedUrl());
            final long step = nextStep(enrolledAt);
            mockMvc.perform(post("/sign-in/mfa").session(right).with(csrf()).with(from(address))
                            .param("code", code(secret.group(1), step)))
                    .andExpect(redirectedUrl("/"));
            mockMvc.perform(get("/settings").session(right).with(from(address))).andExpect(status().isOk());
        } finally {
            final UpdateAdminSettingsRequest off = new UpdateAdminSettingsRequest();
            off.setMfaRequired(false);
            bootstrap().updateAdminSettings(off);
        }
    }

    @Test
    void aPersonManagesTheirOwnMfaKeysAndWebhookFromSettings() throws Exception {
        // A long-lived key, made before the user first signs in, since Philter lets an administrator make one
        // only for a user who has never had a key.
        final String first = password();
        final String password = password();
        final String user = createUser("account", first);
        final String prefix = bootstrap().createApiKey(user, java.util.List.of("redact", "lists:read")).getApiKey()
                .substring(0, 12) + "...";
        final SignInResponse limited = bootstrap().signIn(user, first);
        PhilterClients.build(philterUrl(), limited.getApiKey(), (javax.net.ssl.SSLContext) null)
                .changePassword(first, password);
        final UpdateAdminSettingsRequest available = new UpdateAdminSettingsRequest();
        available.setMfaAvailable(true);
        bootstrap().updateAdminSettings(available);
        final String address = newAddress();
        MockHttpSession session = new MockHttpSession();
        assertEquals("/", signIn(session, address, user, password).getResponse().getRedirectedUrl());

        final String settings = mockMvc.perform(get("/settings").session(session).with(from(address)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(settings.contains(">" + user + "<") && settings.contains(">user<"), settings);
        assertTrue(settings.contains("href=\"/settings/mfa\""), "MFA can be set up");

        // API keys: listed, narrowed, and revoked.
        final String keys = mockMvc.perform(get("/settings/api-keys").session(session).with(from(address)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(keys.contains(prefix) && keys.contains("redact, lists:read"), keys);
        final java.util.regex.Matcher id = java.util.regex.Pattern.compile("/settings/api-keys/scopes\\?id=([^\"&]+)")
                .matcher(keys);
        assertTrue(id.find(), "a Scopes link");
        mockMvc.perform(post("/settings/api-keys/scopes").session(session).with(csrf()).with(from(address))
                        .param("id", id.group(1)).param("scopes", "redact"))
                .andExpect(redirectedUrl("/settings/api-keys"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("success", "Scopes of " + prefix + " changed."));
        assertTrue(mockMvc.perform(get("/settings/api-keys").session(session).with(from(address))).andReturn()
                .getResponse().getContentAsString().contains(">redact<"), "narrowed in Philter");
        mockMvc.perform(post("/settings/api-keys/revoke").session(session).with(csrf()).with(from(address))
                        .param("id", id.group(1)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("success", "Key revoked."));
        assertFalse(mockMvc.perform(get("/settings/api-keys").session(session).with(from(address))).andReturn()
                .getResponse().getContentAsString().contains(prefix), "revoked in Philter");

        // Webhook: a loopback URL is refused by Philter's default allowlist; a public one is set and tested.
        mockMvc.perform(post("/settings/webhook").session(session).with(csrf()).with(from(address))
                        .param("url", "https://127.0.0.1/hook").param("secret", "s".repeat(24)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("error", org.hamcrest.Matchers.containsString("allowlist")));
        mockMvc.perform(post("/settings/webhook").session(session).with(csrf()).with(from(address))
                        .param("url", "https://hooks.example.com/philter").param("secret", "s".repeat(24)))
                .andExpect(redirectedUrl("/settings/webhook"));
        final String webhook = mockMvc.perform(get("/settings/webhook").session(session).with(from(address)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(webhook.contains("value=\"https://hooks.example.com/philter\"")
                && webhook.contains("A webhook is set, with a secret."), webhook);
        // A new URL with the secret left blank keeps the secret.
        mockMvc.perform(post("/settings/webhook").session(session).with(csrf()).with(from(address))
                        .param("url", "https://hooks.example.com/other").param("secretSet", "true"))
                .andExpect(redirectedUrl("/settings/webhook"));
        final String moved = mockMvc.perform(get("/settings/webhook").session(session).with(from(address)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(moved.contains("value=\"https://hooks.example.com/other\"")
                && moved.contains("A webhook is set, with a secret."), moved);
        final String tested = String.valueOf(mockMvc.perform(post("/settings/webhook/test").session(session)
                .with(csrf()).with(from(address))).andReturn().getFlashMap().values());
        assertTrue(tested.contains("The test event was"), tested);
        mockMvc.perform(post("/settings/webhook/remove").session(session).with(csrf()).with(from(address)))
                .andExpect(redirectedUrl("/settings/webhook"));
        assertTrue(mockMvc.perform(get("/settings/webhook").session(session).with(from(address))).andReturn()
                .getResponse().getContentAsString().contains("No webhook is set."), "removed");

        // MFA: set up with the password and a code, which ends the session; then removed with a code.
        final String page = mockMvc.perform(post("/settings/mfa/start").session(session).with(csrf())
                        .with(from(address)).param("currentPassword", password))
                .andReturn().getResponse().getContentAsString();
        final java.util.regex.Matcher secret = java.util.regex.Pattern
                .compile("<span class=\"font-mono\">([A-Z2-7]+)</span>").matcher(page);
        assertTrue(secret.find(), page);
        final long enrolledAt = currentStep();
        mockMvc.perform(post("/settings/mfa/confirm").session(session).with(csrf()).with(from(address))
                        .param("code", code(secret.group(1), enrolledAt)))
                .andExpect(redirectedUrl("/login?mfaEnrolled"));
        assertTrue(bootstrap().getUser(user).isMfaEnabled());

        session = new MockHttpSession();
        assertEquals("/sign-in/mfa", signIn(session, address, user, password).getResponse().getRedirectedUrl());
        final long signedInAt = nextStep(enrolledAt);
        mockMvc.perform(post("/sign-in/mfa").session(session).with(csrf()).with(from(address))
                .param("code", code(secret.group(1), signedInAt))).andExpect(redirectedUrl("/"));
        assertTrue(mockMvc.perform(get("/settings").session(session).with(from(address))).andReturn().getResponse()
                .getContentAsString().contains("action=\"/settings/mfa/remove\""));
        mockMvc.perform(post("/settings/mfa/remove").session(session).with(csrf()).with(from(address))
                        .param("code", code(secret.group(1), nextStep(signedInAt))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("success", "Multi-factor authentication removed."));
        assertFalse(bootstrap().getUser(user).isMfaEnabled());
    }

    @Test
    void legalHoldsArePlacedAndReleasedThroughPhilter() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        assertEquals("/", signIn(session, address, "admin", ADMIN_PASSWORD).getResponse().getRedirectedUrl());
        final String reference = "it-hold-" + HexFormat.of().formatHex(bytes(3));

        mockMvc.perform(post("/admin/holds/new").session(session).with(csrf()).with(from(address))
                        .param("reference", reference).param("scope", "document_chain")
                        .param("documentId", "it-document-1").param("reason", "Matter 2026-17"))
                .andExpect(redirectedUrl("/admin/holds"));
        final ai.philterd.philter.model.LegalHoldResponse placed = bootstrap().getHold(reference);
        assertEquals("document_chain", placed.getScopeType());
        assertEquals("it-document-1", placed.getScopeValue());
        assertEquals("Matter 2026-17", placed.getReason());

        final String listed = mockMvc.perform(get("/admin/holds").param("q", reference).session(session)
                .with(from(address))).andReturn().getResponse().getContentAsString();
        assertTrue(listed.contains(">" + reference + "<") && listed.contains(">it-document-1<")
                && listed.contains(">Matter 2026-17<"), listed);
        final String again = mockMvc.perform(post("/admin/holds/new").session(session).with(csrf())
                        .with(from(address)).param("reference", reference).param("scope", "user"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(again.contains("A hold with that reference already exists."), again);

        // A user hold names its owner, which Philter fills in.
        final String userHold = reference + "-user";
        mockMvc.perform(post("/admin/holds/new").session(session).with(csrf()).with(from(address))
                        .param("reference", userHold).param("scope", "user"))
                .andExpect(redirectedUrl("/admin/holds"));
        assertEquals("admin", bootstrap().getHold(userHold).getScopeValue());

        final String other = createUser("holds", password());
        assertTrue(mockMvc.perform(get("/admin/holds").param("owner", other).session(session).with(from(address)))
                .andReturn().getResponse().getContentAsString().contains("ADMIN_CROSS_USER_ACCESS_ENABLED=true"));

        for (final String each : java.util.List.of(reference, userHold)) {
            mockMvc.perform(post("/admin/holds/release").session(session).with(csrf()).with(from(address))
                            .param("reference", each))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                            .attribute("success", "Hold \"" + each + "\" released."));
        }
        assertTrue(bootstrap().listHolds(null, null).getItems().stream()
                .noneMatch(h -> h.getReference().startsWith(reference)), "released in Philter");
    }

    @Test
    void ledgerChainsAreBrowsedVerifiedAndExportedThroughPhilter() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        assertEquals("/", signIn(session, address, "admin", ADMIN_PASSWORD).getResponse().getRedirectedUrl());
        final String suffix = HexFormat.of().formatHex(bytes(3));
        final String context = "it-ledger-" + suffix;
        final String policy = "it-ledger-policy-" + suffix;
        final String filename = "it-ledger-" + suffix + ".txt";
        bootstrap().createContext(context, false, "document", true, null);
        bootstrap().savePolicy(policy, "{\"identifiers\":{\"emailAddress\":{\"emailAddressFilterStrategies\":"
                + "[{\"strategy\":\"REDACT\"}]}}}", "admin");
        bootstrap().filter(context, policy, filename, "Write to jdoe@example.com about the claim.");

        final String listed = mockMvc.perform(get("/admin/ledgers").param("q", filename).session(session)
                .with(from(address))).andReturn().getResponse().getContentAsString();
        assertTrue(listed.contains(">" + filename + "<"), listed);
        final java.util.regex.Matcher document = java.util.regex.Pattern
                .compile("/admin/ledgers/view\\?document=([^\"&]+)").matcher(listed);
        assertTrue(document.find(), "a View link");
        final String documentId = java.net.URLDecoder.decode(document.group(1), java.nio.charset.StandardCharsets.UTF_8);

        // Philter signs every entry, so a chain it just wrote verifies, and its entries carry no original value.
        final String view = mockMvc.perform(get("/admin/ledgers/view").param("document", documentId).session(session)
                .with(from(address))).andReturn().getResponse().getContentAsString();
        assertTrue(view.contains("The chain verifies.") && view.contains(">Start of chain<")
                && view.contains(">email-address<"), view);
        assertFalse(view.contains("jdoe@example.com"), "the page shows no original value");

        final MvcResult exported = mockMvc.perform(post("/admin/ledgers/export").session(session).with(csrf())
                .with(from(address)).param("document", documentId)).andExpect(status().isOk()).andReturn();
        final ai.philterd.philter.model.LedgerExport export = new com.google.gson.Gson().fromJson(
                exported.getResponse().getContentAsString(), ai.philterd.philter.model.LedgerExport.class);
        assertEquals(documentId, export.getDocumentId());
        assertTrue(export.getEntries().stream().anyMatch(e -> "jdoe@example.com".equals(e.getToken())),
                "the export carries the original values, so it can be verified offline");
        assertFalse(export.getSigningKeys().isEmpty(), "and the keys to check the signatures");

        // This Philter does not set LEDGER_DELETION_ENABLED, so it refuses the delete, and the page says why.
        mockMvc.perform(post("/admin/ledgers/delete").session(session).with(csrf()).with(from(address))
                        .param("document", documentId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("error", org.hamcrest.Matchers.containsString("LEDGER_DELETION_ENABLED=true")));
    }

    @Test
    void philtersSettingsAndAuditLogAreManagedAsTheAdministrator() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        assertEquals("/", signIn(session, address, "admin", ADMIN_PASSWORD).getResponse().getRedirectedUrl());
        final ai.philterd.philter.model.AdminSettings before = bootstrap().getAdminSettings();

        final String page = mockMvc.perform(get("/admin/philter/settings").session(session).with(from(address)))
                .andReturn().getResponse().getContentAsString();
        final String fingerprint = bootstrap().getSigningKeyDetails().getFingerprint();
        assertTrue(page.contains(">" + fingerprint + "<"), "the signing key's fingerprint: " + page);
        try {
            mockMvc.perform(post("/admin/philter/settings").session(session).with(csrf()).with(from(address))
                            .param("originalMfaAvailable", hidden(page, "originalMfaAvailable"))
                            .param("mfaAvailable", hidden(page, "originalMfaAvailable"))
                            .param("originalMfaRequired", hidden(page, "originalMfaRequired"))
                            .param("mfaRequired", hidden(page, "originalMfaRequired"))
                            .param("originalSigningEnabled", hidden(page, "originalSigningEnabled"))
                            .param("signingEnabled", hidden(page, "originalSigningEnabled"))
                            .param("originalDiffuseCountsEnabled", hidden(page, "originalDiffuseCountsEnabled"))
                            .param("diffuseCountsEnabled", hidden(page, "originalDiffuseCountsEnabled"))
                            .param("originalWebhookAllowlist", hidden(page, "originalWebhookAllowlist"))
                            .param("webhookAllowlist", "hooks.example.com\n10.20.0.0/16"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                            .attribute("success", "Philter's settings saved."));
            assertEquals("hooks.example.com,10.20.0.0/16", bootstrap().getAdminSettings().getWebhookAllowlist());

            // Philter's audit log names admin for the change, and the CSV export holds it.
            final String audit = mockMvc.perform(get("/admin/audit/philter").param("event", "settings_updated")
                    .param("user", "admin").session(session).with(from(address))).andReturn().getResponse()
                    .getContentAsString();
            assertTrue(audit.contains(">settings_updated<") && audit.contains(">admin<"), audit);
            final String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
            final MvcResult csv = mockMvc.perform(get("/admin/audit/philter/export").param("from", today)
                    .param("to", today).session(session).with(from(address))).andExpect(status().isOk()).andReturn();
            final String body = csv.getResponse().getContentAsString();
            assertTrue(body.contains("settings_updated"), body);
            assertEquals(1, body.lines().filter(l -> l.equals(body.lines().findFirst().orElseThrow())).count(),
                    "one header");
        } finally {
            final ai.philterd.philter.model.UpdateAdminSettingsRequest restore =
                    new ai.philterd.philter.model.UpdateAdminSettingsRequest();
            restore.setWebhookAllowlist(before.getWebhookAllowlist() == null ? "" : before.getWebhookAllowlist());
            bootstrap().updateAdminSettings(restore);
        }
    }

    @Test
    void aPolicyIsTriedOnTextAndAPdfWithoutABatch() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        assertEquals("/", signIn(session, address, "admin", ADMIN_PASSWORD).getResponse().getRedirectedUrl());
        final long batches = batchRepository.count();
        final long documents = documentRepository.count();

        final String text = mockMvc.perform(post("/try/text").session(session).with(csrf()).with(from(address))
                        .param("policy", "default").param("text", "Write to jdoe@example.com about the claim."))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        final String redacted = text.substring(text.indexOf("id=\"redacted\""));
        assertFalse(redacted.contains("jdoe@example.com"), redacted);
        assertTrue(redacted.contains("about the claim."), redacted);

        final java.io.ByteArrayOutputStream pdf = new java.io.ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            final org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream content =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Write to jdoe@example.com about the claim.");
                content.endText();
            }
            document.save(pdf);
        }
        final MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/try/pdf").file(new org.springframework.mock.web.MockMultipartFile("file", "claim.pdf",
                                "application/pdf", pdf.toByteArray()))
                        .param("policy", "default").session(session).with(csrf()).with(from(address)))
                .andExpect(status().isOk()).andReturn();
        final byte[] out = result.getResponse().getContentAsByteArray();
        assertEquals("attachment; filename=\"claim-redacted.pdf\"", result.getResponse().getHeader("Content-Disposition"));
        try (org.apache.pdfbox.pdmodel.PDDocument redactedPdf = org.apache.pdfbox.Loader.loadPDF(out)) {
            final String extracted = new org.apache.pdfbox.text.PDFTextStripper().getText(redactedPdf);
            assertFalse(extracted.contains("jdoe@example.com"), "the address is not in the redacted PDF: " + extracted);
        }

        assertEquals(batches, batchRepository.count(), "no batch");
        assertEquals(documents, documentRepository.count(), "no document");
        assertTrue(mockMvc.perform(get("/policies/models").session(session).with(from(address))).andReturn()
                .getResponse().getContentAsString().contains("Models"));
    }

    @Test
    void revocationInPhilterEndsTheWorkspaceSession() throws Exception {
        final String password = password();
        final String user = createSignedUpUser("revoke", password);
        final String address = newAddress();
        final MockHttpSession session = new MockHttpSession();
        assertEquals("/", signIn(session, address, user, password).getResponse().getRedirectedUrl());
        mockMvc.perform(get("/settings").session(session).with(from(address))).andExpect(status().isOk());

        bootstrap().revokeSessionKeys(user);

        mockMvc.perform(get("/settings").session(session).with(from(address)))
                .andExpect(redirectedUrl("/login?expired"));
        assertTrue(session.isInvalid());
    }

    @Test
    void expiryInPhilterEndsTheWorkspaceSession() throws Exception {
        final String password = password();
        final String user = createSignedUpUser("expire", password);
        final String address = newAddress();
        final MockHttpSession session = new MockHttpSession();
        assertEquals("/", signIn(session, address, user, password).getResponse().getRedirectedUrl());

        // Philter's idle timeout here is one minute; Philter Workspace checks on every request.
        Thread.sleep(Duration.ofSeconds(65).toMillis());

        mockMvc.perform(get("/settings").session(session).with(from(address)))
                .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void administratorManagesUsersThroughPhilter() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        assertEquals("/", signIn(session, address, "admin", ADMIN_PASSWORD).getResponse().getRedirectedUrl());
        final String username = "added-" + HexFormat.of().formatHex(bytes(3));

        mockMvc.perform(post("/admin/users").session(session).with(csrf()).with(from(address))
                        .param("username", username).param("password", password()))
                .andExpect(redirectedUrl("/admin/users"));
        assertTrue(bootstrap().getUser(username).isPasswordChangeRequired());

        mockMvc.perform(post("/admin/users/" + username + "/role").session(session).with(csrf()).with(from(address))
                .param("role", "admin")).andExpect(redirectedUrl("/admin/users"));
        assertEquals("admin", bootstrap().getUser(username).getRole());

        mockMvc.perform(post("/admin/users/" + username + "/deactivate").session(session).with(csrf())
                .with(from(address))).andExpect(redirectedUrl("/admin/users"));
        assertFalse(bootstrap().getUser(username).isActive());

        // Philter searches, filters, and sorts the page: the new user is a deactivated administrator.
        final String found = mockMvc.perform(get("/admin/users").session(session).with(from(address))
                        .param("q", username).param("role", "admin").param("active", "false")
                        .param("sort", "username").param("order", "desc"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(found.contains("1 to 1 of 1") && found.contains(">" + username + "<"), found);
        final String none = mockMvc.perform(get("/admin/users").session(session).with(from(address))
                        .param("q", username).param("active", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(none.contains("Nothing matches the search and filters."), none);
        mockMvc.perform(get("/admin/users/records").session(session).with(from(address)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/users/me/sign-out").session(session).with(csrf()).with(from(address)))
                .andExpect(redirectedUrl("/admin/users"));
        mockMvc.perform(get("/admin/users").session(session).with(from(address)))
                .andExpect(status().isOk());
    }

    /** A Philter user with no password, as an automation account is, and one key with {@code scopes}. */
    private static ai.philterd.philter.model.CreatedApiKeyResponse apiOnlyUserWithKey(final String prefix,
                                                                                    final java.util.List<String> scopes)
            throws Exception {
        final String username = prefix + "-" + HexFormat.of().formatHex(bytes(3));
        final CreateUserRequest request = new CreateUserRequest();
        request.setUsername(username);
        request.setRole("user");
        bootstrap().createUser(request);
        return bootstrap().createApiKey(username, scopes);
    }

    @Test
    void philterApiKeyWithUsersReadCallsTheApiAsItsUser() throws Exception {
        final ai.philterd.philter.model.CreatedApiKeyResponse key =
                apiOnlyUserWithKey("ci", java.util.List.of("users:read"));

        mockMvc.perform(get("/api/v1/batches").header("Authorization", "Bearer " + key.getApiKey()))
                .andExpect(status().isOk());
        final ai.philterd.workspace.model.User record = userRepository.findByEmail(key.getUsername()).orElseThrow();
        assertEquals(java.util.Set.of("USER"), record.getRoles());
    }

    @Test
    void revokedPhilterApiKeyIsRefused() throws Exception {
        final ai.philterd.philter.model.CreatedApiKeyResponse key =
                apiOnlyUserWithKey("revoked", java.util.List.of("users:read"));
        mockMvc.perform(get("/api/v1/batches").header("Authorization", "Bearer " + key.getApiKey()))
                .andExpect(status().isOk());

        bootstrap().revokeApiKey(key.getId());

        mockMvc.perform(get("/api/v1/batches").header("Authorization", "Bearer " + key.getApiKey()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void philterApiKeyWithoutUsersReadIsRefused() throws Exception {
        final ai.philterd.philter.model.CreatedApiKeyResponse key =
                apiOnlyUserWithKey("no-scope", java.util.List.of("redact"));

        mockMvc.perform(get("/api/v1/batches").header("Authorization", "Bearer " + key.getApiKey()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/batches").header("Authorization", "Bearer sk_not_a_key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theLoginPageAcceptsPhilter4AndAdministratorsAreWarnedOfTheActiveBootstrapKey() throws Exception {
        final String login = mockMvc.perform(get("/login")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(login.contains("name=\"username\"") && !login.contains("does not support"), "the form is shown");

        final MockHttpSession admin = new MockHttpSession();
        final String adminAddress = newAddress();
        assertEquals("/", signIn(admin, adminAddress, "admin", ADMIN_PASSWORD).getResponse().getRedirectedUrl());
        final String dashboard = mockMvc.perform(get("/").session(admin).with(from(adminAddress)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(dashboard.contains("bootstrap API key"), "the bootstrap key is still active in this Philter");

        final String password = password();
        final String username = createSignedUpUser("reviewer", password);
        final MockHttpSession user = new MockHttpSession();
        final String userAddress = newAddress();
        assertEquals("/", signIn(user, userAddress, username, password).getResponse().getRedirectedUrl());
        final String userDashboard = mockMvc.perform(get("/").session(user).with(from(userAddress)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(userDashboard.contains("bootstrap API key"), "only administrators are warned");
    }

    /** The value of a hidden field in a page, as the browser would post it. */
    private static String hidden(final String html, final String name) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "name=\"" + name + "\" value=\"([^\"]*)\"").matcher(html);
        assertTrue(m.find(), name + " in " + html);
        return m.group(1).replace("&quot;", "\"").replace("&amp;", "&");
    }

    @Test
    void policiesAreManagedThroughPhilterAsTheSignedInPerson() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        assertEquals("/", signIn(session, address, "admin", ADMIN_PASSWORD).getResponse().getRedirectedUrl());
        final String name = "it-" + HexFormat.of().formatHex(bytes(3));
        final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
        final String template = bootstrap().getPolicyTemplate("default");
        final tools.jackson.databind.node.ObjectNode smaller =
                (tools.jackson.databind.node.ObjectNode) json.readTree(template);
        final tools.jackson.databind.node.ObjectNode identifiers =
                (tools.jackson.databind.node.ObjectNode) smaller.get("identifiers");
        final String first = identifiers.propertyNames().iterator().next();
        identifiers.retain(first);

        // A new policy starts from Philter's template, and Philter explains an invalid one.
        assertTrue(mockMvc.perform(get("/policies/new").session(session).with(from(address)))
                .andReturn().getResponse().getContentAsString().contains("identifiers"));
        final String invalid = mockMvc.perform(post("/policies/new").session(session).with(csrf()).with(from(address))
                        .param("name", name).param("json", "{\"identifiers\": {}}"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(invalid.contains("Philter did not accept the policy: "), invalid);

        mockMvc.perform(post("/policies/new").session(session).with(csrf()).with(from(address))
                        .param("name", name).param("json", template).param("description", "first"))
                .andExpect(redirectedUrl("/policies"));
        final String listed = mockMvc.perform(get("/policies").param("q", name).session(session).with(from(address)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(listed.contains(">" + name + "<") && listed.contains(">first<"), listed);
        assertEquals("first", PhilterClients.build(philterUrl(), BOOTSTRAP_KEY, (javax.net.ssl.SSLContext) null)
                .getPolicyDetails(name, "admin").getDescription(), "created as the signed-in person, admin");

        // Edit with the tag read when the editor opened; a second save with that tag is refused.
        final String editor = mockMvc.perform(get("/policies/edit").param("name", name).session(session)
                .with(from(address))).andReturn().getResponse().getContentAsString();
        final String eTag = hidden(editor, "eTag");
        mockMvc.perform(post("/policies/edit").session(session).with(csrf()).with(from(address))
                        .param("name", name).param("json", smaller.toString()).param("eTag", eTag)
                        .param("description", "second").param("originalDescription", "first"))
                .andExpect(redirectedUrl("/policies"));
        final String stale = mockMvc.perform(post("/policies/edit").session(session).with(csrf()).with(from(address))
                        .param("name", name).param("json", template).param("eTag", eTag))
                .andReturn().getResponse().getContentAsString();
        assertTrue(stale.contains("changed by someone else"), stale);

        // History shows both revisions and what changed; rolling back to the first makes a new revision.
        final java.util.List<Integer> revisions = bootstrap().listPolicyVersions(name, "admin",
                new ai.philterd.philter.model.ListOptions().limit(10)).getItems().stream()
                .map(ai.philterd.philter.model.PolicyVersionSummary::getRevision).toList();
        assertEquals(2, revisions.size(), "the create and the edit: " + revisions);
        final int older = revisions.get(1);
        final int newer = revisions.get(0);
        final MvcResult opened = mockMvc.perform(get("/policies/history").param("name", name)
                .param("from", String.valueOf(older)).param("to", String.valueOf(newer))
                .session(session).with(from(address))).andReturn();
        assertEquals(null, opened.getResponse().getRedirectedUrl(), String.valueOf(opened.getFlashMap().get("error")));
        final String history = opened.getResponse().getContentAsString();
        assertTrue(history.contains("Roll " + name + " back to revision " + older + "?"), history);
        assertTrue(history.contains("What changed from revision " + older + " to " + newer), history);
        mockMvc.perform(post("/policies/rollback").session(session).with(csrf()).with(from(address))
                        .param("name", name).param("revision", String.valueOf(older)).param("eTag", hidden(history, "eTag")))
                .andExpect(redirectedUrl("/policies/history?name=" + name));
        assertTrue(json.readTree(bootstrap().getPolicy(name, "admin")).get("identifiers").size() > 1,
                "back to the template's identifiers");

        // Deleting keeps the history, and the policy is restored from it.
        mockMvc.perform(post("/policies/delete").session(session).with(csrf()).with(from(address))
                .param("name", name)).andExpect(redirectedUrl("/policies"));
        final String deleted = mockMvc.perform(get("/policies/deleted").param("q", name).session(session)
                .with(from(address))).andReturn().getResponse().getContentAsString();
        assertTrue(deleted.contains(">" + name + "<"), deleted);
        mockMvc.perform(post("/policies/restore").session(session).with(csrf()).with(from(address))
                .param("name", name).param("revision", String.valueOf(newer))).andExpect(redirectedUrl("/policies"));
        assertEquals(1, json.readTree(bootstrap().getPolicy(name, "admin")).get("identifiers").size(),
                "restored from the edited revision");
    }

    @Test
    void listsAreManagedThroughPhilterAsTheSignedInPerson() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        assertEquals("/", signIn(session, address, "admin", ADMIN_PASSWORD).getResponse().getRedirectedUrl());
        final String name = "it-list-" + HexFormat.of().formatHex(bytes(3));

        // A custom list is created, listed, replaced, and deleted as admin.
        mockMvc.perform(post("/policies/lists/new").session(session).with(csrf()).with(from(address))
                        .param("name", name).param("description", "code names")
                        .param("items", "Project Phoenix\r\n\r\nBluebird"))
                .andExpect(redirectedUrl("/policies/lists"));
        assertEquals(java.util.List.of("Project Phoenix", "Bluebird"), bootstrap().getList(name, "admin").getItems());
        final String listed = mockMvc.perform(get("/policies/lists").param("q", name).session(session)
                .with(from(address))).andReturn().getResponse().getContentAsString();
        assertTrue(listed.contains(">" + name + "<") && listed.contains(">code names<"), listed);
        final String again = mockMvc.perform(post("/policies/lists/new").session(session).with(csrf())
                        .with(from(address)).param("name", name).param("items", "x"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(again.contains("A list with that name already exists."), again);

        mockMvc.perform(post("/policies/lists/edit").session(session).with(csrf()).with(from(address))
                        .param("name", name).param("description", "code names")
                        .param("originalDescription", "code names").param("items", "Bluebird"))
                .andExpect(redirectedUrl("/policies/lists"));
        final ai.philterd.philter.model.CustomList replaced = bootstrap().getList(name, "admin");
        assertEquals(java.util.List.of("Bluebird"), replaced.getItems());
        assertEquals("code names", replaced.getDescription(), "an unchanged description is kept");

        // Philter refuses another user's lists without ADMIN_CROSS_USER_ACCESS_ENABLED, and the page says so.
        final String other = createUser("lists", password());
        assertTrue(mockMvc.perform(get("/policies/lists").param("owner", other).session(session)
                        .with(from(address))).andReturn().getResponse().getContentAsString()
                .contains("ADMIN_CROSS_USER_ACCESS_ENABLED=true"));

        mockMvc.perform(post("/policies/lists/delete").session(session).with(csrf()).with(from(address))
                .param("name", name)).andExpect(redirectedUrl("/policies/lists"));
        assertTrue(bootstrap().listCustomLists("admin", null).getItems().stream()
                .noneMatch(l -> name.equals(l.getName())), "deleted");

        // A redact list is replaced at the revision the page read; a save with that revision again is refused.
        final ai.philterd.philter.model.RedactList always = ai.philterd.philter.model.RedactList.ALWAYS;
        final String page = mockMvc.perform(get("/policies/redact-lists").session(session).with(from(address)))
                .andReturn().getResponse().getContentAsString();
        final String revision = String.valueOf(bootstrap().getRedactList(always, "admin").getRevision());
        assertTrue(page.contains("name=\"revision\" value=\"" + revision + "\""), page);
        mockMvc.perform(post("/policies/redact-lists").session(session).with(csrf()).with(from(address))
                        .param("list", "always").param("terms", name + "\r\nBluebird").param("revision", revision))
                .andExpect(redirectedUrl("/policies/redact-lists"));
        final ai.philterd.philter.model.RedactListTerms saved = bootstrap().getRedactList(always, "admin");
        assertEquals(java.util.List.of(name, "Bluebird"), saved.getTerms());
        final String stale = mockMvc.perform(post("/policies/redact-lists").session(session).with(csrf())
                        .with(from(address)).param("list", "always").param("terms", "mine").param("revision", revision))
                .andReturn().getResponse().getContentAsString();
        assertTrue(stale.contains("Someone changed this list after you opened it"), stale);
        assertTrue(stale.contains("name=\"revision\" value=\"" + saved.getRevision() + "\""), "Philter's current revision");
        assertEquals(saved.getTerms(), bootstrap().getRedactList(always, "admin").getTerms(), "nothing was written");

        mockMvc.perform(post("/policies/redact-lists").session(session).with(csrf()).with(from(address))
                        .param("list", "always").param("terms", "").param("revision", String.valueOf(saved.getRevision())))
                .andExpect(redirectedUrl("/policies/redact-lists"));
        assertTrue(bootstrap().getRedactList(always, "admin").getTerms().isEmpty(), "an empty save clears the list");
    }

    @Test
    void contextsAreManagedThroughPhilterAsTheSignedInPerson() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        assertEquals("/", signIn(session, address, "admin", ADMIN_PASSWORD).getResponse().getRedirectedUrl());
        final String name = "it-context-" + HexFormat.of().formatHex(bytes(3));

        mockMvc.perform(post("/policies/contexts/new").session(session).with(csrf()).with(from(address))
                        .param("name", name).param("disambiguation", "true").param("scope", "document"))
                .andExpect(redirectedUrl("/policies/contexts"));
        final ai.philterd.philter.model.ContextDetails created = bootstrap().getContextDetails(name, "admin");
        assertTrue(created.isEntityTypeDisambiguation() && !created.isLedger(), "created as admin, with its settings");
        assertEquals("document", created.getDisambiguationScope());

        final String listed = mockMvc.perform(get("/policies/contexts").param("q", name).session(session)
                .with(from(address))).andReturn().getResponse().getContentAsString();
        assertTrue(listed.contains(">" + name + "<") && listed.contains(">0<"), listed);
        final String again = mockMvc.perform(post("/policies/contexts/new").session(session).with(csrf())
                        .with(from(address)).param("name", name))
                .andReturn().getResponse().getContentAsString();
        assertTrue(again.contains("A context with that name already exists."), again);

        // Only the scope changed, so only it is sent; Philter keeps disambiguation on.
        final String form = mockMvc.perform(get("/policies/contexts/edit").param("name", name).session(session)
                .with(from(address))).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/policies/contexts/edit").session(session).with(csrf()).with(from(address))
                        .param("name", name).param("disambiguation", "true").param("scope", "context")
                        .param("originalDisambiguation", hidden(form, "originalDisambiguation"))
                        .param("originalScope", hidden(form, "originalScope"))
                        .param("originalLedger", hidden(form, "originalLedger")))
                .andExpect(redirectedUrl("/policies/contexts"));
        final ai.philterd.philter.model.ContextDetails edited = bootstrap().getContextDetails(name, "admin");
        assertEquals("context", edited.getDisambiguationScope());
        assertTrue(edited.isEntityTypeDisambiguation());

        // A replacement kept in the context is an entry; the list and the view count it by filter type.
        final String policy = name + "-policy";
        bootstrap().savePolicy(policy, "{\"identifiers\":{\"emailAddress\":{\"emailAddressFilterStrategies\":"
                + "[{\"strategy\":\"RANDOM_REPLACE\",\"replacementScope\":\"CONTEXT\"}]}}}", "admin");
        bootstrap().filter(name, policy, "Write to jdoe@example.com or ann@example.org about the claim.");
        assertEquals(2, bootstrap().getContextDetails(name, "admin").getSize(), "two entries");
        assertTrue(mockMvc.perform(get("/policies/contexts").param("q", name).session(session).with(from(address)))
                .andReturn().getResponse().getContentAsString().contains(">2<"), "counted in the list");
        final String view = mockMvc.perform(get("/policies/contexts/view").param("name", name).session(session)
                .with(from(address))).andReturn().getResponse().getContentAsString();
        assertTrue(view.contains("2 entries") && view.contains("Disambiguation within the context"), view);
        assertTrue(view.contains("Its 2 entries are deleted"), "the clear confirmation counts them");

        final String other = createUser("contexts", password());
        assertTrue(mockMvc.perform(get("/policies/contexts").param("owner", other).session(session)
                        .with(from(address))).andReturn().getResponse().getContentAsString()
                .contains("ADMIN_CROSS_USER_ACCESS_ENABLED=true"));

        mockMvc.perform(post("/policies/contexts/clear").session(session).with(csrf()).with(from(address))
                        .param("name", name)).andExpect(redirectedUrl("/policies/contexts"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("success", "Context \"" + name + "\" cleared."));
        assertEquals(0, bootstrap().getContextDetails(name, "admin").getSize(), "cleared, and the context is kept");
        mockMvc.perform(post("/policies/contexts/delete").session(session).with(csrf()).with(from(address))
                .param("name", name)).andExpect(redirectedUrl("/policies/contexts"));
        assertTrue(bootstrap().listContexts("admin", null).getContexts().stream()
                .noneMatch(c -> name.equals(c.getName())), "deleted");
    }

    @Test
    void anAdministratorsApiKeyIsFlaggedUnderAdminPhilter() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        assertEquals("/", signIn(session, address, "admin", ADMIN_PASSWORD).getResponse().getRedirectedUrl());

        final String error = mockMvc.perform(post("/admin/philter/test").session(session).with(csrf()).with(from(address)))
                .andExpect(redirectedUrl("/admin/philter")).andReturn().getFlashMap().get("error").toString();
        assertTrue(error.contains("Philter 4.0.0") && error.contains("belongs to a Philter administrator"), error);

        final String page = mockMvc.perform(get("/admin/philter").session(session).with(from(address)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("Set; belongs to a Philter administrator"), "the page shows the flag");
        assertTrue(!page.contains(BOOTSTRAP_KEY), "the page never shows the key");
    }

    @Test
    void ingestRedactsThroughTheConfiguredPhilter() throws Exception {
        final String text = "Write to jdoe@example.com about the claim.";
        final ai.philterd.workspace.core.model.RedactionResponse response =
                redactionService.redactText(text, null, null);

        assertTrue(response.getRedactions().stream()
                        .anyMatch(r -> "email-address".equals(r.getType()) && "jdoe@example.com".equals(r.getText())),
                "Philter's default policy finds the email address");
        assertFalse(response.getRedactedText().contains("jdoe@example.com"));
    }

    @Test
    void theEmbeddedIndexServesSearchAndFindSimilarForRedactedDocuments() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        signIn(session, address, "admin", ADMIN_PASSWORD);
        final ai.philterd.workspace.model.GeneralSettings settings = generalSettingsService.load();
        settings.setFullTextSearchEnabled(true);
        generalSettingsService.save(settings);
        final String suffix = HexFormat.of().formatHex(bytes(3));
        final ai.philterd.workspace.model.Batch batch = new ai.philterd.workspace.model.Batch();
        batch.setId(java.util.UUID.randomUUID().toString());
        batch.setName("Search " + suffix);
        batchRepository.save(batch);
        final String word = "zq" + suffix;
        final ai.philterd.workspace.model.Document lease = ingestQueueService.enqueueText(batch, "lease.txt",
                "The tenant " + word + " breached the warehouse lease on Elm Street; write to jdoe@example.com.", 2);
        final ai.philterd.workspace.model.Document copy = ingestQueueService.enqueueText(batch, "copy.txt",
                "The tenant " + word + " breached the lease for the Elm Street warehouse; write to jdoe@example.com.", 2);
        final ai.philterd.workspace.model.Document other = ingestQueueService.enqueueText(batch, "invoice.txt",
                "Quarterly invoice totals for accounting.", 2);
        try {
            final long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline && java.util.List.of(lease, copy, other).stream()
                    .map(d -> documentRepository.findById(d.getId()).orElseThrow().getStatus())
                    .anyMatch(st -> "PENDING".equals(st) || "PROCESSING".equals(st))) {
                ingestQueueService.drain();
                Thread.sleep(250);
            }
            assertEquals("embedded", documentSearchService.backend());

            // The query goes in the body and comes back as a token (#78).
            final String results = mockMvc.perform(post("/search").param("q", word).session(session)
                            .with(from(address)).with(csrf()))
                    .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
            assertTrue(results.matches("/search\\?r=[0-9a-f]{32}"), results);
            final String page = mockMvc.perform(get(results).session(session).with(from(address)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(page.contains("lease.txt") && page.contains("copy.txt"), "both documents are found");
            assertTrue(page.contains("<mark>" + word + "</mark>"), "the match is highlighted");
            assertFalse(page.contains("invoice.txt"));

            final String similar = mockMvc.perform(get("/api/v1/review/" + lease.getId() + "/similar")
                            .session(session).with(from(address)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(similar.indexOf(copy.getId()) >= 0, similar);
            assertFalse(similar.contains(lease.getId()), "not the document itself");

            // A rebuild from the store: emptied, then refilled from every document with text.
            assertTrue(documentSearchService.clear());
            assertEquals(0, documentSearchService.search(word, 0, 10).total());
            assertEquals(null, searchIndexRebuildService.start("admin"));
            final long rebuilt = System.currentTimeMillis() + 60_000;
            while (searchIndexRebuildService.status().running() && System.currentTimeMillis() < rebuilt) {
                Thread.sleep(100);
            }
            assertEquals(null, searchIndexRebuildService.status().error());
            assertTrue(searchIndexRebuildService.status().indexed() >= 3);
            assertEquals(2, documentSearchService.search(word, 0, 10).total(), "refilled from the database");
        } finally {
            documentSearchService.clear();
            final ai.philterd.workspace.model.GeneralSettings after = generalSettingsService.load();
            after.setFullTextSearchEnabled(false);
            generalSettingsService.save(after);
        }
    }

    @Test
    void aFinalizedDocumentIsDeliveredToItsBatchDestination(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir)
            throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        signIn(session, address, "admin", ADMIN_PASSWORD);
        final String suffix = HexFormat.of().formatHex(bytes(3));
        final ai.philterd.workspace.model.LocalDirectoryDestination destination =
                new ai.philterd.workspace.model.LocalDirectoryDestination();
        destination.setId(java.util.UUID.randomUUID().toString());
        destination.setName("staging-" + suffix);
        destination.setDirectoryPath(dir.toString());
        localDestinations.save(destination);
        final ai.philterd.workspace.model.Batch batch = new ai.philterd.workspace.model.Batch();
        batch.setId(java.util.UUID.randomUUID().toString());
        batch.setName("Delivery " + suffix);
        batchRepository.save(batch);

        // The administrator chooses the destination on the Batches page.
        mockMvc.perform(post("/batches/" + batch.getId() + "/destination").param("destination",
                        "LOCAL:" + destination.getId()).session(session).with(csrf()).with(from(address)))
                .andExpect(status().is3xxRedirection());
        assertEquals("LOCAL", batchRepository.findById(batch.getId()).orElseThrow().getDestinationType());

        final String text = "Write to jdoe@example.com about the claim.";
        final ai.philterd.workspace.model.Document queued =
                ingestQueueService.enqueueText(batch, "claim letter.txt", text, 2);
        final long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline && java.util.Set.of("PENDING", "PROCESSING")
                .contains(documentRepository.findById(queued.getId()).orElseThrow().getStatus())) {
            ingestQueueService.drain();
            Thread.sleep(250);
        }
        // Accept every span and approve the document, as review would.
        for (ai.philterd.workspace.model.Span span : spanRepository.findByDocumentId(queued.getId())) {
            span.setStatus("APPROVED");
            spanRepository.save(span);
        }
        final ai.philterd.workspace.model.Document approved = documentRepository.findById(queued.getId()).orElseThrow();
        approved.changeStatus("APPROVED");
        documentRepository.save(approved);

        mockMvc.perform(post("/review/" + queued.getId() + "/finalize").session(session).with(csrf())
                .with(from(address))).andExpect(status().is3xxRedirection());

        final long delivered = System.currentTimeMillis() + 30_000;
        ai.philterd.workspace.model.Document done = documentRepository.findById(queued.getId()).orElseThrow();
        while (!"DELIVERED".equals(done.getDeliveryStatus()) && System.currentTimeMillis() < delivered) {
            Thread.sleep(200);
            done = documentRepository.findById(queued.getId()).orElseThrow();
        }
        assertEquals("DELIVERED", done.getDeliveryStatus(), done.getDeliveryError());
        final java.nio.file.Path file = dir.resolve(queued.getId()).resolve("claim letter_redacted.txt");
        final String written = java.nio.file.Files.readString(file);
        assertEquals(done.getRedactedText(), written);
        assertFalse(written.contains("jdoe@example.com"), written);

        final String review = mockMvc.perform(get("/review/" + queued.getId()).session(session).with(from(address)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(review.contains("Delivered to") && review.contains("staging-" + suffix), "the review page says so");

        // The destination cannot be removed while the batch uses it.
        mockMvc.perform(post("/admin/destinations/local/" + destination.getId() + "/delete").session(session)
                .with(csrf()).with(from(address))).andExpect(status().is3xxRedirection());
        assertTrue(localDestinations.findById(destination.getId()).isPresent());
    }

    @Test
    void aDocumentsAuditTrailJoinsItsEventsWithPhiltersLedger() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        signIn(session, address, "admin", ADMIN_PASSWORD);
        final String suffix = HexFormat.of().formatHex(bytes(3));
        final String context = "it-trail-" + suffix;
        bootstrap().createContext(context, false, "document", true, null);
        final ai.philterd.workspace.model.Batch batch = new ai.philterd.workspace.model.Batch();
        batch.setId(java.util.UUID.randomUUID().toString());
        batch.setName("Trail " + suffix);
        batch.setContext(context);
        batchRepository.save(batch);
        final ai.philterd.workspace.model.Document queued = ingestQueueService.enqueueText(batch,
                "Jane Roe letter " + suffix + ".txt", "Write to jdoe@example.com about the claim.", 2);
        final long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline && java.util.Set.of("PENDING", "PROCESSING")
                .contains(documentRepository.findById(queued.getId()).orElseThrow().getStatus())) {
            ingestQueueService.drain();
            Thread.sleep(250);
        }
        final ai.philterd.workspace.model.Document redacted = documentRepository.findById(queued.getId()).orElseThrow();
        assertTrue(redacted.getPhilterDocumentId() != null, redacted.getStatus());

        // The Review page's ledger: read with Philter Workspace's key, which owns the chain.
        final String ledger = mockMvc.perform(get("/api/v1/documents/" + queued.getId() + "/ledger")
                .session(session).with(from(address))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        final tools.jackson.databind.JsonNode ledgerJson = new tools.jackson.databind.ObjectMapper()
                .readTree(ledger);
        assertEquals(redacted.getPhilterDocumentId(), ledgerJson.at("/chains/0/philterDocumentId").asString());
        assertTrue(ledgerJson.at("/chains/0/verified").asBoolean(), ledger);
        assertEquals("[genesis]", ledgerJson.at("/entries/0/previousHash").asString(), "Philter opens the chain");
        assertEquals("email-address", ledgerJson.at("/entries/1/type").asString(), ledger);

        final String trail = mockMvc.perform(get("/api/v1/documents/" + queued.getId() + "/audit-trail")
                .param("format", "json").session(session).with(from(address))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper()
                .readTree(trail);
        assertTrue(json.at("/chains/0/verified").asBoolean(), trail);
        final java.util.List<String> sources = new java.util.ArrayList<>();
        final java.util.List<String> actions = new java.util.ArrayList<>();
        json.get("events").forEach(e -> {
            sources.add(e.get("source").asString());
            actions.add(e.path("action").asString(""));
        });
        assertTrue(sources.contains("PHILTER_LEDGER") && sources.contains("PHILTER_WORKSPACE"), trail);
        assertTrue(actions.contains("AUDIT_TRAIL_EXPORT"), "the export is in its own trail");
        assertFalse(trail.contains("jdoe@example.com"), "no original value");
        assertFalse(trail.contains("{{{REDACTED"), "no replacement");
        assertFalse(trail.contains("Jane Roe"), "no filename from Philter's ledger");

        final String csv = mockMvc.perform(get("/admin/audit/trail").param("format", "csv")
                .param("startTime", java.time.Instant.now().minusSeconds(3600).toString())
                .session(session).with(from(address))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertTrue(csv.contains(",PHILTER_LEDGER_CHAIN," + queued.getId() + ",,CHAIN_VERIFICATION,VERIFIED,"), csv);
        assertFalse(csv.contains("jdoe@example.com"));

        // A key without ledger:read is told so, not shown an empty ledger.
        final String limited = bootstrap().createApiKey(java.util.List.of("redact")).getApiKey();
        final ai.philterd.workspace.service.DocumentLedgerService withoutScope =
                new ai.philterd.workspace.service.DocumentLedgerService(new ai.philterd.workspace.service.PhilterSettings(
                        philterUrl(), limited, ai.philterd.workspace.philter.PhilterTlsConfig.none()));
        assertEquals("KEY_LACKS_SCOPE", withoutScope.forDocument(redacted).chains().get(0).state());
    }

    @Test
    void aReviewerMovesTheBoundariesOfASpanPhilterDetectedAndResetPutsThemBack() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        signIn(session, address, "admin", ADMIN_PASSWORD);
        final ai.philterd.workspace.model.Batch batch = new ai.philterd.workspace.model.Batch();
        batch.setId(java.util.UUID.randomUUID().toString());
        batch.setName("Boundaries " + batch.getId());
        batch.setConfidenceThreshold(1.01);
        batchRepository.save(batch);
        final ai.philterd.workspace.model.Document queued = ingestQueueService.enqueueText(batch, "letter.txt",
                "Write to jdoe@example.com about the claim.", 2);
        drainUntilDone(queued.getId());
        final ai.philterd.workspace.model.Span detected = spanRepository.findByDocumentId(queued.getId()).stream()
                .filter(sp -> "jdoe@example.com".equals(sp.getText())).findFirst().orElseThrow();
        assertEquals("PENDING", detected.getStatus());
        final int start = detected.getLocation().characterStart();
        final int end = detected.getLocation().characterEnd();

        // "jdoe@example.com" shrunk to "jdoe": moved and accepted in one call.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/spans/" + detected.getId()).session(session).with(csrf()).with(from(address))
                        .contentType("application/json").content("{\"start\":" + start + ",\"end\":" + (start + 4) + "}"))
                .andExpect(status().isOk());
        final ai.philterd.workspace.model.Span moved = spanRepository.findById(detected.getId()).orElseThrow();
        assertEquals("jdoe", moved.getText());
        assertEquals("APPROVED", moved.getStatus());
        assertTrue(moved.isAdjusted());
        assertEquals(start, moved.getOriginalCharacterStart());
        assertEquals(end, moved.getOriginalCharacterEnd());
        final String page = mockMvc.perform(get("/review/" + queued.getId()).session(session).with(from(address)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("\"adjusted\":true"), "the review page marks it adjusted");
        final String history = mockMvc.perform(get("/api/v1/documents/" + queued.getId() + "/history")
                .session(session).with(from(address))).andReturn().getResponse().getContentAsString();
        assertTrue(history.contains("SPAN_ADJUST"), history);

        mockMvc.perform(post("/api/v1/spans/" + detected.getId() + "/reset").session(session).with(csrf())
                        .with(from(address)).contentType("application/json").content("{\"originalStatus\":\"PENDING\"}"))
                .andExpect(status().isOk());
        final ai.philterd.workspace.model.Span reset = spanRepository.findById(detected.getId()).orElseThrow();
        assertEquals("jdoe@example.com", reset.getText());
        assertEquals(end, reset.getLocation().characterEnd());
        assertFalse(reset.isAdjusted());
        assertEquals("PENDING", reset.getStatus());
    }

    @Test
    void redactAllLikeThisRefusesAOneCharacterSpanWithItsReason() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        signIn(session, address, "admin", ADMIN_PASSWORD);
        final ai.philterd.workspace.model.Batch batch = new ai.philterd.workspace.model.Batch();
        batch.setId(java.util.UUID.randomUUID().toString());
        batch.setName("Redact like " + batch.getId());
        batchRepository.save(batch);
        final ai.philterd.workspace.model.Document queued = ingestQueueService.enqueueText(batch, "letter.txt",
                "Box A, box B, box C.", 2);
        drainUntilDone(queued.getId());

        // Selecting one character still makes a span: one selection is one span (#75).
        final String created = mockMvc.perform(post("/api/v1/documents/" + queued.getId() + "/spans").session(session)
                        .with(csrf()).with(from(address)).contentType("application/json")
                        .content("{\"type\":\"person\",\"start\":4,\"end\":5}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        final String spanId = new tools.jackson.databind.ObjectMapper().readTree(created).get("id").asString();

        // Redact All Like This refuses it, with the reason in the body despite include-message=never.
        final String refused = mockMvc.perform(post("/api/v1/spans/" + spanId + "/redact-like").session(session)
                        .with(csrf()).with(from(address)).contentType("application/json"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertTrue(refused.contains("at least 3 characters"), refused);
        assertEquals(1, spanRepository.findByDocumentId(queued.getId()).stream()
                .filter(sp -> "A".equals(sp.getText())).count(), "no span was added");
    }

    @Test
    void tomcatRefusesAnUploadWithTooManyParts() throws Exception {
        // Through the real server: MockMvc does not parse multipart bodies with Tomcat (#79, CVE-2025-48988).
        final java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        final java.util.function.IntFunction<Integer> postWithParts = parts -> {
            final String boundary = "b" + HexFormat.of().formatHex(bytes(8));
            final StringBuilder body = new StringBuilder();
            for (int i = 0; i < parts; i++) {
                body.append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"file\"; filename=\"f").append(i)
                        .append(".txt\"\r\nContent-Type: text/plain\r\n\r\nx\r\n");
            }
            body.append("--").append(boundary).append("--\r\n");
            try {
                return client.send(java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("http://localhost:" + PORT + "/upload"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        final int few = postWithParts.apply(5);
        final int tooMany = postWithParts.apply(150);
        // Over Spring Boot's default of 50 parts, within the configured 110.
        assertEquals(few, postWithParts.apply(60), "60 parts are allowed");
        assertTrue(few == 403 || (few >= 300 && few < 400), "a small upload reaches the security checks: " + few);
        assertTrue(tooMany != few && tooMany >= 400, "150 parts are refused by Tomcat: " + tooMany);
    }

    @Test
    void finalizingThroughTheApiIssuesACertificateAndAuditsItLikeTheReviewPage() throws Exception {
        final ai.philterd.workspace.model.Batch batch = new ai.philterd.workspace.model.Batch();
        batch.setId(java.util.UUID.randomUUID().toString());
        batch.setName("API finalize " + batch.getId());
        batchRepository.save(batch);
        final ai.philterd.workspace.model.Document queued = ingestQueueService.enqueueText(batch, "letter.txt",
                "Write to jdoe@example.com about the claim.", 2);
        drainUntilDone(queued.getId());
        for (ai.philterd.workspace.model.Span span : spanRepository.findByDocumentId(queued.getId())) {
            span.setStatus("APPROVED");
            spanRepository.save(span);
        }
        final ai.philterd.workspace.model.Document approved = documentRepository.findById(queued.getId()).orElseThrow();
        approved.changeStatus("APPROVED");
        documentRepository.save(approved);

        final String body = mockMvc.perform(post("/api/v1/documents/" + queued.getId() + "/finalize")
                        .header("Authorization", "Bearer " + BOOTSTRAP_KEY).contentType("application/json"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // The same output the review page produces (#61).
        assertTrue(body.contains("Write to <<EMAIL-ADDRESS>> about the claim."), body);
        final ai.philterd.workspace.model.Document finalized = documentRepository.findById(queued.getId()).orElseThrow();
        assertEquals("FINALIZED", finalized.getStatus());
        assertEquals("Write to <<EMAIL-ADDRESS>> about the claim.", finalized.getRedactedText());
        assertFalse(certificateRepository.findByDocumentIdOrderByFinalizedAtDesc(queued.getId()).isEmpty(),
                "a certificate of redaction");
        final ai.philterd.workspace.model.AuditLog finalize = auditLogQueryService.findForDocument(queued.getId(),
                java.util.List.of()).stream().filter(e -> "DOCUMENT_FINALIZE".equals(e.getAction())).findFirst()
                .orElseThrow();
        assertEquals("api", finalize.getDetails().get("via"));
        assertEquals("admin", finalize.getDetails().get("actor"));

        // Finalizing again is refused, as on the review page.
        mockMvc.perform(post("/api/v1/documents/" + queued.getId() + "/finalize")
                        .header("Authorization", "Bearer " + BOOTSTRAP_KEY).contentType("application/json"))
                .andExpect(status().isConflict());
    }

    @Test
    void aBatchRedactsEachDocumentWithThePolicyMappedToItsCaseType() throws Exception {
        final String suffix = HexFormat.of().formatHex(bytes(3));
        final String policy = "it-email-only-" + suffix;
        final String caseType = "civil" + suffix;
        // Finds email addresses only, so a person's name in the text shows which policy was used.
        bootstrap().savePolicy(policy, "{\"identifiers\":{\"emailAddress\":{\"emailAddressFilterStrategies\":"
                + "[{\"strategy\":\"REDACT\"}]}}}", "admin");
        final ai.philterd.workspace.model.GeneralSettings settings = generalSettingsService.load();
        settings.getCaseTypePolicies().add(new ai.philterd.workspace.model.CaseTypePolicy(caseType, policy));
        generalSettingsService.save(settings);

        final ai.philterd.workspace.model.Batch batch = new ai.philterd.workspace.model.Batch();
        batch.setId(java.util.UUID.randomUUID().toString());
        batch.setName("By case type " + suffix);
        batch.setPolicyByCaseType(true);
        batchRepository.save(batch);
        final String text = "Jane Roe wrote to jdoe@example.com about the claim.";
        final ai.philterd.workspace.model.Document mapped = ingestQueueService.enqueueText(batch, "mapped.txt", text, 2,
                ai.philterd.workspace.model.CaseMetadata.of("1:26-cv-01234", caseType.toUpperCase(), "Jane Roe",
                        "paid", false));
        final ai.philterd.workspace.model.Document unmapped = ingestQueueService.enqueueText(batch, "unmapped.txt",
                text, 2, ai.philterd.workspace.model.CaseMetadata.of(null, "criminal" + suffix, null, null, null));
        try {
            final long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline && java.util.List.of(mapped, unmapped).stream()
                    .map(d -> documentRepository.findById(d.getId()).orElseThrow().getStatus())
                    .anyMatch(st -> "PENDING".equals(st) || "PROCESSING".equals(st))) {
                ingestQueueService.drain();
                Thread.sleep(250);
            }

            final ai.philterd.workspace.model.Document done = documentRepository.findById(mapped.getId()).orElseThrow();
            assertFalse("FAILED".equals(done.getStatus()), done.getFailureMessage());
            assertEquals(policy, done.getAppliedPolicyName(), "the policy Philter reports applying is recorded");
            assertTrue(done.getAppliedPolicyVersion() != null && done.getAppliedPolicyVersion() >= 0);
            final java.util.List<String> types = spanRepository.findByDocumentId(mapped.getId()).stream()
                    .map(ai.philterd.workspace.model.Span::getType).toList();
            assertEquals(java.util.List.of("email-address"), types, "only what the mapped policy finds");

            final ai.philterd.workspace.model.Document flagged =
                    documentRepository.findById(unmapped.getId()).orElseThrow();
            assertEquals("FAILED", flagged.getStatus());
            assertTrue(flagged.getFailureMessage().contains("no policy is mapped to case type \"criminal" + suffix),
                    flagged.getFailureMessage());
            assertTrue(spanRepository.findByDocumentId(unmapped.getId()).isEmpty(), "not redacted with another policy");
        } finally {
            final ai.philterd.workspace.model.GeneralSettings after = generalSettingsService.load();
            after.getCaseTypePolicies().removeIf(e -> caseType.equals(e.getCaseType()));
            generalSettingsService.save(after);
        }
    }

    @Test
    void detectionRulesChangeWhatPhilterFlagsInLaterDocuments() throws Exception {
        final org.springframework.security.core.Authentication admin =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin@example.com",
                        null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_ADMIN")));
        final ai.philterd.workspace.model.Batch batch = new ai.philterd.workspace.model.Batch();
        batch.setId(java.util.UUID.randomUUID().toString());
        batch.setName("Rules " + batch.getId());
        batchRepository.save(batch);
        final ai.philterd.workspace.model.Document document = new ai.philterd.workspace.model.Document();
        document.setId(java.util.UUID.randomUUID().toString());
        document.setBatchId(batch.getId());
        document.setFilename("letterhead.txt");
        documentRepository.save(document);

        final String text = "Reply to front-desk@example.net about Project Phoenix.";
        final java.util.function.Function<String, java.util.List<String>> flagged = value -> {
            try {
                return redactionService.redactText(text, null, null).getRedactions().stream()
                        .filter(r -> value.equals(r.getText())).map(r -> r.getType()).toList();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        assertEquals(java.util.List.of("email-address"), flagged.apply("front-desk@example.net"));
        assertTrue(flagged.apply("Project Phoenix").isEmpty(), "the default policy has no reason to flag it");

        final ai.philterd.workspace.model.Span refused = new ai.philterd.workspace.model.Span();
        refused.setId(java.util.UUID.randomUUID().toString());
        refused.setDocumentId(document.getId());
        refused.setType("email-address");
        refused.setText("front-desk@example.net");
        refused.setStatus("REJECTED");
        final ai.philterd.workspace.model.Span added = new ai.philterd.workspace.model.Span();
        added.setId(java.util.UUID.randomUUID().toString());
        added.setDocumentId(document.getId());
        added.setType("organization");
        added.setText("Project Phoenix");
        added.setStatus("APPROVED");
        added.setManuallyCreated(true);

        final ai.philterd.workspace.model.DetectionRule never = detectionRuleService.makeFromSpan(
                ai.philterd.workspace.model.DetectionRule.KIND_NEVER, refused, document, admin).rule();
        final ai.philterd.workspace.model.DetectionRule always = detectionRuleService.makeFromSpan(
                ai.philterd.workspace.model.DetectionRule.KIND_ALWAYS, added, document, admin).rule();
        try {
            assertTrue(never.isActive() && always.isActive());
            assertTrue(bootstrap().getRedactList(ai.philterd.philter.model.RedactList.NEVER).getTerms()
                    .contains("front-desk@example.net"));

            assertTrue(flagged.apply("front-desk@example.net").isEmpty(), "never flagged once the rule is confirmed");
            assertEquals(java.util.List.of("custom-dictionary"), flagged.apply("Project Phoenix"),
                    "always flagged, as a custom dictionary match");
            // Both lists match without regard to case.
            final ai.philterd.workspace.core.model.RedactionResponse otherCase = redactionService.redactText(
                    "Reply to FRONT-DESK@EXAMPLE.NET about project phoenix.", null, null);
            assertTrue(otherCase.getRedactions().stream().noneMatch(r -> "FRONT-DESK@EXAMPLE.NET".equals(r.getText())),
                    "never flagged in another case");
            assertTrue(otherCase.getRedactions().stream().anyMatch(r -> "project phoenix".equals(r.getText())),
                    "always flagged in another case");
        } finally {
            detectionRuleService.remove(never, admin);
            detectionRuleService.remove(always, admin);
        }
        assertEquals(java.util.List.of("email-address"), flagged.apply("front-desk@example.net"), "removed");
        assertTrue(flagged.apply("Project Phoenix").isEmpty(), "removed");
    }

    @Test
    void queuedUploadsAreEncryptedAtRestAndStillRedacted() throws Exception {
        final org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        final ai.philterd.workspace.model.Batch batch = new ai.philterd.workspace.model.Batch();
        batch.setId(java.util.UUID.randomUUID().toString());
        batch.setName("Queued uploads " + batch.getId());
        batchRepository.save(batch);

        final String text = "Write to jdoe@example.com about the claim.";
        final byte[] pdf;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            final org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream stream =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        final ai.philterd.workspace.model.Document plain = ingestQueueService.enqueueFile(batch, "note.txt",
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain", 2);
        final ai.philterd.workspace.model.Document scanned = ingestQueueService.enqueueFile(batch, "claim.pdf",
                pdf, "application/pdf", 2);
        final ai.philterd.workspace.model.Document legacy = ingestQueueService.enqueueFile(batch, "old.txt",
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain", 2);

        for (ai.philterd.workspace.model.Document d : java.util.List.of(plain, scanned, legacy)) {
            final byte[] stored = java.util.Base64.getDecoder().decode(jdbc.queryForObject(
                    "SELECT json_extract(doc, '$.content') FROM pending_uploads WHERE id = ?", String.class, d.getId()));
            assertTrue(new String(stored, 0, 7, java.nio.charset.StandardCharsets.US_ASCII).equals("enc:v1:"),
                    "stored encrypted");
            assertFalse(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1).contains("jdoe@example.com"));
        }
        // One row as it was stored before uploads were encrypted: the plain bytes.
        jdbc.update("UPDATE pending_uploads SET doc = json_set(doc, '$.content', ?) WHERE id = ?",
                java.util.Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                legacy.getId());

        // The scheduled worker may be draining too; either way each document is processed once.
        final long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline && java.util.List.of(plain, scanned, legacy).stream()
                .map(d -> documentRepository.findById(d.getId()).orElseThrow().getStatus())
                .anyMatch(st -> "PENDING".equals(st) || "PROCESSING".equals(st))) {
            ingestQueueService.drain();
            Thread.sleep(250);
        }

        // Queued PDFs fail until Philter's PDF review is supported (#13), so the upload is kept: still
        // encrypted at rest, and its bytes intact.
        final ai.philterd.workspace.model.Document failed = documentRepository.findById(scanned.getId()).orElseThrow();
        assertEquals("FAILED", failed.getStatus());
        assertTrue(failed.getFailureMessage().startsWith("PDF review through Philter is not yet available."),
                failed.getFailureMessage());
        final byte[] kept = java.util.Base64.getDecoder().decode(jdbc.queryForObject(
                "SELECT json_extract(doc, '$.content') FROM pending_uploads WHERE id = ?", String.class, scanned.getId()));
        assertEquals("enc:v1:", new String(kept, 0, 7, java.nio.charset.StandardCharsets.US_ASCII));
        assertArrayEquals(pdf, pendingUploadRepository.findById(scanned.getId()).orElseThrow().getContent());

        for (ai.philterd.workspace.model.Document d : java.util.List.of(plain, legacy)) {
            final ai.philterd.workspace.model.Document done = documentRepository.findById(d.getId()).orElseThrow();
            assertFalse(java.util.List.of("PENDING", "PROCESSING", "FAILED").contains(done.getStatus()),
                    d.getFilename() + " is " + done.getStatus() + ": " + done.getFailureMessage());
            assertTrue(spanRepository.findByDocumentId(d.getId()).stream()
                            .anyMatch(span -> "jdoe@example.com".equals(span.getText())),
                    d.getFilename() + ": Philter found the email address in the uploaded bytes");
            assertTrue(pendingUploadRepository.findById(d.getId()).isEmpty(), "the upload is removed once redacted");
        }
    }

    @Test
    void aFailedDocumentIsRetriedAndKeepsPhiltersDocumentId() throws Exception {
        // A batch whose policy Philter does not have, so redaction fails.
        final ai.philterd.workspace.model.Batch batch = new ai.philterd.workspace.model.Batch();
        batch.setId(java.util.UUID.randomUUID().toString());
        batch.setName("Retry " + batch.getId());
        batch.setPolicyName("no-such-policy-" + batch.getId());
        batchRepository.save(batch);
        final ai.philterd.workspace.model.Document doc = ingestQueueService.enqueueFile(batch, "retry.txt",
                "Write to jdoe@example.com about the claim.".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "text/plain", 2);
        assertEquals("FAILED", drainUntilDone(doc.getId()).getStatus());
        assertNull(documentRepository.findById(doc.getId()).orElseThrow().getPhilterDocumentId());

        // Fix the batch, in a context that keeps Philter's ledger, then retry as an administrator.
        final String context = "retry-" + batch.getId();
        bootstrap().createContext(context, null, true);
        final ai.philterd.workspace.model.Batch fixed = batchRepository.findById(batch.getId()).orElseThrow();
        fixed.setPolicyName(null);
        fixed.setContext(context);
        batchRepository.save(fixed);
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        signIn(session, address, "admin", ADMIN_PASSWORD);
        mockMvc.perform(post("/admin/ingest-queue/" + doc.getId() + "/retry").session(session).with(csrf())
                        .with(from(address)))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Queued \"retry.txt\" to be redacted again."));

        final ai.philterd.workspace.model.Document done = drainUntilDone(doc.getId());
        assertFalse(java.util.List.of("FAILED", "PENDING", "PROCESSING").contains(done.getStatus()), done.getStatus());
        assertNull(done.getFailureMessage(), "the earlier failure is cleared");
        assertTrue(spanRepository.findByDocumentId(doc.getId()).stream()
                .anyMatch(span -> "jdoe@example.com".equals(span.getText())));
        assertTrue(pendingUploadRepository.findById(doc.getId()).isEmpty());
        assertNotNull(done.getPhilterDocumentId(), "Philter's document ID is stored");
        // Philter records a redaction under a UUID.
        assertTrue(done.getPhilterDocumentId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                done.getPhilterDocumentId());

        // The review page shows administrators the ID, and a section that reads its ledger chain (#18).
        final String page = mockMvc.perform(get("/review/" + doc.getId()).session(session).with(from(address)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains(done.getPhilterDocumentId()), "the ID is shown");
        assertTrue(page.contains("id=\"ledgerSection\" data-document-id=\"" + doc.getId() + "\""), "the ledger section");
        final String chain = mockMvc.perform(get("/api/v1/documents/" + doc.getId() + "/ledger")
                        .session(session).with(from(address)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(chain.contains("\"philterDocumentId\":\"" + done.getPhilterDocumentId() + "\"")
                && chain.contains("\"verified\":true"), chain);
    }

    /** Drains the queue until the document leaves PENDING and PROCESSING, and returns it. */
    private ai.philterd.workspace.model.Document drainUntilDone(final String id) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 120_000;
        ai.philterd.workspace.model.Document d = documentRepository.findById(id).orElseThrow();
        while (System.currentTimeMillis() < deadline
                && ("PENDING".equals(d.getStatus()) || "PROCESSING".equals(d.getStatus()))) {
            ingestQueueService.drain();
            Thread.sleep(250);
            d = documentRepository.findById(id).orElseThrow();
        }
        return d;
    }

    @Test
    void philterDeliversItsSignedWebhookToPhilterWorkspace() throws Exception {
        final MockHttpSession session = new MockHttpSession();
        final String address = newAddress();
        signIn(session, address, "admin", ADMIN_PASSWORD);
        final String webhookUrl = "http://host.testcontainers.internal:" + PORT + "/api/philter/webhook";
        // Philter refuses private addresses unless an administrator allowlists both the host and the
        // address it resolves to, as a deployment on a private network must.
        final String resolved = PHILTER.execInContainer("getent", "hosts", "host.testcontainers.internal")
                .getStdout().trim().split("\\s+")[0];
        final ai.philterd.philter.model.AdminSettings before = bootstrap().getAdminSettings();
        final ai.philterd.philter.model.UpdateAdminSettingsRequest allow = new ai.philterd.philter.model.UpdateAdminSettingsRequest();
        allow.setWebhookAllowlist("host.testcontainers.internal," + resolved);
        bootstrap().updateAdminSettings(allow);
        try {
            assertTrue(mockMvc.perform(get("/admin/philter").session(session).with(from(address)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString().contains("Not registered"));

            mockMvc.perform(post("/admin/philter/webhook/register").session(session).with(csrf()).with(from(address)))
                    .andExpect(flash().attribute("success", "Philter will send its webhooks to " + webhookUrl
                            + ". Send a test event to check that they arrive."));
            assertTrue(mockMvc.perform(get("/admin/philter").session(session).with(from(address)))
                    .andReturn().getResponse().getContentAsString().contains("<span>Registered</span>"));

            // Philter signs a test event with the secret and delivers it over HTTP.
            final long received = webhookDeliveries.count();
            final String delivered = (String) mockMvc.perform(post("/admin/philter/webhook/test").session(session)
                            .with(csrf()).with(from(address)))
                    .andReturn().getFlashMap().get("success");
            assertNotNull(delivered, "Philter delivered the test event");
            assertTrue(delivered.startsWith("Philter delivered a test event to Philter Workspace"), delivered);
            assertEquals(received + 1, webhookDeliveries.count());
            assertTrue(webhookDeliveries.findAll().stream().anyMatch(d -> "WEBHOOK_TEST".equals(d.getEvent())));

            // Signed with another secret, the delivery is refused, and the page says why.
            bootstrap().setWebhook(webhookUrl, "a-different-secret-" + HexFormat.of().formatHex(bytes(8)));
            mockMvc.perform(post("/admin/philter/webhook/test").session(session).with(csrf()).with(from(address)))
                    .andExpect(flash().attribute("error", "Philter Workspace refused the test event's signature: the "
                            + "secret Philter holds is not PHILTER_WORKSPACE_PHILTER_WEBHOOK_SECRET. Register the webhook again."));
            assertEquals(received + 1, webhookDeliveries.count(), "the refused delivery is not recorded");
        } finally {
            bootstrap().removeWebhook();
            final ai.philterd.philter.model.UpdateAdminSettingsRequest restore = new ai.philterd.philter.model.UpdateAdminSettingsRequest();
            restore.setWebhookAllowlist(before.getWebhookAllowlist() == null ? "" : before.getWebhookAllowlist());
            bootstrap().updateAdminSettings(restore);
        }
    }

    /**
     * Philter Workspace draws redaction boxes on PDFs with the same Phileas that Philter detects with. When the
     * Philter image these tests run is moved to a release with another Phileas, this fails until
     * {@code phileas.version} in pom.xml is moved too.
     */
    @Test
    void philterWorkspaceUsesThePhileasVersionPhilterUses() throws Exception {
        final java.util.Properties ours = new java.util.Properties();
        try (java.io.InputStream in = ai.philterd.phileas.PhileasConfiguration.class
                .getResourceAsStream("/META-INF/maven/ai.philterd/phileas/pom.properties")) {
            assertNotNull(in, "Phileas's Maven metadata is on the classpath");
            ours.load(in);
        }
        final String philters = PHILTER.copyFileFromContainer("/opt/philter/philter.jar", in -> {
            try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(in)) {
                for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    final java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("BOOT-INF/lib/phileas-(\\d[^/]*)\\.jar").matcher(e.getName());
                    if (m.matches()) {
                        return m.group(1);
                    }
                }
            }
            return null;
        });
        assertNotNull(philters, "Philter's jar bundles Phileas");
        assertEquals(philters, ours.getProperty("version"), "Philter " + PHILTER.getDockerImageName()
                + " uses Phileas " + philters + "; set phileas.version in pom.xml to match");
    }
}
