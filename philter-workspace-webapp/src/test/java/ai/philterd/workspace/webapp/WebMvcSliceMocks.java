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
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;


import static org.mockito.Mockito.when;

/**
 * The beans a Philter Workspace {@code @WebMvcTest} slice needs mocked: the slice pulls in more of the application
 * than its controller list, and these have no database or remote service behind them in a test.
 */
abstract class WebMvcSliceMocks {

    @MockitoBean protected ai.philterd.workspace.webapp.security.PhilterApiKeys philterApiKeys;
    @MockitoBean protected ai.philterd.workspace.webapp.security.PhilterSignInService philterSignInService;
    @MockitoBean protected ai.philterd.workspace.webapp.security.PhilterSignInFlow philterSignInFlow;
    @MockitoBean protected ai.philterd.workspace.webapp.security.BootstrapKeyWarning bootstrapKeyWarning;

    /** The slice does not auto-configure Actuator, so the aggregate HealthController reads is mocked. */
    @MockitoBean protected HealthEndpoint healthEndpoint;

    @BeforeEach
    void healthIsUp() {
        when(healthEndpoint.health()).thenReturn(TestHealth.reporting(org.springframework.boot.health.contributor.Status.UP));
    }

    // Repositories — Spring Data interfaces, all mockable.
    @MockitoBean protected RedactionService redactionService;
    @MockitoBean protected SpanRepository spanRepository;
    @MockitoBean protected DocumentRepository documentRepository;
    @MockitoBean protected BatchRepository batchRepository;
    @MockitoBean protected UserRepository userRepository;
    @MockitoBean protected GroupRepository groupRepository;
    @MockitoBean protected AuditLogRepository auditLogRepository;
    @MockitoBean protected NotificationSettingsRepository notificationSettingsRepository;
    @MockitoBean protected GeneralSettingsRepository generalSettingsRepository;
    @MockitoBean protected OllamaInstanceRepository ollamaInstanceRepository;
    @MockitoBean protected OpenSearchDataSourceRepository openSearchDataSourceRepository;
    @MockitoBean protected ai.philterd.workspace.repository.BackgroundJobRepository backgroundJobRepository;
    @MockitoBean protected ai.philterd.workspace.repository.DataImportLogEntryRepository dataImportLogEntryRepository;
    @MockitoBean protected ai.philterd.workspace.repository.ElasticsearchDataSourceRepository elasticsearchDataSourceRepository;
    @MockitoBean protected S3DataSourceRepository s3DataSourceRepository;
    @MockitoBean protected RelationalDbDataSourceRepository relationalDbDataSourceRepository;
    @MockitoBean protected LocalDirectoryDataSourceRepository localDirectoryDataSourceRepository;
    @MockitoBean protected LocalDirectoryDestinationRepository localDirectoryDestinationRepository;
    @MockitoBean protected S3DestinationRepository s3DestinationRepository;
    @MockitoBean protected DestinationTester destinationTester;
    @MockitoBean protected ai.philterd.workspace.service.PhilterSettings philterSettings;
    @MockitoBean protected DocumentCommentRepository documentCommentRepository;
    @MockitoBean protected WeightSetRepository weightSetRepository;
    @MockitoBean protected LlmJudgeDefaultsRepository llmJudgeDefaultsRepository;
    @MockitoBean protected UserSettingsRepository userSettingsRepository;
    @MockitoBean protected InboxMessageRepository inboxMessageRepository;
    @MockitoBean protected PendingUploadRepository pendingUploadRepository;
    @MockitoBean protected ai.philterd.workspace.repository.PhilterWebhookDeliveryRepository philterWebhookDeliveryRepository;
    @MockitoBean protected ai.philterd.workspace.repository.DetectionRuleRepository detectionRuleRepository;
    @MockitoBean protected ai.philterd.workspace.service.DetectionRuleService detectionRuleService;
    @MockitoBean protected ai.philterd.workspace.service.CaseMetadataService caseMetadataService;
    @MockitoBean protected ai.philterd.workspace.service.DocumentDeliveryService documentDeliveryService;
    @MockitoBean protected ai.philterd.workspace.service.DocumentChangeGuard documentChangeGuard;
    @MockitoBean protected ai.philterd.workspace.service.DocumentFinalizationService documentFinalizationService;
    @MockitoBean protected ai.philterd.workspace.repository.BlindReviewSpanRepository blindReviewSpanRepository;
    @MockitoBean protected ai.philterd.workspace.service.BlindDoubleReviewService blindDoubleReviewService;

    /** Recording who was shown a document's spans hands the document back, as the service does (#86). */
    @org.junit.jupiter.api.BeforeEach
    void blindDoubleReviewHandsTheDocumentBack() {
        org.mockito.Mockito.when(blindDoubleReviewService.noteSeen(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(1));
    }
    @MockitoBean protected ai.philterd.workspace.webapp.security.PhilterWebhookSettings philterWebhookSettings;
    @MockitoBean protected ai.philterd.workspace.webapp.security.PhilterWebhookReceiver philterWebhookReceiver;
    @MockitoBean protected RedactionCertificateRepository redactionCertificateRepository;
    @MockitoBean protected ai.philterd.workspace.repository.FinalizationPolicyRepository finalizationPolicyRepository;
    @MockitoBean protected ai.philterd.workspace.repository.ComplianceProfileRepository complianceProfileRepository;
    @MockitoBean protected MongoOperations mongoOperations;
}
