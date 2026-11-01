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
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.LlmJudgeDefaults;
import ai.philterd.workspace.model.OllamaInstance;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.OllamaInstanceRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.service.DataSourceHostAllowList;
import ai.philterd.workspace.service.DocumentAccessService;
import ai.philterd.workspace.service.LlmJudgeDefaultsService;
import ai.philterd.workspace.service.UserGroupsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/v1")
public class LlmJudgeController {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeController.class);

    private static final String EXPLAIN_PROMPT = "Explain the risk of the PII in this document.";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MODELS_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration GENERATE_TIMEOUT = Duration.ofMinutes(3);

    private final OllamaInstanceRepository ollamaInstanceRepository;
    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final UserGroupsService userGroupsService;
    private final DocumentAccessService documentAccessService;
    private final AuditLogService auditLogService;
    private final LlmJudgeDefaultsService llmJudgeDefaultsService;
    private final ObjectMapper objectMapper;
    private final DataSourceHostAllowList hostAllowList;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public LlmJudgeController(final OllamaInstanceRepository ollamaInstanceRepository,
                              final DocumentRepository documentRepository,
                              final SpanRepository spanRepository,
                              final UserGroupsService userGroupsService,
                              final DocumentAccessService documentAccessService,
                              final AuditLogService auditLogService,
                              final LlmJudgeDefaultsService llmJudgeDefaultsService,
                              final ObjectMapper objectMapper,
                              final DataSourceHostAllowList hostAllowList) {
        this.ollamaInstanceRepository = ollamaInstanceRepository;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.userGroupsService = userGroupsService;
        this.documentAccessService = documentAccessService;
        this.auditLogService = auditLogService;
        this.llmJudgeDefaultsService = llmJudgeDefaultsService;
        this.objectMapper = objectMapper;
        this.hostAllowList = hostAllowList;
    }

    @GetMapping("/ollama/{instanceId}/models")
    public Map<String, Object> listModels(@PathVariable final String instanceId,
                                          final Authentication authentication) {
        // Run the role/default check *before* loading the instance from Mongo so a
        // reviewer probing arbitrary ids gets the same uniform 404 whether the id
        // exists or not. The previous shape returned 404 on miss but 403 on
        // access-denied, letting a reviewer enumerate every registered Ollama
        // instance by status code alone.
        requireListModelsAccess(authentication, instanceId);
        final OllamaInstance instance = ollamaInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ollama instance not found."));
        final String base = requireAllowedBaseUrl(instance);
        try {
            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/tags"))
                    .timeout(MODELS_TIMEOUT)
                    .GET()
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(BAD_GATEWAY, "Ollama instance unavailable.");
            }
            final JsonNode root = objectMapper.readTree(resp.body());
            final List<String> names = new ArrayList<>();
            final JsonNode models = root.get("models");
            if (models != null && models.isArray()) {
                for (JsonNode m : models) {
                    final JsonNode name = m.get("name");
                    if (name != null && !name.asString("").isBlank()) {
                        names.add(name.asString(""));
                    }
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("instanceId", instance.getId());
            out.put("instanceName", instance.getName());
            out.put("models", names);
            return out;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // The upstream-error log line keeps the URL + reason for operators; the
            // response body is generic so it doesn't echo the instance name back to
            // an authorized-but-probing caller (the audit case from finding #11).
            log.warn("Failed to list models from {}: {}", base, e.getMessage());
            throw new ResponseStatusException(BAD_GATEWAY, "Ollama instance unavailable.");
        }
    }

    public record ExplainRequest(String instanceId, String model) {}

    @PostMapping(value = "/documents/{documentId}/explain",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> explain(@PathVariable final String documentId,
                                       @RequestBody final ExplainRequest request,
                                       final Authentication authentication) {
        if (request == null || request.instanceId() == null || request.instanceId().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "instanceId is required.");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "model is required.");
        }

        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found."));
        documentAccessService.requireDocumentAccess(authentication, document);

        // Same indistinguishability rule as listModels: refuse access against the bare
        // id before the DB lookup so a probing caller can't tell which arbitrary ids
        // resolve to real rows.
        requireListModelsAccess(authentication, request.instanceId());
        final OllamaInstance instance = ollamaInstanceRepository.findById(request.instanceId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ollama instance not found."));
        // Reject before auditing — a disallowed-host attempt isn't "PII sent to LLM" since
        // no PII actually leaves the process.
        final String base = requireAllowedBaseUrl(instance);

        final List<Span> spans = spanRepository.findByDocumentId(documentId);
        final String prompt = buildPrompt(document, spans);

        try {
            final ObjectNode body = objectMapper.createObjectNode();
            body.put("model", request.model());
            body.put("prompt", prompt);
            body.put("stream", false);

            // Audit before the HTTP call so the record exists even if Ollama returns an error
            // or the connection fails. PII leaves the system at this point.
            auditLogService.log("DOCUMENT_PII_SENT_TO_LLM", "Document", documentId,
                    Map.of("instanceId", instance.getId(),
                            "instanceName", instance.getName() == null ? "" : instance.getName(),
                            "model", request.model(),
                            "spanCount", spans.size()));

            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/generate"))
                    .timeout(GENERATE_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Ollama instance unavailable.");
            }
            final JsonNode root = objectMapper.readTree(resp.body());
            final String response = root.path("response").asString("");

            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("instanceName", instance.getName());
            out.put("model", request.model());
            out.put("response", response);
            return out;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // Classes only: the error can quote the model's reply, which can quote the document (#77).
            log.warn("Failed to call Ollama at {}: {}", base, ai.philterd.workspace.service.ErrorClasses.of(e));
            throw new ResponseStatusException(BAD_GATEWAY,
                    "Ollama instance unavailable.");
        }
    }

    /**
     * {@code consumes = application/json} is a CSRF defence — a cross-site
     * simple form POST sends form-urlencoded data, which the JSON content-type
     * requirement rejects before any side effects fire. The review-page fetch
     * already sets the JSON content type.
     */
    @PostMapping(value = "/spans/{spanId}/second-opinion",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> secondOpinion(@PathVariable final String spanId,
                                             final Authentication authentication) {
        final Span span = spanRepository.findById(spanId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Span not found."));
        final Document document = documentAccessService.loadAccessibleParentForSpan(span, authentication);

        final LlmJudgeDefaults defaults = llmJudgeDefaultsService.load();
        if (defaults.getSecondOpinionInstanceId() == null) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "No default Ollama instance is configured for Second Opinion. "
                            + "An administrator must set one under Admin → LLM-as-a-Judge.");
        }
        final OllamaInstance instance = ollamaInstanceRepository.findById(defaults.getSecondOpinionInstanceId())
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST,
                        "The configured Second Opinion Ollama instance no longer exists."));
        // Validate once and reuse — pickFirstModel and the generate call both need the URL.
        final String base = requireAllowedBaseUrl(instance);

        String model = defaults.getSecondOpinionModel();
        if (model == null || model.isBlank()) {
            model = pickFirstModel(instance, base);
        }
        final String prompt = buildSecondOpinionPrompt(span, document);

        try {
            final ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);

            // Audit before the HTTP call so the record exists even if Ollama returns an error
            // or the connection fails. PII leaves the system at this point.
            auditLogService.log("DOCUMENT_PII_SENT_TO_LLM", "Document", document.getId(),
                    Map.of("instanceId", instance.getId(),
                            "instanceName", instance.getName() == null ? "" : instance.getName(),
                            "model", model,
                            "spanId", span.getId(),
                            "spanType", span.getType() == null ? "" : span.getType()));

            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/generate"))
                    .timeout(GENERATE_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Ollama instance unavailable.");
            }
            final JsonNode root = objectMapper.readTree(resp.body());
            final String response = root.path("response").asString("");

            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("instanceName", instance.getName());
            out.put("model", model);
            out.put("sourceText", span.getText());
            out.put("sourceType", span.getType());
            out.put("response", response);
            return out;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // Classes only: the error can quote the model's reply, which can quote the document (#77).
            log.warn("Failed to call Ollama at {}: {}", base, ai.philterd.workspace.service.ErrorClasses.of(e));
            throw new ResponseStatusException(BAD_GATEWAY,
                    "Ollama instance unavailable.");
        }
    }

    private String pickFirstModel(final OllamaInstance instance, final String base) {
        try {
            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/tags"))
                    .timeout(MODELS_TIMEOUT)
                    .GET()
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Ollama instance unavailable.");
            }
            final JsonNode models = objectMapper.readTree(resp.body()).get("models");
            if (models == null || !models.isArray() || models.isEmpty()) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Default Ollama instance has no models installed.");
            }
            final List<String> names = new ArrayList<>();
            for (JsonNode m : models) {
                final JsonNode name = m.get("name");
                if (name != null && !name.asString("").isBlank()) names.add(name.asString(""));
            }
            if (names.isEmpty()) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Default Ollama instance has no usable models.");
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names.get(0);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to query models from {}: {}", base, e.getMessage());
            throw new ResponseStatusException(BAD_GATEWAY,
                    "Ollama instance unavailable.");
        }
    }

    private static String buildSecondOpinionPrompt(final Span span, final Document document) {
        final StringBuilder sb = new StringBuilder(4096);
        sb.append("You are reviewing a single PII redaction decision.\n\n");
        sb.append("The text \"")
                .append(span.getText() == null ? "" : span.getText())
                .append("\" was identified as PII of type \"")
                .append(span.getType() == null ? "" : span.getType())
                .append("\" by an automated redactor (confidence ")
                .append(String.format("%.2f", span.getConfidence()))
                .append(").\n\n");
        sb.append("Document context:\n");
        sb.append(document.getOriginalText() == null ? "" : document.getOriginalText());
        sb.append("\n\nIs the marked text genuinely personally identifiable information of type \"")
                .append(span.getType() == null ? "" : span.getType())
                .append("\", or might it be a false positive? Briefly explain your reasoning.\n");
        return sb.toString();
    }

    // ----- helpers -----

    private static String baseUrl(final OllamaInstance instance) {
        String host = instance.getEndpoint();
        if (host == null) host = "localhost";
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        return host + ":" + instance.getPort();
    }

    /**
     * Re-validate a stored Ollama endpoint against the data-source allow-list before
     * making an outbound call. The save-time check in {@code AdminOllamaController} can
     * become stale (allow-list reconfigured, default-deny tightened); refusing here keeps
     * the live policy authoritative.
     */
    private String requireAllowedBaseUrl(final OllamaInstance instance) {
        final String url = baseUrl(instance);
        if (!hostAllowList.isAllowed(url)) {
            // Don't echo the instance name in the response body — same finding-#11
            // reasoning. The operator gets the full host + URL in the warning log.
            log.warn("Ollama instance \"{}\" host {} is not on the data-source allow-list",
                    instance.getName(), url);
            throw new ResponseStatusException(BAD_GATEWAY, "Ollama instance unavailable.");
        }
        return url;
    }

    private static String buildPrompt(final Document document, final List<Span> spans) {
        final StringBuilder sb = new StringBuilder(8192);
        sb.append("You are reviewing a document for personally identifiable information (PII).\n\n");
        sb.append("Document:\n");
        sb.append(document.getOriginalText() == null ? "" : document.getOriginalText());
        sb.append("\n\nDetected PII:\n");
        if (spans == null || spans.isEmpty()) {
            sb.append("(none)\n");
        } else {
            spans.stream()
                    .sorted(Comparator.comparingInt(s -> s.getLocation() == null ? 0
                            : s.getLocation().characterStart()))
                    .forEach(s -> sb.append("- ")
                            .append(s.getType() == null ? "" : s.getType())
                            .append(": \"")
                            .append(s.getText() == null ? "" : s.getText())
                            .append("\" (confidence ")
                            .append(String.format("%.2f", s.getConfidence()))
                            .append(", status ")
                            .append(s.getStatus() == null ? "" : s.getStatus())
                            .append(")\n"));
        }
        sb.append('\n').append(EXPLAIN_PROMPT).append('\n');
        return sb.toString();
    }

    /**
     * Gate {@code GET /api/v1/ollama/{instanceId}/models} so only the callers who actually need
     * it can probe outbound HTTP. Without this gate, any authenticated user (or API-key holder)
     * could supply any registered Ollama instance id and force Philter Workspace to make an outbound
     * {@code GET /api/tags} call to the configured URL — an SSRF-adjacent surface for
     * internal-only Ollama URLs.
     *
     * <p>Allowed callers:
     * <ul>
     *   <li>Admins — for the Admin → LLM-as-a-Judge configuration page where they pick a
     *       model from a freshly-registered instance.
     *   <li>Group-scoped reviewers, but <em>only</em> when the requested instance is one of
     *       the configured Explain or Second-Opinion defaults — i.e. an instance the review
     *       page legitimately needs the model list for. Reviewers with no group membership
     *       (no batches they can see) are rejected outright.
     * </ul>
     */
    private void requireListModelsAccess(final Authentication auth, final String requestedInstanceId) {
        if (AuthUtils.isAdmin(auth)) return;

        // Every reject path below throws the same uniform 404 "Ollama instance not
        // found." that the bare lookup miss returns — finding #11. A probing reviewer
        // cannot distinguish "instance doesn't exist", "I'm not in any group", and
        // "instance exists but isn't a configured default" by status code or body.
        final ResponseStatusException notFound = new ResponseStatusException(
                NOT_FOUND, "Ollama instance not found.");

        // Reviewer must have at least one accessible batch — that proves they're an active
        // user, not a stranded account or API key with no real role in the system.
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        if (myGroupIds.isEmpty()) {
            throw notFound;
        }

        // The instance must be a configured LLM-as-a-Judge default. Reviewers cannot probe
        // arbitrary Ollama URLs the admin happened to register but isn't actively using.
        final ai.philterd.workspace.model.LlmJudgeDefaults defaults = llmJudgeDefaultsService.load();
        final boolean isExplainDefault = requestedInstanceId != null
                && requestedInstanceId.equals(defaults.getExplainInstanceId());
        final boolean isSecondOpinionDefault = requestedInstanceId != null
                && requestedInstanceId.equals(defaults.getSecondOpinionInstanceId());
        if (!isExplainDefault && !isSecondOpinionDefault) {
            throw notFound;
        }
    }

}
