/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.workspace.philter;

import ai.philterd.workspace.core.model.Redaction;
import ai.philterd.workspace.core.model.RedactionResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhilterRedactionsTest {

    private static final String TEXT = "SSN 123-45-6789 here";

    private static final String EXPLAIN_BODY = """
            {"filteredText":"SSN {{{REDACTED-ssn}}} here","context":"ctx","documentId":"d1",
             "explanation":{"appliedSpans":[{"characterStart":4,"characterEnd":15,"filterType":"SSN",
             "confidence":0.9,"text":"123-45-6789"}],"ignoredSpans":[]}}
            """;

    private HttpServer server;
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private int status = 200;
    private String explainBody = EXPLAIN_BODY;
    private String documentIdHeader = "6543210fedcba9876543210f";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/explain", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            final byte[] body = (status == 200 ? explainBody : "{\"message\":\"no\",\"reason\":\"policy_not_found\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (documentIdHeader != null) {
                exchange.getResponseHeaders().add("X-Document-Id", documentIdHeader);
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Redacts TEXT through the SDK client, as Philter Workspace does. */
    private RedactionResponse redact(final String apiKey, final String policy, final String context)
            throws IOException {
        return PhilterRedactions.redact(PhilterClients.build("http://127.0.0.1:" + server.getAddress().getPort(),
                apiKey, PhilterTlsConfig.none()), TEXT, policy, context);
    }

    @Test
    void theSdkSendsPolicyContextAndBearerKey() throws IOException {
        final RedactionResponse result = redact("secret", "claims", "ctx");

        assertTrue(query.get().contains("p=claims"));
        assertTrue(query.get().contains("c=ctx"));
        assertEquals("Bearer secret", authorization.get());
        assertEquals(1, result.getRedactions().size());
        final Redaction r = result.getRedactions().get(0);
        assertEquals("ssn", r.getType());
        assertEquals(0.9, r.getConfidence());
    }

    @Test
    void blankPolicyAndContextAreOmitted() throws IOException {
        redact(null, " ", "");

        assertTrue(query.get() == null || !query.get().contains("p="));
        assertTrue(query.get() == null || !query.get().contains("c="));
        assertEquals(null, authorization.get());
    }

    @Test
    void redactBuildsRedactionsInFinalText() throws IOException {
        final RedactionResponse response = redact(null, null, "ctx");

        assertEquals("SSN SSN here", response.getRedactedText());
        assertEquals(1, response.getRedactions().size());
        final Redaction r = response.getRedactions().get(0);
        assertEquals(4, r.getStart());
        assertEquals(7, r.getEnd());
        assertEquals("123-45-6789", r.getText());
    }

    @Test
    void redactCarriesThePolicyAndVersionPhilterReportsApplying() throws IOException {
        // Philter 4.0 adds them to the explain body (#16); a Philter that does not leaves them unset.
        explainBody = EXPLAIN_BODY.trim().replaceFirst("\\}$",
                ",\"policyName\":\"civil-filings\",\"policyVersion\":3,\"policyContentHash\":\"abc\"}");
        final ai.philterd.workspace.core.model.RedactionResponse r = redact("secret", "claims", "ctx");
        assertEquals("civil-filings", r.getAppliedPolicyName());
        assertEquals(3, r.getAppliedPolicyVersion());

        explainBody = EXPLAIN_BODY;
        final ai.philterd.workspace.core.model.RedactionResponse older = redact("secret", "claims", "ctx");
        assertEquals(null, older.getAppliedPolicyName());
        assertEquals(null, older.getAppliedPolicyVersion());
    }

    @Test
    void redactCarriesTheDocumentIdPhilterRecordedItUnder() throws IOException {
        // Philter sends the ID in the X-Document-Id header; the body's documentId is not it.
        assertEquals("6543210fedcba9876543210f", redact("secret", "claims", "ctx").getPhilterDocumentId());

        documentIdHeader = null;
        assertEquals(null, redact("secret", "claims", "ctx").getPhilterDocumentId());
    }

    @Test
    void failureMessageCarriesStatusButNotResponseText() {
        status = 404;
        final IOException e = assertThrows(IOException.class, () -> redact(null, "missing", "ctx"));

        assertTrue(e.getMessage().contains("HTTP 404"));
        assertTrue(e.getMessage().contains("policy_not_found"));
        assertFalse(e.getMessage().contains("no\""));
        assertEquals(null, e.getCause());
    }

    @Test
    void philterEnumNamesMapToPhileasTypes() {
        assertEquals("ip-address", PhilterRedactions.type(span("IP_ADDRESS")));
        assertEquals("id", PhilterRedactions.type(span("IDENTIFIER")));
        assertEquals("person", PhilterRedactions.type(span("PERSON")));
        assertEquals("new-type", PhilterRedactions.type(span("NEW_TYPE")));
        assertEquals("other", PhilterRedactions.type(span(null)));
    }

    private static ai.philterd.philter.model.Span span(final String filterType) {
        final ai.philterd.philter.model.Span span = new ai.philterd.philter.model.Span();
        span.setFilterType(filterType);
        return span;
    }
}
