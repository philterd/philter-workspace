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

import ai.philterd.workspace.model.LlmJudgeDefaults;
import ai.philterd.workspace.model.OllamaInstance;
import ai.philterd.workspace.repository.OllamaInstanceRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.DataSourceHostAllowList;
import ai.philterd.workspace.service.LlmJudgeDefaultsService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.net.URI;
import java.time.LocalDateTime;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/admin/llm-judge")
public class AdminOllamaController {

    private static final Duration TEST_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TEST_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final OllamaInstanceRepository repository;
    private final AuditLogService auditLogService;
    private final LlmJudgeDefaultsService defaultsService;
    private final DataSourceHostAllowList hostAllowList;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TEST_CONNECT_TIMEOUT)
            .build();

    public AdminOllamaController(final OllamaInstanceRepository repository,
                                 final AuditLogService auditLogService,
                                 final LlmJudgeDefaultsService defaultsService,
                                 final DataSourceHostAllowList hostAllowList) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.defaultsService = defaultsService;
        this.hostAllowList = hostAllowList;
    }

    @GetMapping
    public String list(final Model model) {
        final List<OllamaInstance> instances = repository.findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("instances", instances);
        // Instances reached over plain HTTP on another machine, where document text crosses the network unencrypted.
        model.addAttribute("plainHttpInstanceIds", instances.stream()
                .filter(i -> ai.philterd.workspace.webapp.ui.PlainHttp.toAnotherHost(baseUrl(i)))
                .map(OllamaInstance::getId).collect(java.util.stream.Collectors.toSet()));
        model.addAttribute("defaults", defaultsService.load());
        return "admin-llm-judge";
    }

    @PostMapping("/defaults")
    public String setDefaults(@RequestParam(value = "explainInstanceId", required = false) final String explainInstanceId,
                              @RequestParam(value = "explainModel", required = false) final String explainModel,
                              @RequestParam(value = "secondOpinionInstanceId", required = false) final String secondOpinionInstanceId,
                              @RequestParam(value = "secondOpinionModel", required = false) final String secondOpinionModel,
                              final RedirectAttributes redirectAttributes) {
        final LlmJudgeDefaults defaults = defaultsService.load();

        defaults.setExplainInstanceId(normalize(explainInstanceId));
        defaults.setExplainModel(normalize(explainModel));
        defaults.setSecondOpinionInstanceId(normalize(secondOpinionInstanceId));
        defaults.setSecondOpinionModel(normalize(secondOpinionModel));

        // Drop a model if its instance was cleared.
        if (defaults.getExplainInstanceId() == null) defaults.setExplainModel(null);
        if (defaults.getSecondOpinionInstanceId() == null) defaults.setSecondOpinionModel(null);

        // Validate referenced instances actually exist.
        if (defaults.getExplainInstanceId() != null
                && !repository.existsById(defaults.getExplainInstanceId())) {
            redirectAttributes.addFlashAttribute("error",
                    "Selected Explain instance no longer exists.");
            return "redirect:/admin/llm-judge";
        }
        if (defaults.getSecondOpinionInstanceId() != null
                && !repository.existsById(defaults.getSecondOpinionInstanceId())) {
            redirectAttributes.addFlashAttribute("error",
                    "Selected Second Opinion instance no longer exists.");
            return "redirect:/admin/llm-judge";
        }

        defaultsService.save(defaults);
        auditLogService.log("LLM_JUDGE_DEFAULTS_CHANGE", "Settings",
                LlmJudgeDefaults.SINGLETON_ID,
                Map.of(
                        "explainInstanceId", str(defaults.getExplainInstanceId()),
                        "explainModel", str(defaults.getExplainModel()),
                        "secondOpinionInstanceId", str(defaults.getSecondOpinionInstanceId()),
                        "secondOpinionModel", str(defaults.getSecondOpinionModel())));
        redirectAttributes.addFlashAttribute("success", "LLM-as-a-Judge defaults saved.");
        return "redirect:/admin/llm-judge";
    }

    private static String normalize(final String s) {
        if (s == null) return null;
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String str(final String s) {
        return s == null ? "" : s;
    }

    @PostMapping
    public String create(@RequestParam("name") final String name,
                         @RequestParam("endpoint") final String endpoint,
                         @RequestParam("port") final int port,
                         final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/llm-judge";
        }
        if (trimmedEndpoint.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Endpoint is required.");
            return "redirect:/admin/llm-judge";
        }
        if (port < 1 || port > 65535) {
            redirectAttributes.addFlashAttribute("error", "Port must be between 1 and 65535.");
            return "redirect:/admin/llm-judge";
        }
        if (repository.findByName(trimmedName).isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "An Ollama instance named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/llm-judge";
        }

        final String instanceUrl = buildInstanceUrl(trimmedEndpoint, port);
        if (!hostAllowList.isAllowed(instanceUrl)) {
            redirectAttributes.addFlashAttribute("error",
                    "Endpoint host is not permitted. Private network addresses (loopback, "
                            + "RFC-1918, link-local) are blocked by default. To allow an internal "
                            + "host, add it to philter-workspace.data-sources.allowed-hosts.");
            return "redirect:/admin/llm-judge";
        }

        final OllamaInstance instance = new OllamaInstance();
        instance.setCreatedAt(LocalDateTime.now());
        instance.setId(UUID.randomUUID().toString());
        instance.setName(trimmedName);
        instance.setEndpoint(trimmedEndpoint);
        instance.setPort(port);

        try {
            repository.save(instance);
        } catch (DuplicateKeyException e) {
            // Race against another admin creating the same name concurrently.
            redirectAttributes.addFlashAttribute("error",
                    "An Ollama instance named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/llm-judge";
        }

        auditLogService.log("OLLAMA_INSTANCE_CREATE", "OllamaInstance", instance.getId(),
                Map.of("name", trimmedName, "endpoint", trimmedEndpoint, "port", port));
        redirectAttributes.addFlashAttribute("success",
                "Ollama instance \"" + trimmedName + "\" added.");
        if (ai.philterd.workspace.webapp.ui.PlainHttp.toAnotherHost(instanceUrl)) {
            redirectAttributes.addFlashAttribute("warning", PLAIN_HTTP_WARNING);
        }
        return "redirect:/admin/llm-judge";
    }

    /** Why plain HTTP to another machine is a risk, and what to do instead (#85). */
    static final String PLAIN_HTTP_WARNING = "This Ollama instance is on another machine and is reached over plain"
            + " HTTP, so the document text and detected values sent to it cross the network unencrypted. Put HTTPS in"
            + " front of it, and enter its endpoint as https://.";

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final OllamaInstance instance = repository.findById(id).orElse(null);
        if (instance == null) {
            redirectAttributes.addFlashAttribute("error", "Ollama instance not found.");
            return "redirect:/admin/llm-judge";
        }
        repository.deleteById(id);
        auditLogService.log("OLLAMA_INSTANCE_DELETE", "OllamaInstance", id,
                Map.of("name", instance.getName() == null ? "" : instance.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Ollama instance \"" + instance.getName() + "\" removed.");
        return "redirect:/admin/llm-judge";
    }


    @PostMapping("/{id}/test")
    public String test(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final OllamaInstance instance = repository.findById(id).orElse(null);
        if (instance == null) {
            redirectAttributes.addFlashAttribute("error", "Ollama instance not found.");
            return "redirect:/admin/llm-judge";
        }

        final String url = baseUrl(instance) + "/api";
        if (!hostAllowList.isAllowed(url)) {
            redirectAttributes.addFlashAttribute("error",
                    "Endpoint host is not permitted. Update or remove this instance.");
            return "redirect:/admin/llm-judge";
        }
        boolean ok = false;
        int status = 0;
        String detail;
        try {
            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TEST_REQUEST_TIMEOUT)
                    .GET()
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            status = resp.statusCode();
            ok = true;
            detail = "HTTP " + status;
        } catch (Exception e) {
            detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }

        auditLogService.log("OLLAMA_INSTANCE_TEST", "OllamaInstance", id,
                Map.of("name", instance.getName() == null ? "" : instance.getName(),
                        "url", url,
                        "ok", ok,
                        "status", status,
                        "detail", detail));

        if (ok) {
            redirectAttributes.addFlashAttribute("success",
                    "Ollama instance \"" + instance.getName() + "\" responded ("
                            + detail + ") at " + url + ".");
        } else {
            redirectAttributes.addFlashAttribute("error",
                    "Ollama instance \"" + instance.getName() + "\" did not respond at "
                            + url + ": " + detail);
        }
        return "redirect:/admin/llm-judge";
    }

    private static String baseUrl(final OllamaInstance instance) {
        return buildInstanceUrl(
                instance.getEndpoint() == null ? "localhost" : instance.getEndpoint(),
                instance.getPort());
    }

    private static String buildInstanceUrl(final String endpoint, final int port) {
        String host = endpoint == null ? "localhost" : endpoint;
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        return host + ":" + port;
    }
}
