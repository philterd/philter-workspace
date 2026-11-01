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

import ai.philterd.workspace.model.AuditLog;
import ai.philterd.workspace.service.AuditLogQueryService;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.util.Csv;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Controller
@RequestMapping("/admin/audit")
public class AuditLogAdminController {

    private static final int EXPORT_LIMIT = 100_000;
    private static final int PREVIEW_LIMIT = 10;

    private final AuditLogQueryService auditLogQueryService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public AuditLogAdminController(final AuditLogQueryService auditLogQueryService, final ObjectMapper objectMapper,
                                   final AuditLogService auditLogService) {
        this.auditLogQueryService = auditLogQueryService;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String form(final Model model) {
        return "admin-audit";
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestParam(name = "startTime", required = false) final String startTime,
                                    @RequestParam(name = "endTime", required = false) final String endTime,
                                    @RequestParam(name = "userEmail", required = false) final String userEmail,
                                    @RequestParam(name = "resourceType", required = false) final String resourceType,
                                    @RequestParam(name = "resourceId", required = false) final String resourceId,
                                    @RequestParam(name = "format", defaultValue = "json") final String format,
                                    final HttpServletResponse response) throws IOException {
        final Instant start = parseInstant(startTime, "startTime");
        final Instant end = parseInstant(endTime, "endTime");
        if (start != null && end != null && start.isAfter(end)) {
            throw new ResponseStatusException(BAD_REQUEST, "startTime must be before endTime");
        }

        final String fmt = format == null ? "json" : format.trim().toLowerCase();
        if (!"json".equals(fmt) && !"csv".equals(fmt)) {
            throw new ResponseStatusException(BAD_REQUEST, "format must be 'json' or 'csv'");
        }

        final List<AuditLog> entries = auditLogQueryService.find(
                start, end, userEmail, resourceType, resourceId, EXPORT_LIMIT);

        // #62: recorded before the download is written, with or without rows. The user and resource ID filters are
        // free text, so they are kept as keyed hashes, as search queries are.
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("format", fmt);
        details.put("startTime", start == null ? "" : start.toString());
        details.put("endTime", end == null ? "" : end.toString());
        details.put("userEmailHash", auditLogService.hashForAudit(trimmed(userEmail)));
        details.put("resourceType", trimmed(resourceType));
        details.put("resourceIdHash", auditLogService.hashForAudit(trimmed(resourceId)));
        details.put("entries", entries.size());
        details.put("truncated", entries.size() >= EXPORT_LIMIT);
        auditLogService.log("AUDIT_LOG_EXPORT", "AuditLog", null, details);

        final String filename = "audit-log-" + Instant.now().toString().replace(':', '-');

        if ("csv".equals(fmt)) {
            response.setContentType("text/csv");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + ".csv\"");
            try (final Writer w = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
                writeCsv(w, entries);
            }
            return null;
        }

        final byte[] body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(entries);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * JSON preview of the first {@link #PREVIEW_LIMIT} entries that the export form's
     * current filter set would return. Same query as {@link #export} so the preview
     * faithfully represents what the download will contain — if the preview is empty,
     * the export will be empty too.
     */
    @GetMapping("/preview")
    @ResponseBody
    public List<AuditLog> preview(@RequestParam(name = "startTime", required = false) final String startTime,
                                  @RequestParam(name = "endTime", required = false) final String endTime,
                                  @RequestParam(name = "userEmail", required = false) final String userEmail,
                                  @RequestParam(name = "resourceType", required = false) final String resourceType,
                                  @RequestParam(name = "resourceId", required = false) final String resourceId) {
        final Instant start = parseInstant(startTime, "startTime");
        final Instant end = parseInstant(endTime, "endTime");
        if (start != null && end != null && start.isAfter(end)) {
            throw new ResponseStatusException(BAD_REQUEST, "startTime must be before endTime");
        }
        return auditLogQueryService.find(start, end, userEmail, resourceType, resourceId, PREVIEW_LIMIT);
    }

    /** Hard cap on page size accepted from the query UI. The service caps to 200
     *  defensively; this matches that ceiling so the validation error message and
     *  the actual behavior agree. */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Paged investigation query for the Query Audit Log tab. Returns a slice of
     * entries plus the total matching count so the client can render pager
     * controls. Sorted newest-first — investigators are typically chasing the
     * most recent activity, not the oldest.
     */
    @GetMapping("/query")
    @ResponseBody
    public AuditLogQueryService.Result query(@RequestParam(name = "startTime", required = false) final String startTime,
                                             @RequestParam(name = "endTime", required = false) final String endTime,
                                             @RequestParam(name = "userEmail", required = false) final String userEmail,
                                             @RequestParam(name = "action", required = false) final String action,
                                             @RequestParam(name = "resourceType", required = false) final String resourceType,
                                             @RequestParam(name = "resourceId", required = false) final String resourceId,
                                             @RequestParam(name = "outcome", required = false) final String outcome,
                                             @RequestParam(name = "ipAddress", required = false) final String ipAddress,
                                             @RequestParam(name = "page", defaultValue = "0") final int page,
                                             @RequestParam(name = "pageSize", defaultValue = "25") final int pageSize) {
        final Instant start = parseInstant(startTime, "startTime");
        final Instant end = parseInstant(endTime, "endTime");
        if (start != null && end != null && start.isAfter(end)) {
            throw new ResponseStatusException(BAD_REQUEST, "startTime must be before endTime");
        }
        if (page < 0) {
            throw new ResponseStatusException(BAD_REQUEST, "page must be >= 0");
        }
        if (pageSize <= 0 || pageSize > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        final AuditLogQueryService.QueryFilters filters = new AuditLogQueryService.QueryFilters(
                start, end, userEmail, action, resourceType, resourceId, outcome, ipAddress);
        return auditLogQueryService.query(filters, page, pageSize);
    }

    /** A filter as the query uses it: trimmed, or empty when not given. */
    private static String trimmed(final String value) {
        return value == null ? "" : value.trim();
    }

    private void writeCsv(final Writer w, final List<AuditLog> entries) throws IOException {
        w.write("timestamp,userEmail,userId,action,resourceType,resourceId,outcome,ipAddress,details\n");
        for (AuditLog e : entries) {
            w.write(Csv.escapeField(e.getTimestamp() == null ? "" : e.getTimestamp().toString()));
            w.write(',');
            w.write(Csv.escapeField(e.getUserEmail()));
            w.write(',');
            w.write(Csv.escapeField(e.getUserId()));
            w.write(',');
            w.write(Csv.escapeField(e.getAction()));
            w.write(',');
            w.write(Csv.escapeField(e.getResourceType()));
            w.write(',');
            w.write(Csv.escapeField(e.getResourceId()));
            w.write(',');
            w.write(Csv.escapeField(e.getOutcome()));
            w.write(',');
            w.write(Csv.escapeField(e.getIpAddress()));
            w.write(',');
            w.write(Csv.escapeField(detailsAsJson(e.getDetails())));
            w.write('\n');
        }
    }

    private String detailsAsJson(final Object details) {
        if (details == null) return "";
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JacksonException e) {
            return "";
        }
    }

    static Instant parseInstant(final String raw, final String fieldName) {
        if (raw == null || raw.isBlank()) return null;
        final String trimmed = raw.trim();
        try {
            // Form input type=datetime-local emits "yyyy-MM-ddTHH:mm" (no zone).
            if (trimmed.length() <= 19 && !trimmed.endsWith("Z") && !trimmed.contains("+")) {
                return LocalDateTime.parse(trimmed).atZone(ZoneId.systemDefault()).toInstant();
            }
            return Instant.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid " + fieldName + ": " + raw);
        }
    }
}
