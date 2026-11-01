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
package ai.philterd.workspace.webapp;

import ai.philterd.workspace.core.model.Redaction;
import ai.philterd.workspace.core.model.RedactionResponse;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.repository.AuditLogRepository;
import ai.philterd.workspace.repository.DocumentCommentRepository;
import ai.philterd.workspace.repository.GroupRepository;
import ai.philterd.workspace.repository.LlmJudgeDefaultsRepository;
import ai.philterd.workspace.repository.GeneralSettingsRepository;
import ai.philterd.workspace.repository.InboxMessageRepository;
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
import ai.philterd.workspace.repository.UserSettingsRepository;
import ai.philterd.workspace.repository.WeightSetRepository;
import ai.philterd.workspace.repository.SpanRepository;
import ai.philterd.workspace.repository.UserRepository;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.service.GeneralSettingsService;
import ai.philterd.workspace.service.DestinationTester;
import ai.philterd.workspace.service.RedactionService;
import ai.philterd.workspace.webapp.controllers.RedactionController;
import org.springframework.data.mongodb.core.MongoOperations;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(RedactionController.class)
@Import({SecurityConfig.class,
        ai.philterd.workspace.service.BatchAccessService.class})
public class RedactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ai.philterd.workspace.webapp.security.PhilterApiKeys philterApiKeys;

    @MockitoBean
    private ai.philterd.workspace.webapp.security.PhilterSignInService philterSignInService;

    @MockitoBean
    private ai.philterd.workspace.webapp.security.PhilterSignInFlow philterSignInFlow;

    /** HealthController lands in this slice and needs an endpoint @WebMvcTest does not provide. */
    @MockitoBean
    private org.springframework.boot.health.actuate.endpoint.HealthEndpoint healthEndpoint;

    @MockitoBean
    private RedactionService redactionService;

    @MockitoBean
    private SpanRepository spanRepository;

    @MockitoBean
    private DocumentRepository documentRepository;

    @MockitoBean
    private BatchRepository batchRepository;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private GroupRepository groupRepository;

    @MockitoBean
    private AuditLogRepository auditLogRepository;

    @MockitoBean
    private NotificationSettingsRepository notificationSettingsRepository;

    @MockitoBean
    private GeneralSettingsRepository generalSettingsRepository;

    @MockitoBean
    private OllamaInstanceRepository ollamaInstanceRepository;

    @MockitoBean
    private OpenSearchDataSourceRepository openSearchDataSourceRepository;

    @MockitoBean
    private ai.philterd.workspace.repository.BackgroundJobRepository backgroundJobRepository;

    @MockitoBean
    private ai.philterd.workspace.repository.DataImportLogEntryRepository dataImportLogEntryRepository;

    @MockitoBean
    private ai.philterd.workspace.repository.ElasticsearchDataSourceRepository elasticsearchDataSourceRepository;

    @MockitoBean
    private S3DataSourceRepository s3DataSourceRepository;

    @MockitoBean
    private RelationalDbDataSourceRepository relationalDbDataSourceRepository;

    @MockitoBean
    private LocalDirectoryDataSourceRepository localDirectoryDataSourceRepository;

    @MockitoBean
    private LocalDirectoryDestinationRepository localDirectoryDestinationRepository;

    @MockitoBean
    private S3DestinationRepository s3DestinationRepository;

    @MockitoBean
    private DestinationTester destinationTester;

    @MockitoBean
    private ai.philterd.workspace.service.PhilterSettings philterSettings;


    @MockitoBean
    private DocumentCommentRepository documentCommentRepository;

    @MockitoBean
    private WeightSetRepository weightSetRepository;

    @MockitoBean
    private LlmJudgeDefaultsRepository llmJudgeDefaultsRepository;

    @MockitoBean
    private UserSettingsRepository userSettingsRepository;

    @MockitoBean
    private InboxMessageRepository inboxMessageRepository;

    @MockitoBean
    private PendingUploadRepository pendingUploadRepository;

    @MockitoBean
    private RedactionCertificateRepository redactionCertificateRepository;

    @MockitoBean
    private ai.philterd.workspace.repository.FinalizationPolicyRepository finalizationPolicyRepository;

    @MockitoBean
    private ai.philterd.workspace.repository.ComplianceProfileRepository complianceProfileRepository;

    @MockitoBean
    private MongoOperations mongoOperations;

    @MockitoBean
    private ai.philterd.workspace.repository.PhilterWebhookDeliveryRepository philterWebhookDeliveryRepository;

    @MockitoBean
    private ai.philterd.workspace.repository.DetectionRuleRepository detectionRuleRepository;

    @MockitoBean
    private ai.philterd.workspace.service.DetectionRuleService detectionRuleService;

    @MockitoBean
    private ai.philterd.workspace.service.CaseMetadataService caseMetadataService;

    @MockitoBean
    private GeneralSettingsService generalSettingsService;

    @org.junit.jupiter.api.BeforeEach
    void defaultSettings() {
        // A real settings row, as GeneralSettingsService.load() always returns; tests that need others stub their own.
        when(generalSettingsService.load()).thenReturn(new ai.philterd.workspace.model.GeneralSettings());
    }

    @Test
    public void testDashboard() throws Exception {
        mockMvc.perform(get("/").with(signedIn("user", "USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"));
    }

    @Test
    public void testUpload() throws Exception {
        mockMvc.perform(get("/upload").with(signedIn("user", "USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    public void batchesWithTheSameNameAreToldApartByGroupInThePickers() throws Exception {
        // Names are unique only within a group (#83), so an administrator can see two batches named alike.
        final java.util.List<Batch> batches = new java.util.ArrayList<>();
        for (String[] b : new String[][] {{"b1", "Sample", "g1"}, {"b2", "Sample", "g2"}, {"b3", "Other", "g1"}}) {
            final Batch batch = new Batch();
            batch.setId(b[0]);
            batch.setName(b[1]);
            batch.setGroupId(b[2]);
            batches.add(batch);
        }
        when(batchRepository.findAll(org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(batches));
        for (String[] g : new String[][] {{"g1", "Claims"}, {"g2", "Litigation"}}) {
            final ai.philterd.workspace.model.Group group = new ai.philterd.workspace.model.Group();
            group.setId(g[0]);
            group.setName(g[1]);
            when(groupRepository.findById(g[0])).thenReturn(java.util.Optional.of(group));
        }

        final String body = mockMvc.perform(get("/upload").with(signedIn("admin", "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("value=\"b1\">Sample (Claims)</option>"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("value=\"b2\">Sample (Litigation)</option>"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("value=\"b3\">Other</option>"),
                "a name used once is shown alone");
    }

    @MockitoBean
    private ai.philterd.workspace.service.UserGroupsService userGroupsService;

    @MockitoBean
    private ai.philterd.workspace.service.OpenSearchIngestJobService openSearchIngestJobService;

    @MockitoBean
    private ai.philterd.workspace.service.ElasticsearchIngestJobService elasticsearchIngestJobService;

    @MockitoBean
    private ai.philterd.workspace.service.LocalDirectoryIngestJobService localDirectoryIngestJobService;

    @MockitoBean
    private ai.philterd.workspace.service.S3IngestJobService s3IngestJobService;

    @MockitoBean
    private ai.philterd.workspace.service.RdbIngestJobService rdbIngestJobService;

    /**
     * A non-admin reviewer with no group membership submits an OpenSearch ingest pointed at a
     * batch they do not have access to. The controller must reject the request before the
     * job service is ever called — without this check, any authenticated user could ingest
     * documents into anyone's batch.
     */
    @Test
    public void ingestFromSourceRejectedWhenUserCannotAccessBatch() throws Exception {
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Foreign batch");
        batch.setGroupId("g-foreign");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(java.util.Set.of());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "opensearch")
                        .param("batchId", "b1")
                        .param("dataSourceId", "src-1")
                        .with(csrf()).with(signedIn("user", "USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/upload"))
                .andExpect(flash().attributeExists("error"));

        org.mockito.Mockito.verify(openSearchIngestJobService, org.mockito.Mockito.never())
                .start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        org.mockito.Mockito.verify(elasticsearchIngestJobService, org.mockito.Mockito.never())
                .start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    /** Mirror for the Elasticsearch source type. */
    @Test
    public void ingestFromElasticsearchRejectedWhenUserCannotAccessBatch() throws Exception {
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Foreign batch");
        batch.setGroupId("g-foreign");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(java.util.Set.of());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "elasticsearch")
                        .param("batchId", "b1")
                        .param("dataSourceId", "src-1")
                        .with(csrf()).with(signedIn("user", "USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/upload"))
                .andExpect(flash().attributeExists("error"));

        org.mockito.Mockito.verify(openSearchIngestJobService, org.mockito.Mockito.never())
                .start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        org.mockito.Mockito.verify(elasticsearchIngestJobService, org.mockito.Mockito.never())
                .start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    /** A reviewer in the batch's group is allowed through to the job service. */
    @Test
    public void ingestFromSourceAllowedForGroupMember() throws Exception {
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Mine");
        batch.setGroupId("g-mine");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(java.util.Set.of("g-mine"));

        final ai.philterd.workspace.model.BackgroundJob job = new ai.philterd.workspace.model.BackgroundJob();
        job.setStatus(ai.philterd.workspace.model.BackgroundJob.STATUS_PENDING);
        when(openSearchIngestJobService.start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(job);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "opensearch")
                        .param("batchId", "b1")
                        .param("dataSourceId", "src-1")
                        .with(csrf()).with(signedIn("user", "USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/jobs"))
                .andExpect(flash().attributeExists("success"));

        org.mockito.Mockito.verify(openSearchIngestJobService)
                .start(org.mockito.ArgumentMatchers.eq("src-1"),
                        org.mockito.ArgumentMatchers.eq("b1"),
                        org.mockito.ArgumentMatchers.anyInt(),
                        any());
    }

    /** An admin can start an ingest into any batch. */
    @Test
    public void ingestFromSourceAllowedForAdmin() throws Exception {
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Anyone's batch");
        batch.setGroupId("g-someone");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        final ai.philterd.workspace.model.BackgroundJob job = new ai.philterd.workspace.model.BackgroundJob();
        job.setStatus(ai.philterd.workspace.model.BackgroundJob.STATUS_PENDING);
        when(openSearchIngestJobService.start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(job);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "opensearch")
                        .param("batchId", "b1")
                        .param("dataSourceId", "src-1")
                        .with(csrf()).with(signedIn("user", "ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/jobs"));
    }

    /** A request for a batch that doesn't exist is rejected. */
    @Test
    public void ingestFromSourceRejectedWhenBatchMissing() throws Exception {
        when(batchRepository.findById("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "opensearch")
                        .param("batchId", "ghost")
                        .param("dataSourceId", "src-1")
                        .with(csrf()).with(signedIn("user", "USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/upload"))
                .andExpect(flash().attributeExists("error"));

        org.mockito.Mockito.verify(openSearchIngestJobService, org.mockito.Mockito.never())
                .start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    /**
     * Relational-database ingest is wired to the dedicated {@code rdbIngestJobService}
     * and redirects the operator to the Background Jobs page on success — matching the
     * shape of the OpenSearch / Elasticsearch / S3 paths above. Pre-implementation this
     * branch returned a "not yet implemented" flash; the test guards against a
     * regression that would silently break the demo PostgreSQL data source.
     */
    @Test
    public void ingestFromSourceRdbStartsBackgroundJobAndRedirectsToJobsPage() throws Exception {
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Q4 docs");
        batch.setGroupId("g1");
        when(batchRepository.findById("b1")).thenReturn(Optional.of(batch));

        final ai.philterd.workspace.model.BackgroundJob job = new ai.philterd.workspace.model.BackgroundJob();
        job.setStatus(ai.philterd.workspace.model.BackgroundJob.STATUS_PENDING);
        when(rdbIngestJobService.start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(job);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "rdb")
                        .param("batchId", "b1")
                        .param("dataSourceId", "rdb-1")
                        .with(csrf()).with(signedIn("user", "ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/jobs"))
                .andExpect(flash().attributeExists("success"));

        org.mockito.Mockito.verify(rdbIngestJobService)
                .start(org.mockito.ArgumentMatchers.eq("rdb-1"),
                        org.mockito.ArgumentMatchers.eq("b1"),
                        org.mockito.ArgumentMatchers.anyInt(),
                        any());
    }

    /** A closed batch cannot accept new ingest jobs. */
    @Test
    public void ingestFromSourceRejectedWhenBatchClosed() throws Exception {
        final Batch closed = new Batch();
        closed.setId("b1");
        closed.setName("Closed");
        closed.setGroupId("g1");
        closed.setClosed(true);
        when(batchRepository.findById("b1")).thenReturn(Optional.of(closed));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/ingest-from-source")
                        .param("sourceType", "opensearch")
                        .param("batchId", "b1")
                        .param("dataSourceId", "src-1")
                        .with(csrf()).with(signedIn("user", "ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/upload"))
                .andExpect(flash().attributeExists("error"));

        org.mockito.Mockito.verify(openSearchIngestJobService, org.mockito.Mockito.never())
                .start(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    public void testRedactText() throws Exception {
        final String text = "George Washington lived in Mount Vernon.";

        final Batch batch = new Batch();
        batch.setId("batch-1");
        batch.setName("Test batch");

        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));

        final GeneralSettings settings = new GeneralSettings();
        settings.setMaxUploadFileSizeBytes(10L * 1024L * 1024L);
        when(generalSettingsService.load()).thenReturn(settings);

        final MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", text.getBytes());

        // The endpoint now enqueues the upload onto the redaction queue and redirects back to
        // /upload with a success flash. The actual redaction runs asynchronously in the worker.
        mockMvc.perform(multipart("/redact").file(file).param("batchId", "batch-1").with(csrf()).with(signedIn("user", "ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/upload"))
                .andExpect(flash().attributeExists("success"));
    }

    @Test
    public void uploadedFilesCarryTheCaseMetadataGivenWithThem() throws Exception {
        final Batch batch = new Batch();
        batch.setId("batch-1");
        batch.setName("Filings");
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));
        final GeneralSettings settings = new GeneralSettings();
        settings.setMaxUploadFileSizeBytes(1024);
        when(generalSettingsService.load()).thenReturn(settings);

        mockMvc.perform(multipart("/redact")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "one".getBytes()))
                        .file(new MockMultipartFile("file", "b.txt", "text/plain", "two".getBytes()))
                        .param("batchId", "batch-1").param("caseNumber", "1:26-cv-01234").param("caseType", "Civil")
                        .param("filer", "").param("feeStatus", "waived").param("ifp", "true")
                        .with(csrf()).with(signedIn("user", "ADMIN")))
                .andExpect(redirectedUrl("/upload"))
                .andExpect(flash().attributeExists("success"));

        final org.mockito.ArgumentCaptor<ai.philterd.workspace.model.Document> saved =
                org.mockito.ArgumentCaptor.forClass(ai.philterd.workspace.model.Document.class);
        org.mockito.Mockito.verify(documentRepository, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        final java.util.function.Function<String, ai.philterd.workspace.model.CaseMetadata> metadataOf = name ->
                saved.getAllValues().stream().filter(d -> name.equals(d.getFilename())).findFirst().orElseThrow()
                        .getCaseMetadata();
        final ai.philterd.workspace.model.CaseMetadata first = metadataOf.apply("a.txt");
        final ai.philterd.workspace.model.CaseMetadata second = metadataOf.apply("b.txt");
        org.junit.jupiter.api.Assertions.assertEquals("civil", first.getCaseType());
        org.junit.jupiter.api.Assertions.assertEquals("1:26-cv-01234", second.getCaseNumber());
        org.junit.jupiter.api.Assertions.assertEquals("waived", second.getFeeStatus());
        org.junit.jupiter.api.Assertions.assertEquals(true, second.getIfp());
        org.junit.jupiter.api.Assertions.assertNull(second.getFiler());
        org.junit.jupiter.api.Assertions.assertNotSame(first, second,
                "each document gets its own copy, since stores encrypt in place");
    }

    @Test
    public void invalidCaseMetadataStopsTheUpload() throws Exception {
        final Batch batch = new Batch();
        batch.setId("batch-1");
        batch.setName("Filings");
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));

        mockMvc.perform(multipart("/redact")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "one".getBytes()))
                        .param("batchId", "batch-1").param("feeStatus", "sometimes")
                        .with(csrf()).with(signedIn("user", "ADMIN")))
                .andExpect(redirectedUrl("/upload"))
                .andExpect(flash().attribute("error", "The fee status must be paid, unpaid, or waived."));
        org.mockito.Mockito.verify(documentRepository, org.mockito.Mockito.never()).save(any());
    }

    // ---------------------------------------------------------------------
    // /download — content-type allow-list & filename sanitization.
    // ---------------------------------------------------------------------

    /**
     * The user-supplied {@code contentType} request parameter is restricted to a small
     * allow-list ({@code text/plain}, {@code application/pdf}). Anything else — including
     * the classic stored-XSS shapes like {@code text/html} — is rejected with HTTP 400
     * before the response body is even built.
     */
    @Test
    public void download_rejectsForbiddenContentType_html() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/download")
                        .param("redactedText", "<script>alert(1)</script>")
                        .param("fileName", "evil.html")
                        .param("contentType", "text/html")
                        .with(csrf()).with(signedIn("user", "USER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void download_rejectsForbiddenContentType_javascript() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/download")
                        .param("redactedText", "alert(1)")
                        .param("fileName", "evil.js")
                        .param("contentType", "application/javascript")
                        .with(csrf()).with(signedIn("user", "USER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void download_rejectsBlankContentType() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/download")
                        .param("redactedText", "data")
                        .param("fileName", "x.txt")
                        .param("contentType", "")
                        .with(csrf()).with(signedIn("user", "USER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void download_refusesARedactedPdfItCannotProduceAndNeverServesTheOriginal() throws Exception {
        final byte[] original = "%PDF-1.4 original bytes".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("originalFile", original);
        when(redactionService.getRedactedPdf(any(), any())).thenThrow(new java.io.IOException());

        final org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/download")
                                .session(session)
                                .param("redactedText", "")
                                .param("fileName", "doc.pdf")
                                .param("contentType", "application/pdf")
                                .with(csrf()).with(signedIn("user", "USER")))
                .andExpect(status().isConflict())
                .andReturn();

        org.junit.jupiter.api.Assertions.assertFalse(
                java.util.Arrays.equals(original, result.getResponse().getContentAsByteArray()),
                "the original must never be served as the redacted file");
        org.junit.jupiter.api.Assertions.assertNull(result.getResponse().getHeader("Content-Disposition"));
        org.mockito.Mockito.verify(auditLogRepository).save(org.mockito.ArgumentMatchers.argThat(
                (ai.philterd.workspace.model.AuditLog entry) -> "REDACTED_PDF_REFUSED".equals(entry.getAction())));
    }

    @Test
    public void download_namesTheRedactedTextAsText() throws Exception {
        // #66: only the last extension is replaced, and a PDF name in any case becomes .txt when text is served.
        for (String[] c : new String[][] {{"my.notes.txt", "text/plain", "my.notes_redacted.txt"},
                {"filing.PDF", "application/pdf", "filing_redacted.txt"}}) {
            final org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post("/download")
                                    .param("redactedText", "Hello.")
                                    .param("fileName", c[0])
                                    .param("contentType", c[1])
                                    .with(csrf()).with(signedIn("user", "USER")))
                    .andExpect(status().isOk())
                    .andReturn();
            org.junit.jupiter.api.Assertions.assertEquals("attachment; filename=\"" + c[2] + "\"",
                    result.getResponse().getHeader("Content-Disposition"), c[0]);
            org.junit.jupiter.api.Assertions.assertEquals("text/plain;charset=UTF-8",
                    result.getResponse().getContentType(), c[0]);
        }
    }

    @Test
    public void download_namesARedactedPdfAsARedactedPdf() throws Exception {
        final org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("originalFile", "%PDF-1.4 original".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        when(redactionService.getRedactedPdf(any(), any())).thenReturn("%PDF-1.4 redacted".getBytes());

        final org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/download")
                                .session(session)
                                .param("redactedText", "")
                                .param("fileName", "filing.pdf")
                                .param("contentType", "application/pdf")
                                .with(csrf()).with(signedIn("user", "USER")))
                .andExpect(status().isOk())
                .andReturn();

        org.junit.jupiter.api.Assertions.assertEquals("attachment; filename=\"filing_redacted.pdf\"",
                result.getResponse().getHeader("Content-Disposition"));
        org.junit.jupiter.api.Assertions.assertEquals("application/pdf", result.getResponse().getContentType());
    }

    @Test
    public void download_acceptsPlainTextAndSanitizesFilename() throws Exception {
        // The fileName is built with CR/LF and double-quote injection attempts; the
        // safeFilename helper must scrub them before they reach Content-Disposition.
        final org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/download")
                                .param("redactedText", "Hello.")
                                .param("fileName", "report\r\nX-Injected: yes\";attack=\".txt")
                                .param("contentType", "text/plain")
                                .with(csrf()).with(signedIn("user", "USER")))
                .andExpect(status().isOk())
                .andReturn();

        final String disposition = result.getResponse().getHeader("Content-Disposition");
        org.junit.jupiter.api.Assertions.assertNotNull(disposition,
                "Content-Disposition must be set on a successful download");
        org.junit.jupiter.api.Assertions.assertFalse(disposition.contains("\r"),
                "CR must not appear in the response header");
        org.junit.jupiter.api.Assertions.assertFalse(disposition.contains("\n"),
                "LF must not appear in the response header");
        // Quotes inside the filename portion must have been stripped — only the wrapping
        // quotes around the value should remain.
        final String prefix = "attachment; filename=\"";
        org.junit.jupiter.api.Assertions.assertTrue(disposition.startsWith(prefix),
                "Content-Disposition must keep the standard attachment wrapper");
        // After the prefix, the next quote should be the closing one (the value contains no
        // unescaped double quotes).
        final String tail = disposition.substring(prefix.length());
        final int firstQuote = tail.indexOf('"');
        org.junit.jupiter.api.Assertions.assertEquals(tail.length() - 1, firstQuote,
                "filename portion must not contain bare double quotes; got: " + disposition);
    }

    /**
     * Render the Add Documents page with a populated RDB data source list. Catches a
     * Thymeleaf template regression — the dropdown previously interpolated
     * {@code r.jdbcUrl} on the entity, which broke when that field was renamed to
     * {@code encryptedJdbcUrl}. With an empty repo the iteration body never runs and
     * the breakage stays invisible to existing tests; this test gives the iteration
     * at least one row to render.
     */
    @Test
    public void uploadPageRendersRdbDropdownWithStoredDataSources() throws Exception {
        final ai.philterd.workspace.model.RelationalDbDataSource src =
                new ai.philterd.workspace.model.RelationalDbDataSource();
        src.setId("rdb-1");
        src.setName("warehouse");
        // The URL field is encrypted at rest — the entity carries ciphertext.
        // The upload page must not interpolate this field (which would (a) crash
        // because the entity no longer has getJdbcUrl(), and (b) leak ciphertext
        // — or, with a sloppy decrypt, the plaintext URL — onto a page reachable
        // by every reviewer, not just admins).
        src.setEncryptedJdbcUrl("enc:jdbc:postgresql://host:5432/db");
        src.setSqlQuery("SELECT text, filename FROM documents");
        when(relationalDbDataSourceRepository.findAll(
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(src)));

        // Provide one batch too so the form is rendered (the template skips the form
        // when batches is empty, which would also skip iteration over rdbDataSources).
        final Batch batch = new Batch();
        batch.setId("b1");
        batch.setName("Mine");
        batch.setGroupId("g-mine");
        when(batchRepository.findAll(
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(batch)));
        when(userGroupsService.groupIdsForEmail(any())).thenReturn(java.util.Set.of("g-mine"));

        final org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/upload").with(signedIn("user", "ADMIN")))
                .andExpect(status().isOk())
                .andReturn();
        final String body = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("warehouse"),
                "The RDB data source's name should render in the dropdown.");
        // The encrypted URL (or any URL) must NOT appear on this page — encryption-at-rest
        // becomes useless if /upload (reachable by all reviewers) prints it back out.
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("jdbc:postgresql://"),
                "Encrypted-at-rest JDBC URL must not be surfaced on the upload page.");
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("enc:jdbc:"),
                "Raw ciphertext must never appear on the upload page.");
    }
}
