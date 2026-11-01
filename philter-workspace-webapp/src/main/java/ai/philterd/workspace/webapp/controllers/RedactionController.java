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

import ai.philterd.workspace.core.model.Redaction;
import ai.philterd.workspace.core.model.RedactionResponse;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Coordinates;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.IngestStatus;
import ai.philterd.workspace.model.Location;
import ai.philterd.workspace.model.RiskScore;
import ai.philterd.workspace.model.ElasticsearchDataSource;
import ai.philterd.workspace.model.LocalDirectoryDataSource;
import ai.philterd.workspace.model.OpenSearchDataSource;
import ai.philterd.workspace.model.RelationalDbDataSource;
import ai.philterd.workspace.model.S3DataSource;
import ai.philterd.workspace.model.Span;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.workspace.repository.ElasticsearchDataSourceRepository;
import ai.philterd.workspace.repository.OpenSearchDataSourceRepository;
import ai.philterd.workspace.repository.RelationalDbDataSourceRepository;
import ai.philterd.workspace.repository.S3DataSourceRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.GeneralSettingsService;
import ai.philterd.workspace.service.DocumentSearchService;
import ai.philterd.workspace.service.RedactionService;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.service.BatchAccessService;
import ai.philterd.workspace.service.UserGroupsService;
import ai.philterd.workspace.service.IngestQueueService;
import org.springframework.security.core.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Controller
public class RedactionController {

    private static final Logger log = LoggerFactory.getLogger(RedactionController.class);

    private final RedactionService redactionService;
    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final UserGroupsService userGroupsService;
    private final BatchAccessService batchAccessService;
    private final AuditLogService auditLogService;
    private final DocumentSearchService searchService;
    private final IngestQueueService ingestQueueService;
    private final GeneralSettingsService generalSettingsService;
    private final OpenSearchDataSourceRepository dataSourceRepository;
    private final ElasticsearchDataSourceRepository esDataSourceRepository;
    private final S3DataSourceRepository s3DataSourceRepository;
    private final RelationalDbDataSourceRepository rdbDataSourceRepository;
    private final LocalDirectoryDataSourceRepository localDataSourceRepository;
    private final ai.philterd.workspace.service.OpenSearchIngestJobService openSearchIngestJobService;
    private final ai.philterd.workspace.service.ElasticsearchIngestJobService elasticsearchIngestJobService;
    private final ai.philterd.workspace.service.LocalDirectoryIngestJobService localDirectoryIngestJobService;
    private final ai.philterd.workspace.service.S3IngestJobService s3IngestJobService;
    private final ai.philterd.workspace.service.RdbIngestJobService rdbIngestJobService;
    private final ai.philterd.workspace.webapp.security.BootstrapKeyWarning bootstrapKeyWarning;
    private final ai.philterd.workspace.webapp.ui.BatchLabels batchLabels;

    public RedactionController(final RedactionService redactionService,
                               final BatchRepository batchRepository,
                               final DocumentRepository documentRepository,
                               final SpanRepository spanRepository,
                               final UserGroupsService userGroupsService,
                               final BatchAccessService batchAccessService,
                               final AuditLogService auditLogService,
                               final DocumentSearchService searchService,
                               final IngestQueueService ingestQueueService,
                               final GeneralSettingsService generalSettingsService,
                               final OpenSearchDataSourceRepository dataSourceRepository,
                               final ElasticsearchDataSourceRepository esDataSourceRepository,
                               final S3DataSourceRepository s3DataSourceRepository,
                               final RelationalDbDataSourceRepository rdbDataSourceRepository,
                               final LocalDirectoryDataSourceRepository localDataSourceRepository,
                               final ai.philterd.workspace.service.OpenSearchIngestJobService openSearchIngestJobService,
                               final ai.philterd.workspace.service.ElasticsearchIngestJobService elasticsearchIngestJobService,
                               final ai.philterd.workspace.service.LocalDirectoryIngestJobService localDirectoryIngestJobService,
                               final ai.philterd.workspace.service.S3IngestJobService s3IngestJobService,
                               final ai.philterd.workspace.service.RdbIngestJobService rdbIngestJobService,
                               final ai.philterd.workspace.webapp.security.BootstrapKeyWarning bootstrapKeyWarning,
                               final ai.philterd.workspace.webapp.ui.BatchLabels batchLabels) {
        this.redactionService = redactionService;
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.userGroupsService = userGroupsService;
        this.batchAccessService = batchAccessService;
        this.auditLogService = auditLogService;
        this.searchService = searchService;
        this.ingestQueueService = ingestQueueService;
        this.generalSettingsService = generalSettingsService;
        this.dataSourceRepository = dataSourceRepository;
        this.esDataSourceRepository = esDataSourceRepository;
        this.s3DataSourceRepository = s3DataSourceRepository;
        this.rdbDataSourceRepository = rdbDataSourceRepository;
        this.localDataSourceRepository = localDataSourceRepository;
        this.openSearchIngestJobService = openSearchIngestJobService;
        this.elasticsearchIngestJobService = elasticsearchIngestJobService;
        this.localDirectoryIngestJobService = localDirectoryIngestJobService;
        this.s3IngestJobService = s3IngestJobService;
        this.rdbIngestJobService = rdbIngestJobService;
        this.bootstrapKeyWarning = bootstrapKeyWarning;
        this.batchLabels = batchLabels;
    }

    @GetMapping("/")
    public String dashboard(final Authentication authentication, final Model model) {
        // Auditors see the same dashboard rollups admins see — both are read-everywhere.
        final boolean admin = AuthUtils.isAdminOrAuditor(authentication);
        final Set<String> myGroupIds = admin
                ? Set.of()
                : userGroupsService.groupIdsForEmail(authentication == null ? null : authentication.getName());

        final org.springframework.data.domain.Page<Batch> batchPage =
                batchRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        final List<Batch> batches = new ArrayList<>(batchPage != null ? batchPage.getContent() : List.of());
        if (!admin) {
            batches.removeIf(b -> b.getGroupId() == null || !myGroupIds.contains(b.getGroupId()));
        }

        final long totalBatches = batches.size();
        final long openBatches = batches.stream().filter(b -> !b.isClosed()).count();
        final long closedBatches = totalBatches - openBatches;

        long totalDocuments = 0;
        long needsReview = 0;
        long autoApproved = 0;
        long approved = 0;
        long pending = 0;
        long failed = 0;
        for (Batch b : batches) {
            totalDocuments += documentRepository.countByBatchId(b.getId());
            needsReview += documentRepository.countByBatchIdAndStatusIn(
                    b.getId(), Set.of("REVIEW_REQUIRED", "AUDIT_REQUIRED"));
            autoApproved += documentRepository.countByBatchIdAndStatusIn(
                    b.getId(), Set.of("AUTO_APPROVED"));
            approved += documentRepository.countByBatchIdAndStatusIn(
                    b.getId(), Set.of("APPROVED"));
            pending += documentRepository.countByBatchIdAndStatusIn(
                    b.getId(), Set.of("PENDING"));
            failed += documentRepository.countByBatchIdAndStatusIn(
                    b.getId(), Set.of("FAILED", "REJECTED"));
        }

        model.addAttribute("totalBatches", totalBatches);
        model.addAttribute("openBatches", openBatches);
        model.addAttribute("closedBatches", closedBatches);
        model.addAttribute("totalDocuments", totalDocuments);
        model.addAttribute("needsReview", needsReview);
        model.addAttribute("autoApproved", autoApproved);
        model.addAttribute("approved", approved);
        model.addAttribute("pending", pending);
        model.addAttribute("failed", failed);
        model.addAttribute("isAdmin", admin);
        model.addAttribute("bootstrapKeyActive", bootstrapKeyWarning.show(authentication));
        model.addAttribute("bootstrapKeyDocs", ai.philterd.workspace.webapp.security.BootstrapKeyWarning.DOCS_URL);
        return "dashboard";
    }

    @GetMapping("/queue")
    public String queue(final Authentication authentication, final Model model) {
        model.addAttribute("isAdmin", AuthUtils.isAdmin(authentication));
        // The audit trail with Philter's ledger (#18) is for administrators and auditors.
        model.addAttribute("canExportTrail", AuthUtils.isAdminOrAuditor(authentication));
        return "queue";
    }

    @GetMapping("/upload")
    public String upload(final Authentication authentication, final Model model) {
        // Same cross-group read scope for admins and auditors. The actual upload POST
        // below is gated by AuditorWriteRejectFilter, so an auditor reaches this page
        // but cannot submit it.
        final boolean admin = AuthUtils.isAdminOrAuditor(authentication);
        final Set<String> myGroupIds = admin
                ? Set.of()
                : userGroupsService.groupIdsForEmail(authentication == null ? null : authentication.getName());
        final org.springframework.data.domain.Page<Batch> batchPage2 =
                batchRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        final List<Batch> batches = new ArrayList<>(batchPage2 != null ? batchPage2.getContent() : List.of());
        batches.removeIf(Batch::isClosed);
        if (!admin) {
            batches.removeIf(b -> b.getGroupId() == null || !myGroupIds.contains(b.getGroupId()));
        }
        batches.sort(Comparator.comparing(Batch::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
        model.addAttribute("batches", batches);
        model.addAttribute("batchLabels", batchLabels.of(batches));
        model.addAttribute("caseTypes", generalSettingsService.load().getCaseTypePolicies().stream()
                .map(ai.philterd.workspace.model.CaseTypePolicy::getCaseType).toList());

        final org.springframework.data.domain.Page<OpenSearchDataSource> dataSourcePage =
                dataSourceRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        model.addAttribute("dataSources",
                dataSourcePage != null ? dataSourcePage.getContent() : List.of());

        final org.springframework.data.domain.Page<ElasticsearchDataSource> esPage =
                esDataSourceRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        model.addAttribute("esDataSources",
                esPage != null ? esPage.getContent() : List.of());

        final org.springframework.data.domain.Page<S3DataSource> s3Page =
                s3DataSourceRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        model.addAttribute("s3DataSources",
                s3Page != null ? s3Page.getContent() : List.of());

        final org.springframework.data.domain.Page<RelationalDbDataSource> rdbPage =
                rdbDataSourceRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        model.addAttribute("rdbDataSources",
                rdbPage != null ? rdbPage.getContent() : List.of());

        final org.springframework.data.domain.Page<LocalDirectoryDataSource> localPage =
                localDataSourceRepository.findAll(PageRequest.of(0, 500, Sort.by("name")));
        model.addAttribute("localDataSources",
                localPage != null ? localPage.getContent() : List.of());

        return "index";
    }

    /**
     * Dispatch an "ingest from data source" click on the Add Documents page. Looks at
     * {@code sourceType} and hands off to the matching {@code *IngestJobService}, which
     * records a PENDING {@link ai.philterd.workspace.model.BackgroundJob} and returns
     * immediately; {@link ai.philterd.workspace.service.DataImportDispatcher} promotes
     * it to RUNNING when a worker slot is free. The user is redirected to the
     * Background Jobs page so they can watch progress.
     */
    @PostMapping("/ingest-from-source")
    public String ingestFromSource(@RequestParam("sourceType") final String sourceType,
                                   @RequestParam("batchId") final String batchId,
                                   @RequestParam("dataSourceId") final String dataSourceId,
                                   @RequestParam(value = "priority", defaultValue = "2") final int priority,
                                   final Authentication authentication,
                                   final RedirectAttributes redirectAttributes) {
        // Authorization: the caller must be able to access the chosen batch. Without this
        // check, any authenticated user could ingest documents into any batch in the
        // system. Match the redirect target to the source type so users land on a useful
        // page after the error (the data-source ingest tabs all live on /upload).
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !batchAccessService.canAccessBatch(authentication, batch)) {
            redirectAttributes.addFlashAttribute("error",
                    "Selected batch is not available.");
            return "redirect:/upload";
        }
        if (batch.isClosed()) {
            redirectAttributes.addFlashAttribute("error",
                    "Batch \"" + batch.getName() + "\" is closed and cannot accept new documents.");
            return "redirect:/upload";
        }
        if ("opensearch".equals(sourceType)) {
            final String email = authentication == null ? null : authentication.getName();
            final ai.philterd.workspace.model.BackgroundJob job =
                    openSearchIngestJobService.start(dataSourceId, batchId, priority, email);
            if (ai.philterd.workspace.model.BackgroundJob.STATUS_FAILED.equals(job.getStatus())) {
                redirectAttributes.addFlashAttribute("error",
                        "Could not start OpenSearch ingest: " + job.getErrorMessage());
            } else {
                redirectAttributes.addFlashAttribute("success",
                        "OpenSearch ingest started. Watch its progress on the Background Jobs page.");
            }
            return "redirect:/jobs";
        }
        if ("elasticsearch".equals(sourceType)) {
            final String email = authentication == null ? null : authentication.getName();
            final ai.philterd.workspace.model.BackgroundJob job =
                    elasticsearchIngestJobService.start(dataSourceId, batchId, priority, email);
            if (ai.philterd.workspace.model.BackgroundJob.STATUS_FAILED.equals(job.getStatus())) {
                redirectAttributes.addFlashAttribute("error",
                        "Could not start Elasticsearch ingest: " + job.getErrorMessage());
            } else {
                redirectAttributes.addFlashAttribute("success",
                        "Elasticsearch ingest started. Watch its progress on the Background Jobs page.");
            }
            return "redirect:/jobs";
        }
        if ("local".equals(sourceType)) {
            final String email = authentication == null ? null : authentication.getName();
            final ai.philterd.workspace.model.BackgroundJob job =
                    localDirectoryIngestJobService.start(dataSourceId, batchId, priority, email);
            if (ai.philterd.workspace.model.BackgroundJob.STATUS_FAILED.equals(job.getStatus())) {
                redirectAttributes.addFlashAttribute("error",
                        "Could not start local-directory ingest: " + job.getErrorMessage());
            } else {
                redirectAttributes.addFlashAttribute("success",
                        "Local-directory ingest started. Watch its progress on the Background Jobs page.");
            }
            return "redirect:/jobs";
        }
        if ("s3".equals(sourceType)) {
            final String email = authentication == null ? null : authentication.getName();
            final ai.philterd.workspace.model.BackgroundJob job =
                    s3IngestJobService.start(dataSourceId, batchId, priority, email);
            if (ai.philterd.workspace.model.BackgroundJob.STATUS_FAILED.equals(job.getStatus())) {
                redirectAttributes.addFlashAttribute("error",
                        "Could not start S3 ingest: " + job.getErrorMessage());
            } else {
                redirectAttributes.addFlashAttribute("success",
                        "S3 ingest started. Watch its progress on the Background Jobs page.");
            }
            return "redirect:/jobs";
        }
        if ("rdb".equals(sourceType)) {
            final String email = authentication == null ? null : authentication.getName();
            final ai.philterd.workspace.model.BackgroundJob job =
                    rdbIngestJobService.start(dataSourceId, batchId, priority, email);
            if (ai.philterd.workspace.model.BackgroundJob.STATUS_FAILED.equals(job.getStatus())) {
                redirectAttributes.addFlashAttribute("error",
                        "Could not start relational database ingest: " + job.getErrorMessage());
            } else {
                redirectAttributes.addFlashAttribute("success",
                        "Relational database ingest started. "
                                + "Watch its progress on the Background Jobs page.");
            }
            return "redirect:/jobs";
        }
        redirectAttributes.addFlashAttribute("error",
                "Unknown data source type: \"" + sourceType + "\".");
        return "redirect:/upload";
    }

    @PostMapping("/redact")
    public String redact(@RequestParam("file") final MultipartFile[] files,
                         @RequestParam("batchId") final String batchId,
                         @RequestParam(value = "priority", defaultValue = "2") final int priority,
                         @RequestParam(value = "caseNumber", required = false) final String caseNumber,
                         @RequestParam(value = "caseType", required = false) final String caseType,
                         @RequestParam(value = "filer", required = false) final String filer,
                         @RequestParam(value = "feeStatus", required = false) final String feeStatus,
                         @RequestParam(value = "ifp", required = false) final String ifp,
                         final Authentication authentication,
                         final Model model,
                         final HttpSession session,
                         final RedirectAttributes redirectAttributes) throws IOException {
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || !batchAccessService.canAccessBatch(authentication, batch)) {
            redirectAttributes.addFlashAttribute("error", "Selected batch no longer exists.");
            return "redirect:/upload";
        }
        if (batch.isClosed()) {
            redirectAttributes.addFlashAttribute("error",
                    "Batch \"" + batch.getName() + "\" is closed and cannot accept new documents.");
            return "redirect:/upload";
        }
        if (files == null || files.length == 0
                || java.util.Arrays.stream(files).allMatch(MultipartFile::isEmpty)) {
            redirectAttributes.addFlashAttribute("error", "Pick at least one file to upload.");
            return "redirect:/upload";
        }

        // The filing's case metadata (#16), applied to every file in this upload.
        final ai.philterd.workspace.model.CaseMetadata caseMetadata;
        try {
            caseMetadata = ai.philterd.workspace.model.CaseMetadata.of(caseNumber, caseType, filer, feeStatus,
                    ifp == null || ifp.isBlank() ? null : Boolean.valueOf(ifp));
        } catch (ai.philterd.workspace.model.CaseMetadata.InvalidException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/upload";
        }

        final long maxBytes = generalSettingsService.load().getMaxUploadFileSizeBytes();
        final int safePriority = (priority >= 1 && priority <= 3) ? priority : 2;

        // Best-effort per file: a single oversized or unreadable file should not
        // sink the whole batch — queue what we can, report what we couldn't.
        int queuedCount = 0;
        final java.util.List<String> rejected = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            final String originalFilename = file.getOriginalFilename();
            if (file.getSize() > maxBytes) {
                rejected.add((originalFilename == null ? "(unnamed)" : originalFilename)
                        + " — exceeds " + String.format("%.2f", maxBytes / 1048576.0) + " MB");
                continue;
            }
            final String contentType = file.getContentType();
            final byte[] fileBytes = file.getBytes();
            final Document queued;
            if (MediaType.APPLICATION_PDF_VALUE.equals(contentType)) {
                queued = ingestQueueService.enqueueFile(batch, originalFilename, fileBytes, contentType, safePriority,
                        copy(caseMetadata));
            } else {
                final String text = new String(fileBytes, StandardCharsets.UTF_8);
                queued = ingestQueueService.enqueueText(batch, originalFilename, text, safePriority,
                        copy(caseMetadata));
            }
            // Filename is hashed (R2-F7) — filenames routinely carry PII
            // (patient names, MRNs, tax-form filers) and the audit log is
            // cross-group-readable by auditors. The document row keeps the
            // plaintext filename behind encryption-at-rest for callers with
            // legitimate access.
            auditLogService.log("DOCUMENT_QUEUED", "Document", queued.getId(),
                    Map.of(
                            "batchId", batch.getId(),
                            "filenameHash", auditLogService.hashForAudit(originalFilename),
                            "filenameLength", originalFilename == null ? 0 : originalFilename.length(),
                            "contentType", contentType == null ? "" : contentType,
                            "size", fileBytes.length,
                            "caseType", caseMetadata == null || caseMetadata.getCaseType() == null
                                    ? "" : caseMetadata.getCaseType()));
            queuedCount++;
        }

        if (queuedCount == 0) {
            redirectAttributes.addFlashAttribute("error",
                    "No documents were queued. " + String.join("; ", rejected));
            return "redirect:/upload";
        }
        final StringBuilder msg = new StringBuilder();
        msg.append(queuedCount).append(queuedCount == 1 ? " document was" : " documents were")
                .append(" queued for redaction.");
        if (!rejected.isEmpty()) {
            msg.append(" Skipped: ").append(String.join("; ", rejected)).append('.');
        }
        redirectAttributes.addFlashAttribute("success", msg.toString());
        return "redirect:/upload";
    }

    private Document persistDocument(Batch batch, String filename, RedactionResponse response) {
        final String originalText = response.getOriginalText() == null ? "" : response.getOriginalText();
        final List<Redaction> redactions = response.getRedactions() == null ? List.of() : response.getRedactions();
        final double threshold = batch.getConfidenceThreshold();

        final Document document = new Document();
        document.setId(UUID.randomUUID().toString());
        document.setBatchId(batch.getId());
        document.setCreatedAt(LocalDateTime.now());
        document.setFilename(filename);
        document.setOriginalText(originalText);

        final List<Span> spans = new ArrayList<>();
        if (!redactions.isEmpty()) {
            final List<Redaction> ordered = new ArrayList<>(redactions);
            ordered.sort(Comparator.comparingInt(Redaction::getStart));
            int cursor = 0;
            for (Redaction r : ordered) {
                final int start = originalText.indexOf(r.getText(), cursor);
                if (start < 0) {
                    continue;
                }
                final int end = start + r.getText().length();
                final LocalDateTime now = LocalDateTime.now();
                final Span span = new Span();
                span.setId(UUID.randomUUID().toString());
                span.setDocumentId(document.getId());
                span.setType(r.getType());
                span.setText(r.getText());
                span.setConfidence(r.getConfidence());
                span.setCreatedAt(now);
                span.changeStatus(r.getConfidence() >= threshold ? "APPROVED" : "PENDING");
                span.setLocation(new Location(start, end, Math.max(r.getPageNumber(), 1),
                        new Coordinates(r.getLowerLeftX(), r.getLowerLeftY(),
                                r.getUpperRightX() - r.getLowerLeftX(),
                                r.getUpperRightY() - r.getLowerLeftY())));
                spans.add(span);
                cursor = end;
            }
        }
        document.setRiskScore(RiskScore.compute(spans, originalText, batch.getPiiTypeWeights()));

        final boolean needsReview = !spans.isEmpty()
                && spans.stream().anyMatch(s -> "PENDING".equals(s.getStatus()));
        document.changeStatus(IngestStatus.pick(batch, needsReview));
        documentRepository.save(document);
        if (!spans.isEmpty()) {
            spanRepository.saveAll(spans);
        }
        searchService.indexDocument(document);
        return document;
    }


    /**
     * Content types this controller is willing to stream back to the browser. Anything
     * outside this set is rejected (400) — the user-supplied {@code contentType} request
     * parameter cannot be used to coerce Philter Workspace into serving e.g. {@code text/html} that
     * would otherwise be a stored-XSS vector if the {@code Content-Disposition: attachment}
     * header is ever stripped.
     */
    private static final java.util.Set<String> ALLOWED_DOWNLOAD_CONTENT_TYPES = java.util.Set.of(
            MediaType.TEXT_PLAIN_VALUE,
            MediaType.APPLICATION_PDF_VALUE);

    /** Maximum length of a sanitized filename before truncation. */
    private static final int MAX_FILENAME_LENGTH = 200;

    /**
     * Sanitize a user-supplied filename so it's safe to put into a {@code Content-Disposition}
     * header. Strips:
     *
     * <ul>
     *   <li>Control characters (incl. CR/LF — defends against header injection / response
     *       splitting even if the framework already strips these).
     *   <li>Path separators ({@code /} and {@code \}) so the value can never look like a
     *       directory traversal or an absolute path.
     *   <li>Double quotes so the quoted-string in the header can never be broken out of.
     *   <li>Leading dots (so the value can't be coerced into hidden-file or
     *       parent-directory shapes).
     * </ul>
     *
     * Truncates to {@link #MAX_FILENAME_LENGTH} characters. Returns {@code fallback} when the
     * sanitized result is empty.
     */
    static String safeFilename(final String input, final String fallback) {
        if (input == null) return fallback;
        final StringBuilder sb = new StringBuilder(Math.min(input.length(), MAX_FILENAME_LENGTH));
        for (int i = 0; i < input.length() && sb.length() < MAX_FILENAME_LENGTH; i++) {
            final char c = input.charAt(i);
            if (c < 0x20 || c == 0x7F) continue; // control chars
            if (c == '/' || c == '\\') continue; // path separators
            if (c == '"') continue;              // header-quote breakout
            sb.append(c);
        }
        // Trim leading dots so the result can't look like a hidden file or "..".
        int start = 0;
        while (start < sb.length() && sb.charAt(start) == '.') start++;
        final String cleaned = sb.substring(start).trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    /**
     * The PDF with the reviewed spans applied, or a 409 naming why it can't be produced. Never the
     * original in place of a redacted file. The refusal is audited without the document's content.
     */
    private byte[] redactedPdfOrRefuse(final byte[] originalFile, final RedactionResponse redactionResponse) {
        try {
            return redactionService.getRedactedPdf(new ByteArrayInputStream(originalFile), redactionResponse);
        } catch (IOException e) {
            final String reason = e.getMessage() == null ? "The redacted PDF could not be produced." : e.getMessage();
            auditLogService.log("REDACTED_PDF_REFUSED", "Redaction", null, Map.of("reason", reason));
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, reason);
        }
    }

    @PostMapping("/preview")
    public ResponseEntity<Resource> preview(
            @RequestParam("redactedText") String redactedText,
            @RequestParam("fileName") String fileName,
            @RequestParam("contentType") String contentType,
            @RequestBody RedactionResponse redactionResponse,
            HttpSession session) throws IOException {

        // Only PDF previews are supported. Any other content type — including a malicious
        // text/html or application/javascript — is rejected outright.
        if (MediaType.APPLICATION_PDF_VALUE.equals(contentType)) {
            final byte[] originalFile = (byte[]) session.getAttribute("originalFile");
            if (originalFile != null) {
                final byte[] redactedPdf = redactedPdfOrRefuse(originalFile, redactionResponse);
                final ByteArrayResource resource = new ByteArrayResource(redactedPdf);
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(resource);
            }
        }

        return ResponseEntity.notFound().build();
    }

    @PostMapping("/download")
    public ResponseEntity<Resource> download(
            @RequestParam("redactedText") String redactedText,
            @RequestParam("fileName") String fileName,
            @RequestParam("contentType") String contentType,
            @ModelAttribute RedactionResponse redactionResponse,
            HttpSession session) throws IOException {

        // Allow-list the requested content type. Unknown/forbidden types get 400 — there's
        // no legitimate reason for the form to ask for anything outside this small set, and
        // the allow-list prevents the endpoint from being weaponized as a stored-XSS vector
        // if Content-Disposition is ever stripped or ignored.
        if (contentType == null || !ALLOWED_DOWNLOAD_CONTENT_TYPES.contains(contentType)) {
            return ResponseEntity.badRequest().build();
        }

        byte[] content;
        String resolvedContentType = contentType;
        String workingFileName = fileName;
        if (MediaType.APPLICATION_PDF_VALUE.equals(contentType)) {
            final byte[] originalFile = (byte[]) session.getAttribute("originalFile");
            if (originalFile != null) {
                content = redactedPdfOrRefuse(originalFile, redactionResponse);
            } else {
                content = redactedText.getBytes(StandardCharsets.UTF_8);
                workingFileName = (workingFileName == null ? "" : workingFileName)
                        .replace(".pdf", "_redacted.txt");
                resolvedContentType = MediaType.TEXT_PLAIN_VALUE;
            }
        } else {
            content = redactedText.getBytes(StandardCharsets.UTF_8);
            workingFileName = (workingFileName == null ? "" : workingFileName)
                    .replace(".", "_redacted.");
        }

        final String safeName = safeFilename(workingFileName,
                MediaType.APPLICATION_PDF_VALUE.equals(resolvedContentType)
                        ? "redacted.pdf" : "redacted.txt");
        final ByteArrayResource resource = new ByteArrayResource(content);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "\"")
                .contentType(MediaType.parseMediaType(resolvedContentType))
                .body(resource);
    }

    /** Each document gets its own copy, since the stores encrypt its case number and filer in place. */
    private static ai.philterd.workspace.model.CaseMetadata copy(final ai.philterd.workspace.model.CaseMetadata m) {
        return m == null ? null : new ai.philterd.workspace.model.CaseMetadata(m.getCaseNumber(), m.getCaseType(),
                m.getFiler(), m.getFeeStatus(), m.getIfp());
    }
}
