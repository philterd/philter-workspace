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

import ai.philterd.workspace.model.ElasticsearchDataSource;
import ai.philterd.workspace.model.LocalDirectoryDataSource;
import ai.philterd.workspace.model.OpenSearchDataSource;
import ai.philterd.workspace.model.RelationalDbDataSource;
import ai.philterd.workspace.model.S3DataSource;
import ai.philterd.workspace.model.BackgroundJob;
import ai.philterd.workspace.repository.BackgroundJobRepository;
import ai.philterd.workspace.repository.ElasticsearchDataSourceRepository;
import ai.philterd.workspace.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.workspace.repository.OpenSearchDataSourceRepository;
import ai.philterd.workspace.repository.RelationalDbDataSourceRepository;
import ai.philterd.workspace.repository.S3DataSourceRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.RdbPreviewService;
import ai.philterd.workspace.service.SymmetricCipher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/admin/data-sources")
public class AdminDataSourceController {

    private final OpenSearchDataSourceRepository repository;
    private final ElasticsearchDataSourceRepository esRepository;
    private final S3DataSourceRepository s3Repository;
    private final RelationalDbDataSourceRepository rdbRepository;
    private final LocalDirectoryDataSourceRepository localRepository;
    private final BackgroundJobRepository backgroundJobRepository;
    private final AuditLogService auditLogService;
    private final SymmetricCipher cipher;
    private final ObjectMapper objectMapper;
    private final ai.philterd.workspace.service.DataSourceHostAllowList hostAllowList;
    private final ai.philterd.workspace.service.JdbcUrlValidator jdbcUrlValidator;
    private final RdbPreviewService rdbPreviewService;
    private static final String ENDPOINT_NOT_ALLOWED = "Endpoint host is not permitted. Private network addresses"
            + " (loopback, RFC-1918, link-local, carrier-grade NAT, and IPv6 forms of them) are blocked by default, and"
            + " when philter-workspace.data-sources.allowed-hosts is set, the host must be on it.";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public AdminDataSourceController(final OpenSearchDataSourceRepository repository,
                                     final ElasticsearchDataSourceRepository esRepository,
                                     final S3DataSourceRepository s3Repository,
                                     final RelationalDbDataSourceRepository rdbRepository,
                                     final LocalDirectoryDataSourceRepository localRepository,
                                     final BackgroundJobRepository backgroundJobRepository,
                                     final AuditLogService auditLogService,
                                     final SymmetricCipher cipher,
                                     final ObjectMapper objectMapper,
                                     final ai.philterd.workspace.service.DataSourceHostAllowList hostAllowList,
                                     final ai.philterd.workspace.service.JdbcUrlValidator jdbcUrlValidator,
                                     final RdbPreviewService rdbPreviewService) {
        this.repository = repository;
        this.esRepository = esRepository;
        this.s3Repository = s3Repository;
        this.rdbRepository = rdbRepository;
        this.localRepository = localRepository;
        this.backgroundJobRepository = backgroundJobRepository;
        this.auditLogService = auditLogService;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.hostAllowList = hostAllowList;
        this.jdbcUrlValidator = jdbcUrlValidator;
        this.rdbPreviewService = rdbPreviewService;
    }

    /**
     * Template-facing projection of {@link RelationalDbDataSource}. The {@code jdbcUrl}
     * field carries the <em>decrypted</em> URL so the Data Sources page can render it
     * in the listing row and stamp it onto the Edit modal's data-attribute. The
     * encrypted username/password are passed through verbatim — the template only
     * tests them for emptiness ("are credentials stored?") and never displays them.
     */
    public record RelationalDbDataSourceView(
            String id,
            String name,
            String jdbcUrl,
            String sqlQuery,
            String encryptedUsername,
            String encryptedPassword,
            java.time.LocalDateTime createdAt,
            String watermarkColumn,
            String lastImportedKey,
            java.time.Instant lastImportedAt) {}

    private RelationalDbDataSourceView toRdbView(final RelationalDbDataSource r) {
        // Decryption can throw on a tampered / out-of-sync ciphertext. Render an
        // explicit marker instead of crashing the whole listing — operators can
        // then identify and re-create the bad row.
        String url;
        try {
            url = r.getEncryptedJdbcUrl() == null || r.getEncryptedJdbcUrl().isEmpty()
                    ? "" : cipher.decrypt(r.getEncryptedJdbcUrl());
        } catch (RuntimeException e) {
            url = "(unable to decrypt — re-save this data source)";
        }
        return new RelationalDbDataSourceView(
                r.getId(), r.getName(), url, r.getSqlQuery(),
                r.getEncryptedUsername(), r.getEncryptedPassword(), r.getCreatedAt(),
                r.getWatermarkColumn(), r.getLastImportedKey(), r.getLastImportedAt());
    }

    @GetMapping
    public String list(final Model model) {
        final List<OpenSearchDataSource> sources = repository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("dataSources", sources);

        final List<ElasticsearchDataSource> esSources = esRepository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("esDataSources", esSources);

        final List<S3DataSource> s3Sources = s3Repository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("s3DataSources", s3Sources);

        final List<RelationalDbDataSource> rdbSources = rdbRepository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        // Map the entities to a view record that exposes the decrypted JDBC URL —
        // the template displays it in the listing row and stamps it onto the Edit
        // button's data-attribute for the modal prefill. Decryption happens once
        // here so the template never sees ciphertext (and so a future change can't
        // accidentally render the encrypted blob to the page).
        final List<RelationalDbDataSourceView> rdbView = rdbSources.stream()
                .map(this::toRdbView)
                .toList();
        model.addAttribute("rdbDataSources", rdbView);

        final List<LocalDirectoryDataSource> localSources = localRepository
                .findAll(PageRequest.of(0, 500, Sort.by("name"))).getContent();
        model.addAttribute("localDataSources", localSources);

        return "admin-data-sources";
    }

    @PostMapping
    public String create(@RequestParam("name") final String name,
                         @RequestParam("endpoint") final String endpoint,
                         @RequestParam("query") final String query,
                         @RequestParam("textField") final String textField,
                         @RequestParam(value = "filenameField", required = false) final String filenameField,
                         @RequestParam(value = "username", required = false) final String username,
                         @RequestParam(value = "password", required = false) final String password,
                         final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedQuery = query == null ? "" : query.trim();
        final String trimmedTextField = textField == null ? "" : textField.trim();
        final String trimmedFilenameField = filenameField == null ? "" : filenameField.trim();
        final String trimmedUsername = username == null ? "" : username.trim();
        // Password is intentionally not trimmed — leading/trailing whitespace can be valid.
        final String rawPassword = password == null ? "" : password;

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedEndpoint.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Endpoint is required.");
            return "redirect:/admin/data-sources";
        }
        // Checked on save and edit too, not only by Test and when an import is queued (#73).
        if (!hostAllowList.isAllowed(trimmedEndpoint)) {
            redirectAttributes.addFlashAttribute("error", ENDPOINT_NOT_ALLOWED);
            return "redirect:/admin/data-sources";
        }
        if (trimmedQuery.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Query is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedTextField.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Text field is required.");
            return "redirect:/admin/data-sources";
        }
        final java.util.Optional<OpenSearchDataSource> existing =
                repository.findFirstByNameIgnoreCase(trimmedName);
        if (existing.isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A data source named \"" + existing.get().getName() + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        final OpenSearchDataSource source = new OpenSearchDataSource();
        source.setId(UUID.randomUUID().toString());
        source.setName(trimmedName);
        source.setEndpoint(trimmedEndpoint);
        source.setQuery(trimmedQuery);
        source.setTextField(trimmedTextField);
        source.setFilenameField(trimmedFilenameField.isEmpty() ? null : trimmedFilenameField);
        source.setUsername(trimmedUsername.isEmpty() ? null : trimmedUsername);
        source.setEncryptedPassword(rawPassword.isEmpty() ? null : cipher.encrypt(rawPassword));
        source.setCreatedAt(LocalDateTime.now());

        try {
            repository.save(source);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "A data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        auditLogService.log("OPENSEARCH_DATASOURCE_CREATE", "OpenSearchDataSource", source.getId(),
                Map.of("name", trimmedName,
                        "endpoint", trimmedEndpoint,
                        "query", trimmedQuery,
                        "textField", trimmedTextField,
                        "filenameField", source.getFilenameField() == null ? "" : source.getFilenameField(),
                        "username", source.getUsername() == null ? "" : source.getUsername(),
                        "passwordSet", source.getEncryptedPassword() != null));
        redirectAttributes.addFlashAttribute("success",
                "Data source \"" + trimmedName + "\" added.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final OpenSearchDataSource source = repository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Data source not found.");
            return "redirect:/admin/data-sources";
        }
        repository.deleteById(id);
        auditLogService.log("OPENSEARCH_DATASOURCE_DELETE", "OpenSearchDataSource", id,
                Map.of("name", source.getName() == null ? "" : source.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Data source \"" + source.getName() + "\" removed.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/{id}/edit")
    public String editOpenSearch(@PathVariable final String id,
                                 @RequestParam("endpoint") final String endpoint,
                                 @RequestParam("query") final String query,
                                 @RequestParam("textField") final String textField,
                                 @RequestParam(value = "filenameField", required = false) final String filenameField,
                                 @RequestParam(value = "username", required = false) final String username,
                                 @RequestParam(value = "password", required = false) final String password,
                                 @RequestParam(value = "clearPassword", defaultValue = "false") final boolean clearPassword,
                                 final RedirectAttributes redirectAttributes) {
        final OpenSearchDataSource source = repository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Data source not found.");
            return "redirect:/admin/data-sources";
        }
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedQuery = query == null ? "" : query.trim();
        final String trimmedTextField = textField == null ? "" : textField.trim();
        final String trimmedFilenameField = filenameField == null ? "" : filenameField.trim();
        final String trimmedUsername = username == null ? "" : username.trim();
        // Password is intentionally not trimmed.
        final String rawPassword = password == null ? "" : password;

        if (trimmedEndpoint.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Endpoint is required.");
            return "redirect:/admin/data-sources";
        }
        // Checked on save and edit too, not only by Test and when an import is queued (#73).
        if (!hostAllowList.isAllowed(trimmedEndpoint)) {
            redirectAttributes.addFlashAttribute("error", ENDPOINT_NOT_ALLOWED);
            return "redirect:/admin/data-sources";
        }
        if (trimmedQuery.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Query is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedTextField.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Text field is required.");
            return "redirect:/admin/data-sources";
        }

        // Password handling:
        //  - clearPassword=true wipes the stored password.
        //  - non-empty rawPassword replaces it.
        //  - blank rawPassword (and clearPassword=false) leaves the existing password alone so
        //    admins can edit other fields without re-typing credentials.
        final boolean passwordChanged;
        if (clearPassword) {
            source.setEncryptedPassword(null);
            passwordChanged = true;
        } else if (!rawPassword.isEmpty()) {
            source.setEncryptedPassword(cipher.encrypt(rawPassword));
            passwordChanged = true;
        } else {
            passwordChanged = false;
        }

        source.setEndpoint(trimmedEndpoint);
        source.setQuery(trimmedQuery);
        source.setTextField(trimmedTextField);
        source.setFilenameField(trimmedFilenameField.isEmpty() ? null : trimmedFilenameField);
        source.setUsername(trimmedUsername.isEmpty() ? null : trimmedUsername);
        repository.save(source);

        auditLogService.log("OPENSEARCH_DATASOURCE_UPDATE", "OpenSearchDataSource", source.getId(),
                Map.of("name", source.getName() == null ? "" : source.getName(),
                        "endpoint", trimmedEndpoint,
                        "query", trimmedQuery,
                        "textField", trimmedTextField,
                        "filenameField", source.getFilenameField() == null ? "" : source.getFilenameField(),
                        "username", source.getUsername() == null ? "" : source.getUsername(),
                        "passwordChanged", passwordChanged,
                        "passwordSet", source.getEncryptedPassword() != null));
        redirectAttributes.addFlashAttribute("success",
                "Data source \"" + source.getName() + "\" updated.");
        return "redirect:/admin/data-sources";
    }

    /**
     * Connect to the supplied OpenSearch endpoint, run the query, and return up to the first 10
     * hits. Used by the "Test" button on the Add OpenSearch data source form so the admin can
     * verify connectivity and the query shape before saving the data source.
     *
     * <p>Query format matches what we already accept on creation: {@code <path> <json>}, where
     * {@code <path>} is the index/_search path (e.g. {@code contracts/_search}) and {@code <json>}
     * is the request body.
     */
    @PostMapping("/test")
    @ResponseBody
    public Map<String, Object> testOpenSearch(@RequestParam("endpoint") final String endpoint,
                                              @RequestParam("query") final String query,
                                              @RequestParam(value = "username", required = false) final String username,
                                              @RequestParam(value = "password", required = false) final String password) {
        return runOpenSearchTest(endpoint, query, username, password);
    }

    /**
     * Same connectivity / preview test as {@link #testOpenSearch}, but for an already-saved
     * data source identified by id. The stored password is decrypted server-side so the UI
     * never has to handle the cleartext.
     */
    @PostMapping("/{id}/test")
    @ResponseBody
    public Map<String, Object> testSavedOpenSearch(@PathVariable final String id) {
        final OpenSearchDataSource source = repository.findById(id).orElse(null);
        if (source == null) {
            return Map.of("ok", false, "error", "Data source not found.");
        }
        final String password = source.getEncryptedPassword() == null
                || source.getEncryptedPassword().isEmpty()
                ? null
                : cipher.decrypt(source.getEncryptedPassword());
        return runOpenSearchTest(source.getEndpoint(), source.getQuery(),
                source.getUsername(), password);
    }

    private Map<String, Object> runOpenSearchTest(final String endpoint, final String query,
                                                  final String username, final String password) {
        final Map<String, Object> result = new LinkedHashMap<>();
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedEndpoint.isEmpty()) {
            result.put("ok", false);
            result.put("error", "Endpoint is required.");
            return result;
        }
        if (trimmedQuery.isEmpty()) {
            result.put("ok", false);
            result.put("error", "Query is required.");
            return result;
        }
        // Optional defense-in-depth: when philter-workspace.data-sources.allowed-hosts is configured,
        // the endpoint host must be on the allow-list. Disabled by default; opt-in for
        // deployments where Philter Workspace has access to internal services that admins should not
        // be able to reach.
        if (!hostAllowList.isAllowed(trimmedEndpoint)) {
            result.put("ok", false);
            result.put("error", "Endpoint host is not on the data-source allow-list "
                    + "(philter-workspace.data-sources.allowed-hosts).");
            return result;
        }

        final int split = firstWhitespace(trimmedQuery);
        final String path = split < 0 ? trimmedQuery : trimmedQuery.substring(0, split);
        final String body = split < 0 ? "{}" : trimmedQuery.substring(split + 1).trim();

        final String url = trimmedEndpoint.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
        final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (username != null && !username.isBlank()) {
            final String credentials = username + ":" + (password == null ? "" : password);
            final String basic = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            reqBuilder.header("Authorization", "Basic " + basic);
        }

        try {
            final HttpResponse<String> resp = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                result.put("ok", false);
                result.put("error", "OpenSearch returned HTTP " + resp.statusCode()
                        + (resp.body() == null || resp.body().isEmpty()
                            ? "." : ": " + truncate(resp.body(), 500)));
                return result;
            }
            final JsonNode root = objectMapper.readTree(resp.body());
            final JsonNode hitsArr = root.path("hits").path("hits");
            final List<Map<String, Object>> hits = new ArrayList<>();
            if (hitsArr.isArray()) {
                for (int i = 0; i < hitsArr.size() && i < 10; i++) {
                    final JsonNode hit = hitsArr.get(i);
                    final Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("_index", hit.path("_index").asString(""));
                    entry.put("_id", hit.path("_id").asString(""));
                    final JsonNode source = hit.path("_source");
                    entry.put("_source", source.isMissingNode() ? new LinkedHashMap<>()
                            : objectMapper.convertValue(source, Map.class));
                    hits.add(entry);
                }
            }
            final long total = root.path("hits").path("total").path("value").asLong(
                    root.path("hits").path("total").asLong(hits.size()));
            result.put("ok", true);
            result.put("total", total);
            result.put("hits", hits);
            return result;
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", "Could not reach OpenSearch: " + e.getMessage());
            return result;
        }
    }

    private static int firstWhitespace(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static String truncate(final String s, final int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // Elasticsearch ----------------------------------------------------------
    //
    // Behaves identically to OpenSearch: same query/_search/hits/_source dialect, same
    // create/edit/delete/test affordances. Stored in its own collection so a name can be
    // reused across types.

    @PostMapping("/es")
    public String createEs(@RequestParam("name") final String name,
                           @RequestParam("endpoint") final String endpoint,
                           @RequestParam("query") final String query,
                           @RequestParam("textField") final String textField,
                           @RequestParam(value = "filenameField", required = false) final String filenameField,
                           @RequestParam(value = "username", required = false) final String username,
                           @RequestParam(value = "password", required = false) final String password,
                           final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedQuery = query == null ? "" : query.trim();
        final String trimmedTextField = textField == null ? "" : textField.trim();
        final String trimmedFilenameField = filenameField == null ? "" : filenameField.trim();
        final String trimmedUsername = username == null ? "" : username.trim();
        final String rawPassword = password == null ? "" : password;

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedEndpoint.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Endpoint is required.");
            return "redirect:/admin/data-sources";
        }
        // Checked on save and edit too, not only by Test and when an import is queued (#73).
        if (!hostAllowList.isAllowed(trimmedEndpoint)) {
            redirectAttributes.addFlashAttribute("error", ENDPOINT_NOT_ALLOWED);
            return "redirect:/admin/data-sources";
        }
        if (trimmedQuery.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Query is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedTextField.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Text field is required.");
            return "redirect:/admin/data-sources";
        }
        final java.util.Optional<ElasticsearchDataSource> existingEs =
                esRepository.findFirstByNameIgnoreCase(trimmedName);
        if (existingEs.isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "An Elasticsearch data source named \"" + existingEs.get().getName() + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        final ElasticsearchDataSource source = new ElasticsearchDataSource();
        source.setId(UUID.randomUUID().toString());
        source.setName(trimmedName);
        source.setEndpoint(trimmedEndpoint);
        source.setQuery(trimmedQuery);
        source.setTextField(trimmedTextField);
        source.setFilenameField(trimmedFilenameField.isEmpty() ? null : trimmedFilenameField);
        source.setUsername(trimmedUsername.isEmpty() ? null : trimmedUsername);
        source.setEncryptedPassword(rawPassword.isEmpty() ? null : cipher.encrypt(rawPassword));
        source.setCreatedAt(LocalDateTime.now());

        try {
            esRepository.save(source);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "An Elasticsearch data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        auditLogService.log("ELASTICSEARCH_DATASOURCE_CREATE", "ElasticsearchDataSource", source.getId(),
                Map.of("name", trimmedName,
                        "endpoint", trimmedEndpoint,
                        "query", trimmedQuery,
                        "textField", trimmedTextField,
                        "filenameField", source.getFilenameField() == null ? "" : source.getFilenameField(),
                        "username", source.getUsername() == null ? "" : source.getUsername(),
                        "passwordSet", source.getEncryptedPassword() != null));
        redirectAttributes.addFlashAttribute("success",
                "Elasticsearch data source \"" + trimmedName + "\" added.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/es/{id}/delete")
    public String deleteEs(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final ElasticsearchDataSource source = esRepository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Data source not found.");
            return "redirect:/admin/data-sources";
        }
        esRepository.deleteById(id);
        auditLogService.log("ELASTICSEARCH_DATASOURCE_DELETE", "ElasticsearchDataSource", id,
                Map.of("name", source.getName() == null ? "" : source.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Elasticsearch data source \"" + source.getName() + "\" removed.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/es/{id}/edit")
    public String editEs(@PathVariable final String id,
                         @RequestParam("endpoint") final String endpoint,
                         @RequestParam("query") final String query,
                         @RequestParam("textField") final String textField,
                         @RequestParam(value = "filenameField", required = false) final String filenameField,
                         @RequestParam(value = "username", required = false) final String username,
                         @RequestParam(value = "password", required = false) final String password,
                         @RequestParam(value = "clearPassword", defaultValue = "false") final boolean clearPassword,
                         final RedirectAttributes redirectAttributes) {
        final ElasticsearchDataSource source = esRepository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Data source not found.");
            return "redirect:/admin/data-sources";
        }
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedQuery = query == null ? "" : query.trim();
        final String trimmedTextField = textField == null ? "" : textField.trim();
        final String trimmedFilenameField = filenameField == null ? "" : filenameField.trim();
        final String trimmedUsername = username == null ? "" : username.trim();
        final String rawPassword = password == null ? "" : password;

        if (trimmedEndpoint.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Endpoint is required.");
            return "redirect:/admin/data-sources";
        }
        // Checked on save and edit too, not only by Test and when an import is queued (#73).
        if (!hostAllowList.isAllowed(trimmedEndpoint)) {
            redirectAttributes.addFlashAttribute("error", ENDPOINT_NOT_ALLOWED);
            return "redirect:/admin/data-sources";
        }
        if (trimmedQuery.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Query is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedTextField.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Text field is required.");
            return "redirect:/admin/data-sources";
        }

        final boolean passwordChanged;
        if (clearPassword) {
            source.setEncryptedPassword(null);
            passwordChanged = true;
        } else if (!rawPassword.isEmpty()) {
            source.setEncryptedPassword(cipher.encrypt(rawPassword));
            passwordChanged = true;
        } else {
            passwordChanged = false;
        }

        source.setEndpoint(trimmedEndpoint);
        source.setQuery(trimmedQuery);
        source.setTextField(trimmedTextField);
        source.setFilenameField(trimmedFilenameField.isEmpty() ? null : trimmedFilenameField);
        source.setUsername(trimmedUsername.isEmpty() ? null : trimmedUsername);
        esRepository.save(source);

        auditLogService.log("ELASTICSEARCH_DATASOURCE_UPDATE", "ElasticsearchDataSource", source.getId(),
                Map.of("name", source.getName() == null ? "" : source.getName(),
                        "endpoint", trimmedEndpoint,
                        "query", trimmedQuery,
                        "textField", trimmedTextField,
                        "filenameField", source.getFilenameField() == null ? "" : source.getFilenameField(),
                        "username", source.getUsername() == null ? "" : source.getUsername(),
                        "passwordChanged", passwordChanged,
                        "passwordSet", source.getEncryptedPassword() != null));
        redirectAttributes.addFlashAttribute("success",
                "Elasticsearch data source \"" + source.getName() + "\" updated.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/es/test")
    @ResponseBody
    public Map<String, Object> testElasticsearch(@RequestParam("endpoint") final String endpoint,
                                                 @RequestParam("query") final String query,
                                                 @RequestParam(value = "username", required = false) final String username,
                                                 @RequestParam(value = "password", required = false) final String password) {
        return runOpenSearchTest(endpoint, query, username, password);
    }

    @PostMapping("/es/{id}/test")
    @ResponseBody
    public Map<String, Object> testSavedElasticsearch(@PathVariable final String id) {
        final ElasticsearchDataSource source = esRepository.findById(id).orElse(null);
        if (source == null) {
            return Map.of("ok", false, "error", "Data source not found.");
        }
        final String password = source.getEncryptedPassword() == null
                || source.getEncryptedPassword().isEmpty()
                ? null
                : cipher.decrypt(source.getEncryptedPassword());
        return runOpenSearchTest(source.getEndpoint(), source.getQuery(),
                source.getUsername(), password);
    }

    @PostMapping("/s3")
    public String createS3(@RequestParam("name") final String name,
                           @RequestParam(value = "endpoint", required = false) final String endpoint,
                           @RequestParam("bucketName") final String bucketName,
                           @RequestParam("bucketKey") final String bucketKey,
                           @RequestParam("filenameGlob") final String filenameGlob,
                           @RequestParam(value = "accessKey", required = false) final String accessKey,
                           @RequestParam(value = "secretKey", required = false) final String secretKey,
                           final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedBucket = bucketName == null ? "" : bucketName.trim();
        final String trimmedKey = bucketKey == null ? "" : bucketKey.trim();
        final String trimmedGlob = filenameGlob == null ? "" : filenameGlob.trim();
        // Credentials are not trimmed — AWS keys can theoretically contain whitespace, and a user
        // who typed one is asking us to store exactly what they typed.
        final String rawAccessKey = accessKey == null ? "" : accessKey;
        final String rawSecretKey = secretKey == null ? "" : secretKey;

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedBucket.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Bucket name is required.");
            return "redirect:/admin/data-sources";
        }
        // The endpoint is optional (AWS when blank); one that is given is checked on save and edit (#73).
        if (!trimmedEndpoint.isEmpty() && !hostAllowList.isAllowed(trimmedEndpoint)) {
            redirectAttributes.addFlashAttribute("error", ENDPOINT_NOT_ALLOWED);
            return "redirect:/admin/data-sources";
        }
        if (trimmedKey.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Bucket key is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedGlob.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Filename glob is required.");
            return "redirect:/admin/data-sources";
        }
        // An access key without a secret key (or vice versa) is almost certainly a mistake.
        if (rawAccessKey.isEmpty() != rawSecretKey.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Provide both Access key and Secret key, or leave both blank.");
            return "redirect:/admin/data-sources";
        }
        final java.util.Optional<S3DataSource> existingS3 =
                s3Repository.findFirstByNameIgnoreCase(trimmedName);
        if (existingS3.isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "An S3 data source named \"" + existingS3.get().getName() + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        final S3DataSource source = new S3DataSource();
        source.setId(UUID.randomUUID().toString());
        source.setName(trimmedName);
        source.setEndpoint(trimmedEndpoint.isEmpty() ? null : trimmedEndpoint);
        source.setBucketName(trimmedBucket);
        source.setBucketKey(trimmedKey);
        source.setFilenameGlob(trimmedGlob);
        source.setEncryptedAccessKey(rawAccessKey.isEmpty() ? null : cipher.encrypt(rawAccessKey));
        source.setEncryptedSecretKey(rawSecretKey.isEmpty() ? null : cipher.encrypt(rawSecretKey));
        source.setCreatedAt(LocalDateTime.now());

        try {
            s3Repository.save(source);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "An S3 data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        auditLogService.log("S3_DATASOURCE_CREATE", "S3DataSource", source.getId(),
                Map.of("name", trimmedName,
                        "bucketName", trimmedBucket,
                        "bucketKey", trimmedKey,
                        "filenameGlob", trimmedGlob,
                        "credentialsSet", source.getEncryptedAccessKey() != null));
        redirectAttributes.addFlashAttribute("success",
                "S3 data source \"" + trimmedName + "\" added.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/s3/{id}/delete")
    public String deleteS3(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final S3DataSource source = s3Repository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "S3 data source not found.");
            return "redirect:/admin/data-sources";
        }
        s3Repository.deleteById(id);
        auditLogService.log("S3_DATASOURCE_DELETE", "S3DataSource", id,
                Map.of("name", source.getName() == null ? "" : source.getName()));
        redirectAttributes.addFlashAttribute("success",
                "S3 data source \"" + source.getName() + "\" removed.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/s3/{id}/edit")
    public String editS3(@PathVariable final String id,
                         @RequestParam(value = "endpoint", required = false) final String endpoint,
                         @RequestParam("bucketName") final String bucketName,
                         @RequestParam("bucketKey") final String bucketKey,
                         @RequestParam("filenameGlob") final String filenameGlob,
                         @RequestParam(value = "accessKey", required = false) final String accessKey,
                         @RequestParam(value = "secretKey", required = false) final String secretKey,
                         @RequestParam(value = "clearCredentials", defaultValue = "false") final boolean clearCredentials,
                         final RedirectAttributes redirectAttributes) {
        final S3DataSource source = s3Repository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "S3 data source not found.");
            return "redirect:/admin/data-sources";
        }
        final String trimmedEndpoint = endpoint == null ? "" : endpoint.trim();
        final String trimmedBucket = bucketName == null ? "" : bucketName.trim();
        final String trimmedKey = bucketKey == null ? "" : bucketKey.trim();
        final String trimmedGlob = filenameGlob == null ? "" : filenameGlob.trim();
        // Credentials are not trimmed — AWS keys can technically contain whitespace, so
        // store exactly what the operator typed.
        final String rawAccessKey = accessKey == null ? "" : accessKey;
        final String rawSecretKey = secretKey == null ? "" : secretKey;

        if (trimmedBucket.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Bucket name is required.");
            return "redirect:/admin/data-sources";
        }
        // The endpoint is optional (AWS when blank); one that is given is checked on save and edit (#73).
        if (!trimmedEndpoint.isEmpty() && !hostAllowList.isAllowed(trimmedEndpoint)) {
            redirectAttributes.addFlashAttribute("error", ENDPOINT_NOT_ALLOWED);
            return "redirect:/admin/data-sources";
        }
        if (trimmedKey.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Bucket key is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedGlob.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Filename glob is required.");
            return "redirect:/admin/data-sources";
        }
        // Mismatched pair almost always indicates a typo or an incomplete edit; refuse
        // rather than silently store half a credential.
        if (rawAccessKey.isEmpty() != rawSecretKey.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Provide both Access key and Secret key, or leave both blank.");
            return "redirect:/admin/data-sources";
        }

        // Credentials are tri-state, mirroring the OpenSearch password flow:
        //  - clearCredentials=true wipes both stored keys (fall back to ambient AWS creds).
        //  - non-empty pair replaces them.
        //  - blank pair (and clearCredentials=false) leaves existing keys intact so admins
        //    can adjust bucket/key/glob without re-typing the secret.
        final boolean credentialsChanged;
        if (clearCredentials) {
            source.setEncryptedAccessKey(null);
            source.setEncryptedSecretKey(null);
            credentialsChanged = true;
        } else if (!rawAccessKey.isEmpty()) {
            source.setEncryptedAccessKey(cipher.encrypt(rawAccessKey));
            source.setEncryptedSecretKey(cipher.encrypt(rawSecretKey));
            credentialsChanged = true;
        } else {
            credentialsChanged = false;
        }

        source.setEndpoint(trimmedEndpoint.isEmpty() ? null : trimmedEndpoint);
        source.setBucketName(trimmedBucket);
        source.setBucketKey(trimmedKey);
        source.setFilenameGlob(trimmedGlob);
        s3Repository.save(source);

        auditLogService.log("S3_DATASOURCE_UPDATE", "S3DataSource", source.getId(),
                Map.of("name", source.getName() == null ? "" : source.getName(),
                        "endpoint", source.getEndpoint() == null ? "" : source.getEndpoint(),
                        "bucketName", trimmedBucket,
                        "bucketKey", trimmedKey,
                        "filenameGlob", trimmedGlob,
                        "credentialsChanged", credentialsChanged,
                        "credentialsSet", source.getEncryptedAccessKey() != null));
        redirectAttributes.addFlashAttribute("success",
                "S3 data source \"" + source.getName() + "\" updated.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/rdb")
    public String createRdb(@RequestParam("name") final String name,
                            @RequestParam("jdbcUrl") final String jdbcUrl,
                            @RequestParam("sqlQuery") final String sqlQuery,
                            @RequestParam(value = "username", required = false) final String username,
                            @RequestParam(value = "password", required = false) final String password,
                            @RequestParam(value = "watermarkColumn", required = false)
                                    final String watermarkColumn,
                            final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
        final String trimmedSql = sqlQuery == null ? "" : sqlQuery.trim();
        final String trimmedWatermark = watermarkColumn == null ? "" : watermarkColumn.trim();
        // Credentials are not trimmed — leading/trailing whitespace can be valid in passwords.
        final String rawUsername = username == null ? "" : username;
        final String rawPassword = password == null ? "" : password;

        if (trimmedName.isEmpty()) {
            return rdbCreateError(redirectAttributes, "Name is required.",
                    trimmedName, trimmedUrl, trimmedSql, rawUsername, rawPassword, trimmedWatermark);
        }
        if (trimmedUrl.isEmpty()) {
            return rdbCreateError(redirectAttributes, "JDBC URL is required.",
                    trimmedName, trimmedUrl, trimmedSql, rawUsername, rawPassword, trimmedWatermark);
        }
        // SSRF / RCE defense: refuse JDBC URLs whose driver can run code at connect
        // time (H2 INIT, Derby restoreFrom), or that smuggle a known-dangerous param
        // (PostgreSQL socketFactory, MySQL autoDeserialize, etc.). When the deployer
        // has configured philter-workspace.data-sources.allowed-hosts, the host portion is also
        // gated against that list. See JdbcUrlValidator for the full rule set.
        final ai.philterd.workspace.service.JdbcUrlValidator.Result urlCheck =
                jdbcUrlValidator.validate(trimmedUrl);
        // Strip any embedded user:pass@ from the URL *before* it touches the audit
        // log. The validator above refuses URLs with embedded credentials outright,
        // but the rejection event itself would otherwise persist the password (the
        // audit row was the leak vector — see finding #13). The strip is a no-op for
        // well-formed URLs without userinfo, so it's safe to apply unconditionally.
        final String safeUrl = ai.philterd.workspace.service.JdbcUrlValidator.stripUserInfo(trimmedUrl);
        if (!urlCheck.ok()) {
            auditLogService.log("RDB_DANGEROUS_JDBC_URL_BLOCKED", "RelationalDbDataSource", null,
                    Map.of("name", trimmedName,
                            "jdbcUrl", safeUrl,
                            "reason", urlCheck.error() == null ? "" : urlCheck.error()));
            return rdbCreateError(redirectAttributes, urlCheck.error(),
                    trimmedName, trimmedUrl, trimmedSql, rawUsername, rawPassword, trimmedWatermark);
        }
        if (trimmedSql.isEmpty()) {
            return rdbCreateError(redirectAttributes, "SQL query is required.",
                    trimmedName, trimmedUrl, trimmedSql, rawUsername, rawPassword, trimmedWatermark);
        }
        // Read-only allow-list: only SELECT and WITH … SELECT are permitted; any
        // mutation keyword (INSERT/UPDATE/MERGE/EXEC/CALL/…), multi-statement input,
        // or dollar-quoted block is refused before persistence. The audit row keeps
        // the offending SQL for forensic review without round-tripping through the
        // saved data source.
        final ai.philterd.workspace.service.SqlReadOnlyValidator.Result sqlCheck =
                ai.philterd.workspace.service.SqlReadOnlyValidator.validate(trimmedSql);
        if (!sqlCheck.ok()) {
            auditLogService.log("RDB_DANGEROUS_SQL_BLOCKED", "RelationalDbDataSource", null,
                    Map.of("name", trimmedName,
                            "jdbcUrl", safeUrl,
                            "reason", sqlCheck.error() == null ? "" : sqlCheck.error(),
                            "sqlQuery", trimmedSql));
            return rdbCreateError(redirectAttributes, sqlCheck.error(),
                    trimmedName, trimmedUrl, trimmedSql, rawUsername, rawPassword, trimmedWatermark);
        }
        // A username without a password (or vice versa) is almost always a typo.
        if (rawUsername.isEmpty() != rawPassword.isEmpty()) {
            return rdbCreateError(redirectAttributes,
                    "Provide both Username and Password, or leave both blank.",
                    trimmedName, trimmedUrl, trimmedSql, rawUsername, rawPassword, trimmedWatermark);
        }
        final java.util.Optional<RelationalDbDataSource> existingRdb =
                rdbRepository.findFirstByNameIgnoreCase(trimmedName);
        if (existingRdb.isPresent()) {
            return rdbCreateError(redirectAttributes,
                    "A relational database data source named \"" + existingRdb.get().getName()
                            + "\" already exists.",
                    trimmedName, trimmedUrl, trimmedSql, rawUsername, rawPassword, trimmedWatermark);
        }

        final RelationalDbDataSource source = new RelationalDbDataSource();
        source.setId(UUID.randomUUID().toString());
        source.setName(trimmedName);
        // The JDBC URL is encrypted at rest because it can carry credentials in
        // query-string flags some drivers accept. The validator already refused
        // user:pass@ userinfo above; this is the belt-and-braces guarantee.
        source.setEncryptedJdbcUrl(cipher.encrypt(trimmedUrl));
        source.setSqlQuery(trimmedSql);
        source.setEncryptedUsername(rawUsername.isEmpty() ? null : cipher.encrypt(rawUsername));
        source.setEncryptedPassword(rawPassword.isEmpty() ? null : cipher.encrypt(rawPassword));
        source.setCreatedAt(LocalDateTime.now());
        // Watermark column is opt-in per source. Empty means the source runs
        // as a one-shot full-scan (capped by MAX_ROWS_PER_RUN); a value means
        // the worker reads that column from each row and advances the per-
        // source watermark to its last-seen value on a successful run.
        source.setWatermarkColumn(trimmedWatermark.isEmpty() ? null : trimmedWatermark);

        try {
            rdbRepository.save(source);
        } catch (DuplicateKeyException e) {
            return rdbCreateError(redirectAttributes,
                    "A relational database data source named \"" + trimmedName + "\" already exists.",
                    trimmedName, trimmedUrl, trimmedSql, rawUsername, rawPassword, trimmedWatermark);
        }

        auditLogService.log("RDB_DATASOURCE_CREATE", "RelationalDbDataSource", source.getId(),
                Map.of("name", trimmedName,
                        "jdbcUrl", safeUrl,
                        "sqlQuery", trimmedSql,
                        "credentialsSet", source.getEncryptedUsername() != null,
                        "watermarkColumn", source.getWatermarkColumn() == null
                                ? "" : source.getWatermarkColumn()));
        redirectAttributes.addFlashAttribute("success",
                "Relational database data source \"" + trimmedName + "\" added.");
        return "redirect:/admin/data-sources?tab=rdb";
    }

    /**
     * Round-trip the submitted form values through flash attributes so the page that
     * re-renders after a validation error keeps everything the operator typed. Without
     * this every error path would empty the form and the operator would have to retype
     * the JDBC URL and SQL just to fix a single field. The redirect target also pins
     * the RDB tab as the active one (the tab controller reads {@code ?tab=…} on load).
     *
     * <p>The credentials round-trip too — re-entering a password after a name-collision
     * error is friction the operator doesn't need. Flash attributes are server-side
     * session storage scoped to a single redirect, so the password is not exposed any
     * more than the original POST body.
     */
    private String rdbCreateError(final RedirectAttributes ra, final String error,
                                  final String name, final String jdbcUrl, final String sqlQuery,
                                  final String username, final String password,
                                  final String watermarkColumn) {
        ra.addFlashAttribute("error", error);
        ra.addFlashAttribute("rdbFormName", name);
        ra.addFlashAttribute("rdbFormJdbcUrl", jdbcUrl);
        ra.addFlashAttribute("rdbFormSqlQuery", sqlQuery);
        ra.addFlashAttribute("rdbFormUsername", username);
        ra.addFlashAttribute("rdbFormPassword", password);
        ra.addFlashAttribute("rdbFormWatermarkColumn", watermarkColumn);
        return "redirect:/admin/data-sources?tab=rdb";
    }

    @PostMapping("/rdb/{id}/delete")
    public String deleteRdb(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final RelationalDbDataSource source = rdbRepository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Relational database data source not found.");
            return "redirect:/admin/data-sources";
        }
        // Refuse the delete while an import job is still running against this source.
        // Without this check the worker would dequeue a now-orphaned job and fail in a
        // way that's hard to diagnose ("source not found" on a job the operator never
        // started). Terminal jobs (COMPLETED/FAILED) don't block — they're just history.
        final boolean inUse = backgroundJobRepository.existsBySourceIdAndStatusIn(
                id, List.of(BackgroundJob.STATUS_PENDING, BackgroundJob.STATUS_RUNNING));
        if (inUse) {
            redirectAttributes.addFlashAttribute("error",
                    "Cannot remove \"" + source.getName()
                            + "\" — a data import job is currently using it. "
                            + "Wait for the job to finish or cancel it first.");
            return "redirect:/admin/data-sources?tab=rdb";
        }
        rdbRepository.deleteById(id);
        auditLogService.log("RDB_DATASOURCE_DELETE", "RelationalDbDataSource", id,
                Map.of("name", source.getName() == null ? "" : source.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Relational database data source \"" + source.getName() + "\" removed.");
        return "redirect:/admin/data-sources?tab=rdb";
    }

    /**
     * Update connection properties on a saved RDB data source. The {@code name} is
     * immutable for the same reason as on every other data-source edit path: it's the
     * stable identifier operators use to recognise the row, and renaming it has zero
     * functional benefit. JDBC URL and SQL re-run through the same validators as the
     * create path, so a saved row can never be edited into something the create form
     * would have refused.
     *
     * <p>Credentials are tri-state, mirroring the S3 / OpenSearch edit flows:
     * <ul>
     *   <li>{@code clearCredentials=true} wipes both stored values (fall back to whatever
     *       the JDBC URL itself or the driver's ambient auth provides).</li>
     *   <li>Non-empty pair replaces them (re-encrypted at rest).</li>
     *   <li>Blank pair with {@code clearCredentials=false} leaves them intact, so an
     *       admin can adjust the URL or SQL without re-typing the password.</li>
     * </ul>
     */
    @PostMapping("/rdb/{id}/edit")
    public String editRdb(@PathVariable final String id,
                          @RequestParam("jdbcUrl") final String jdbcUrl,
                          @RequestParam("sqlQuery") final String sqlQuery,
                          @RequestParam(value = "username", required = false) final String username,
                          @RequestParam(value = "password", required = false) final String password,
                          @RequestParam(value = "watermarkColumn", required = false)
                                  final String watermarkColumn,
                          @RequestParam(value = "clearCredentials", defaultValue = "false")
                                  final boolean clearCredentials,
                          @RequestParam(value = "confirmResetWatermark", defaultValue = "false")
                                  final boolean confirmResetWatermark,
                          final RedirectAttributes redirectAttributes) {
        final RelationalDbDataSource source = rdbRepository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Relational database data source not found.");
            return "redirect:/admin/data-sources?tab=rdb";
        }
        final String trimmedUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
        final String trimmedSql = sqlQuery == null ? "" : sqlQuery.trim();
        final String trimmedWatermark = watermarkColumn == null ? "" : watermarkColumn.trim();
        // Credentials are not trimmed — leading/trailing whitespace can be valid in passwords.
        final String rawUsername = username == null ? "" : username;
        final String rawPassword = password == null ? "" : password;

        if (trimmedUrl.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "JDBC URL is required.");
            return "redirect:/admin/data-sources?tab=rdb";
        }
        final ai.philterd.workspace.service.JdbcUrlValidator.Result urlCheck =
                jdbcUrlValidator.validate(trimmedUrl);
        final String safeUrl = ai.philterd.workspace.service.JdbcUrlValidator.stripUserInfo(trimmedUrl);
        if (!urlCheck.ok()) {
            auditLogService.log("RDB_DANGEROUS_JDBC_URL_BLOCKED", "RelationalDbDataSource", id,
                    Map.of("name", source.getName() == null ? "" : source.getName(),
                            "jdbcUrl", safeUrl,
                            "reason", urlCheck.error() == null ? "" : urlCheck.error()));
            redirectAttributes.addFlashAttribute("error", urlCheck.error());
            return "redirect:/admin/data-sources?tab=rdb";
        }
        if (trimmedSql.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "SQL query is required.");
            return "redirect:/admin/data-sources?tab=rdb";
        }
        final ai.philterd.workspace.service.SqlReadOnlyValidator.Result sqlCheck =
                ai.philterd.workspace.service.SqlReadOnlyValidator.validate(trimmedSql);
        if (!sqlCheck.ok()) {
            auditLogService.log("RDB_DANGEROUS_SQL_BLOCKED", "RelationalDbDataSource", id,
                    Map.of("name", source.getName() == null ? "" : source.getName(),
                            "jdbcUrl", safeUrl,
                            "reason", sqlCheck.error() == null ? "" : sqlCheck.error(),
                            "sqlQuery", trimmedSql));
            redirectAttributes.addFlashAttribute("error", sqlCheck.error());
            return "redirect:/admin/data-sources?tab=rdb";
        }
        if (rawUsername.isEmpty() != rawPassword.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Provide both Username and Password, or leave both blank.");
            return "redirect:/admin/data-sources?tab=rdb";
        }

        final boolean credentialsChanged;
        if (clearCredentials) {
            source.setEncryptedUsername(null);
            source.setEncryptedPassword(null);
            credentialsChanged = true;
        } else if (!rawUsername.isEmpty()) {
            source.setEncryptedUsername(cipher.encrypt(rawUsername));
            source.setEncryptedPassword(cipher.encrypt(rawPassword));
            credentialsChanged = true;
        } else {
            credentialsChanged = false;
        }

        // Watermark integrity: if the admin changes the SQL or the watermark
        // column on a source that has an active watermark, the new query may
        // mean something entirely different. Force an explicit confirmation
        // so a typo can't quietly cause everything to be re-imported (if the
        // watermark is reset) or for new rows to be skipped (if it isn't).
        // The form's "Save" button toggles confirmResetWatermark=true once
        // the operator clicks through the warning.
        final boolean sqlChanged = !trimmedSql.equals(source.getSqlQuery() == null ? "" : source.getSqlQuery());
        final boolean watermarkColumnChanged = !trimmedWatermark.equals(
                source.getWatermarkColumn() == null ? "" : source.getWatermarkColumn());
        final boolean hasActiveWatermark = source.getLastImportedKey() != null;
        if (hasActiveWatermark && (sqlChanged || watermarkColumnChanged) && !confirmResetWatermark) {
            redirectAttributes.addFlashAttribute("error",
                    "This source has an active watermark (last imported key \""
                            + source.getLastImportedKey() + "\"). Changing the SQL or watermark "
                            + "column would change what \"new\" means; re-submit with "
                            + "\"Reset watermark\" checked to confirm.");
            return "redirect:/admin/data-sources?tab=rdb";
        }
        final boolean watermarkReset = hasActiveWatermark && (sqlChanged || watermarkColumnChanged);
        if (watermarkReset) {
            source.setLastImportedKey(null);
            source.setLastImportedAt(null);
        }

        // Re-encrypt on every edit. The URL may have changed; even if it hasn't,
        // re-encrypting produces a fresh AES-GCM nonce so the ciphertext on disk
        // rotates whenever the row is touched.
        source.setEncryptedJdbcUrl(cipher.encrypt(trimmedUrl));
        source.setSqlQuery(trimmedSql);
        source.setWatermarkColumn(trimmedWatermark.isEmpty() ? null : trimmedWatermark);
        rdbRepository.save(source);

        auditLogService.log("RDB_DATASOURCE_UPDATE", "RelationalDbDataSource", source.getId(),
                Map.of("name", source.getName() == null ? "" : source.getName(),
                        "jdbcUrl", safeUrl,
                        "sqlQuery", trimmedSql,
                        "credentialsChanged", credentialsChanged,
                        "credentialsSet", source.getEncryptedUsername() != null,
                        "watermarkColumn", source.getWatermarkColumn() == null
                                ? "" : source.getWatermarkColumn(),
                        "watermarkReset", watermarkReset));
        redirectAttributes.addFlashAttribute("success",
                "Relational database data source \"" + source.getName() + "\" updated.");
        return "redirect:/admin/data-sources?tab=rdb";
    }

    /**
     * Run the saved SQL with a {@link RdbPreviewService#MAX_ROWS} row cap and return
     * the column names plus the first few rows as JSON. The Preview popup on the
     * Data Sources page calls this to give operators a quick "does this query
     * return what I expect" check without leaving the page or starting a real
     * import. Defence in depth: the service revalidates the SQL — even though the
     * write paths above already gate against mutating statements, a hand-edited
     * MongoDB row could in principle slip a {@code DELETE} past the saved-state
     * guard.
     */
    /**
     * Clear the per-source watermark so the next ingest run starts from
     * scratch (the {@code :lastKey} placeholder substitutes {@code NULL}).
     * Used when the underlying table has been re-keyed, the watermark has
     * skipped past rows that should have been imported, or the operator
     * simply wants a full re-scan. Existing documents are not deleted —
     * the {@code (source-id, filename)} dedupe skips already-imported rows
     * on the next run.
     */
    @PostMapping("/rdb/{id}/reset-watermark")
    public String resetRdbWatermark(@PathVariable final String id,
                                    final RedirectAttributes redirectAttributes) {
        final RelationalDbDataSource source = rdbRepository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error",
                    "Relational database data source not found.");
            return "redirect:/admin/data-sources?tab=rdb";
        }
        final String previous = source.getLastImportedKey();
        source.setLastImportedKey(null);
        source.setLastImportedAt(null);
        rdbRepository.save(source);
        auditLogService.log("RDB_WATERMARK_RESET", "RelationalDbDataSource", source.getId(),
                Map.of("name", source.getName() == null ? "" : source.getName(),
                        "previousKey", previous == null ? "" : previous));
        redirectAttributes.addFlashAttribute("success",
                "Watermark cleared for \"" + source.getName() + "\". The next run starts from scratch.");
        return "redirect:/admin/data-sources?tab=rdb";
    }

    /**
     * Set the per-source watermark to a specific value, without running an
     * ingest. Useful for skipping past a known-bad range, or seeding the
     * watermark before the first run when the operator wants to ignore
     * everything older than some pivot.
     */
    /**
     * Conservative upper bound on a watermark value. Real-world keys are small —
     * a 64-bit integer is 19 ASCII chars, a UUID is 36, an RFC-3339 timestamp is
     * 25. 256 leaves comfortable headroom for combined keys
     * ({@code "2026-05-14T00:00:00Z|550e8400-e29b-41d4-a716-446655440000"}) while
     * refusing pathological inputs that would bloat the source row, the audit
     * log details, and the JDBC bind parameter for every subsequent run.
     */
    static final int MAX_WATERMARK_LENGTH = 256;

    @PostMapping("/rdb/{id}/set-watermark")
    public String setRdbWatermark(@PathVariable final String id,
                                  @RequestParam("watermark") final String watermark,
                                  final RedirectAttributes redirectAttributes) {
        final RelationalDbDataSource source = rdbRepository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error",
                    "Relational database data source not found.");
            return "redirect:/admin/data-sources?tab=rdb";
        }
        final String trimmed = watermark == null ? "" : watermark.trim();
        if (trimmed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Watermark value is required. Use Reset to clear it instead.");
            return "redirect:/admin/data-sources?tab=rdb";
        }
        if (trimmed.length() > MAX_WATERMARK_LENGTH) {
            // Defence in depth — admin-only endpoint, but a typo or paste-error
            // shouldn't blow up the source row, the audit log, or the JDBC bind.
            redirectAttributes.addFlashAttribute("error",
                    "Watermark value is too long (" + trimmed.length()
                            + " characters; max " + MAX_WATERMARK_LENGTH + ").");
            return "redirect:/admin/data-sources?tab=rdb";
        }
        final String previous = source.getLastImportedKey();
        source.setLastImportedKey(trimmed);
        source.setLastImportedAt(java.time.Instant.now());
        rdbRepository.save(source);
        auditLogService.log("RDB_WATERMARK_SET_MANUAL", "RelationalDbDataSource", source.getId(),
                Map.of("name", source.getName() == null ? "" : source.getName(),
                        "from", previous == null ? "" : previous,
                        "to", trimmed));
        redirectAttributes.addFlashAttribute("success",
                "Watermark for \"" + source.getName() + "\" set to \"" + trimmed + "\".");
        return "redirect:/admin/data-sources?tab=rdb";
    }

    @PostMapping("/rdb/{id}/preview")
    @ResponseBody
    public Map<String, Object> previewRdb(@PathVariable final String id) {
        final RelationalDbDataSource source = rdbRepository.findById(id).orElse(null);
        if (source == null) {
            return Map.of("ok", false, "error", "Relational database data source not found.");
        }
        final RdbPreviewService.Result result = rdbPreviewService.preview(source);
        if (!result.ok()) {
            return Map.of("ok", false, "error", result.error() == null ? "" : result.error());
        }
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("rowLimit", RdbPreviewService.MAX_ROWS);
        body.put("columns", result.columns());
        body.put("rows", result.rows());
        return body;
    }

    @PostMapping("/local")
    public String createLocal(@RequestParam("name") final String name,
                              @RequestParam("directoryPath") final String directoryPath,
                              @RequestParam("filenameGlob") final String filenameGlob,
                              final RedirectAttributes redirectAttributes) {
        final String trimmedName = name == null ? "" : name.trim();
        final String trimmedPath = directoryPath == null ? "" : directoryPath.trim();
        final String trimmedGlob = filenameGlob == null ? "" : filenameGlob.trim();

        if (trimmedName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Name is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedPath.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Directory path is required.");
            return "redirect:/admin/data-sources";
        }
        if (trimmedGlob.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Filename glob is required.");
            return "redirect:/admin/data-sources";
        }
        final java.util.Optional<LocalDirectoryDataSource> existingLocal =
                localRepository.findFirstByNameIgnoreCase(trimmedName);
        if (existingLocal.isPresent()) {
            redirectAttributes.addFlashAttribute("error",
                    "A local directory data source named \"" + existingLocal.get().getName() + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        final LocalDirectoryDataSource source = new LocalDirectoryDataSource();
        source.setId(UUID.randomUUID().toString());
        source.setName(trimmedName);
        source.setDirectoryPath(trimmedPath);
        source.setFilenameGlob(trimmedGlob);
        source.setCreatedAt(LocalDateTime.now());

        try {
            localRepository.save(source);
        } catch (DuplicateKeyException e) {
            redirectAttributes.addFlashAttribute("error",
                    "A local directory data source named \"" + trimmedName + "\" already exists.");
            return "redirect:/admin/data-sources";
        }

        auditLogService.log("LOCAL_DATASOURCE_CREATE", "LocalDirectoryDataSource", source.getId(),
                Map.of("name", trimmedName,
                        "directoryPath", trimmedPath,
                        "filenameGlob", trimmedGlob));
        redirectAttributes.addFlashAttribute("success",
                "Local directory data source \"" + trimmedName + "\" added.");
        return "redirect:/admin/data-sources";
    }

    @PostMapping("/local/{id}/delete")
    public String deleteLocal(@PathVariable final String id, final RedirectAttributes redirectAttributes) {
        final LocalDirectoryDataSource source = localRepository.findById(id).orElse(null);
        if (source == null) {
            redirectAttributes.addFlashAttribute("error", "Local directory data source not found.");
            return "redirect:/admin/data-sources";
        }
        localRepository.deleteById(id);
        auditLogService.log("LOCAL_DATASOURCE_DELETE", "LocalDirectoryDataSource", id,
                Map.of("name", source.getName() == null ? "" : source.getName()));
        redirectAttributes.addFlashAttribute("success",
                "Local directory data source \"" + source.getName() + "\" removed.");
        return "redirect:/admin/data-sources";
    }
}
