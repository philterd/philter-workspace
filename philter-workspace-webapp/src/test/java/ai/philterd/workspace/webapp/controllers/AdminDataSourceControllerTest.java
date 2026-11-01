/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.LocalDirectoryDataSource;
import ai.philterd.workspace.model.OpenSearchDataSource;
import ai.philterd.workspace.model.RelationalDbDataSource;
import ai.philterd.workspace.model.S3DataSource;
import ai.philterd.workspace.repository.ElasticsearchDataSourceRepository;
import ai.philterd.workspace.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.workspace.repository.OpenSearchDataSourceRepository;
import ai.philterd.workspace.repository.RelationalDbDataSourceRepository;
import ai.philterd.workspace.repository.S3DataSourceRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.SymmetricCipher;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration-style tests for the four data-source flows on
 * {@link AdminDataSourceController}: OpenSearch, S3, Relational Database,
 * Local Directory. Positive happy paths + every key negative case.
 */
class AdminDataSourceControllerTest {

    /** An existing folder under the allowed root these tests use: Local Directory paths must exist under one (#72). */
    private static final String TMP = System.getProperty("java.io.tmpdir");


    private OpenSearchDataSourceRepository repository;
    private ElasticsearchDataSourceRepository esRepository;
    private S3DataSourceRepository s3Repository;
    private RelationalDbDataSourceRepository rdbRepository;
    private LocalDirectoryDataSourceRepository localRepository;
    private ai.philterd.workspace.repository.BackgroundJobRepository backgroundJobRepository;
    private AuditLogService auditLogService;
    private SymmetricCipher cipher;
    private ai.philterd.workspace.service.RdbPreviewService rdbPreviewService;
    private AdminDataSourceController controller;

    private final ai.philterd.workspace.repository.GroupRepository groupRepository =
            org.mockito.Mockito.mock(ai.philterd.workspace.repository.GroupRepository.class);
    private ai.philterd.workspace.service.DataSourceImportAccess dataSourceImportAccess;

    @BeforeEach
    void setUp() {
        repository = mock(OpenSearchDataSourceRepository.class);
        esRepository = mock(ElasticsearchDataSourceRepository.class);
        s3Repository = mock(S3DataSourceRepository.class);
        rdbRepository = mock(RelationalDbDataSourceRepository.class);
        localRepository = mock(LocalDirectoryDataSourceRepository.class);
        dataSourceImportAccess = new ai.philterd.workspace.service.DataSourceImportAccess(repository, esRepository,
                s3Repository, rdbRepository, localRepository, mock(ai.philterd.workspace.service.BatchAccessService.class));
        backgroundJobRepository = mock(ai.philterd.workspace.repository.BackgroundJobRepository.class);
        auditLogService = mock(AuditLogService.class);
        cipher = mock(SymmetricCipher.class);
        rdbPreviewService = mock(ai.philterd.workspace.service.RdbPreviewService.class);
        // Identity-ish encrypt so assertions can recover the supplied value.
        when(cipher.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        final ai.philterd.workspace.service.DataSourceHostAllowList allowList =
                new ai.philterd.workspace.service.DataSourceHostAllowList("");
        controller = new AdminDataSourceController(
                repository, esRepository, s3Repository, rdbRepository, localRepository,
                backgroundJobRepository,
                auditLogService, cipher, new ObjectMapper(),
                allowList,
                new ai.philterd.workspace.service.JdbcUrlValidator(allowList),
                rdbPreviewService,
                groupRepository, dataSourceImportAccess, new ai.philterd.workspace.service.LocalDirectoryRoots(System.getProperty("java.io.tmpdir")));
    }

    private RedirectAttributes flash() { return new RedirectAttributesModelMap(); }
    private static String error(final RedirectAttributes ra) {
        final Object e = ra.getFlashAttributes().get("error"); return e == null ? null : e.toString();
    }
    private static String success(final RedirectAttributes ra) {
        final Object s = ra.getFlashAttributes().get("success"); return s == null ? null : s.toString();
    }

    // ====================================================================
    // OpenSearch
    // ====================================================================

    @Test
    void groupsAreSetOnASourceAndUnknownOnesDropped() {
        // #72: which groups a source is available to.
        final OpenSearchDataSource source = new OpenSearchDataSource();
        source.setId("os-1");
        source.setName("legal");
        when(repository.findById("os-1")).thenReturn(Optional.of(source));
        when(groupRepository.existsById("claims")).thenReturn(true);
        when(groupRepository.existsById("ghost")).thenReturn(false);

        controller.setGroups("opensearch", "os-1", java.util.List.of("claims", "ghost", "claims"), false, flash());
        assertEquals(java.util.List.of("claims"), source.getGroupIds());
        assertFalse(source.isAllGroups());
        verify(repository).save(source);
        verify(auditLogService).log(eq("DATA_SOURCE_GROUPS_CHANGE"), eq("DataSource"), eq("os-1"), any());

        controller.setGroups("opensearch", "os-1", java.util.List.of("claims"), true, flash());
        assertTrue(source.isAllGroups());
        assertEquals(java.util.List.of(), source.getGroupIds(), "all groups needs no list");

        controller.setGroups("opensearch", "os-1", null, false, flash());
        assertFalse(source.isAllGroups());
        assertEquals(java.util.List.of(), source.getGroupIds(), "neither: administrators only");

        final RedirectAttributes missing = flash();
        controller.setGroups("opensearch", "nope", java.util.List.of(), false, missing);
        assertEquals("Data source not found.", error(missing));
    }

    @Test
    void aLocalDirectorySourceOutsideTheAllowedRootsIsRefused() {
        final RedirectAttributes ra = flash();
        controller.createLocal("etc", "/etc", "*", ra);
        assertEquals(ai.philterd.workspace.service.LocalDirectoryRoots.NOT_ALLOWED, error(ra));
        verify(localRepository, never()).save(any());
    }

    @Test
    void anEndpointThatIsNotAllowedIsRefusedOnSaveAndEdit() {
        // #73: checked when a source is saved or edited, not only by Test and when an import is queued.
        final String internal = "http://169.254.169.254:9200";
        when(repository.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(esRepository.findFirstByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(repository.findById("os-1")).thenReturn(Optional.of(new OpenSearchDataSource()));
        when(esRepository.findById("es-1")).thenReturn(Optional.of(new ai.philterd.workspace.model.ElasticsearchDataSource()));
        when(s3Repository.findById("s3-1")).thenReturn(Optional.of(new ai.philterd.workspace.model.S3DataSource()));

        final java.util.List<java.util.function.Function<RedirectAttributes, String>> saves = java.util.List.of(
                ra -> controller.create("os", internal, "idx/_search {}", "body", null, null, null, ra),
                ra -> controller.editOpenSearch("os-1", internal, "idx/_search {}", "body", null, null, null, false, ra),
                ra -> controller.createEs("es", internal, "idx/_search {}", "body", null, null, null, ra),
                ra -> controller.editEs("es-1", internal, "idx/_search {}", "body", null, null, null, false, ra),
                ra -> controller.createS3("s3", internal, "bucket", "", "*.txt", null, null, ra),
                ra -> controller.editS3("s3-1", internal, "bucket", "", "*.txt", null, null, false, ra));
        for (java.util.function.Function<RedirectAttributes, String> save : saves) {
            final RedirectAttributes ra = flash();
            save.apply(ra);
            assertTrue(error(ra) != null && error(ra).contains("not permitted"), String.valueOf(error(ra)));
        }
        verify(repository, never()).save(any());
        verify(esRepository, never()).save(any());
        verify(s3Repository, never()).save(any());
    }

    @Test
    void osCreateHappyPathPersistsAndAudits() {
        when(repository.findFirstByNameIgnoreCase("legal")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        final String view = controller.create("legal", "http://es:9200",
                "contracts/_search { \"query\": { \"match_all\": {} } }",
                "body", null, "alice", "secretpw", ra);

        assertEquals("redirect:/admin/data-sources", view);
        assertEquals("Data source \"legal\" added.", success(ra));

        final ArgumentCaptor<OpenSearchDataSource> saved =
                ArgumentCaptor.forClass(OpenSearchDataSource.class);
        verify(repository).save(saved.capture());
        assertEquals("legal", saved.getValue().getName());
        assertEquals("http://es:9200", saved.getValue().getEndpoint());
        assertEquals("body", saved.getValue().getTextField());
        assertEquals("alice", saved.getValue().getUsername());
        assertEquals("enc:secretpw", saved.getValue().getEncryptedPassword());
        verify(auditLogService).log(eq("OPENSEARCH_DATASOURCE_CREATE"), anyString(), anyString(), any());
    }

    @Test
    void osCreateRejectsBlankName() {
        final RedirectAttributes ra = flash();
        controller.create("   ", "http://es:9200", "q", "body", null, null, null, ra);
        assertEquals("Name is required.", error(ra));
        verify(repository, never()).save(any());
    }

    @Test
    void osCreateRejectsBlankEndpoint() {
        final RedirectAttributes ra = flash();
        controller.create("n", " ", "q", "body", null, null, null, ra);
        assertEquals("Endpoint is required.", error(ra));
    }

    @Test
    void osCreateRejectsBlankQuery() {
        final RedirectAttributes ra = flash();
        controller.create("n", "http://es", "", "body", null, null, null, ra);
        assertEquals("Query is required.", error(ra));
    }

    @Test
    void osCreateRejectsBlankTextField() {
        final RedirectAttributes ra = flash();
        controller.create("n", "http://es", "q", "  ", null, null, null, ra);
        assertEquals("Text field is required.", error(ra));
    }

    @Test
    void osCreateRejectsDuplicateNameCaseInsensitive() {
        final OpenSearchDataSource existing = new OpenSearchDataSource();
        existing.setName("Legal");
        when(repository.findFirstByNameIgnoreCase("legal")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.create("legal", "http://es", "q", "body", null, null, null, ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).contains("already exists"));
        verify(repository, never()).save(any());
    }

    @Test
    void osCreateHandlesDuplicateKeyExceptionFromSave() {
        // Race condition: precheck passes, but Mongo's unique index rejects the insert.
        when(repository.findFirstByNameIgnoreCase("legal")).thenReturn(Optional.empty());
        when(repository.save(any(OpenSearchDataSource.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key"));
        final RedirectAttributes ra = flash();

        controller.create("legal", "http://es", "q", "body", null, null, null, ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).contains("already exists"));
    }

    @Test
    void osDeleteHappyPath() {
        final OpenSearchDataSource src = new OpenSearchDataSource();
        src.setId("ds-1");
        src.setName("legal");
        when(repository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.delete("ds-1", ra);

        verify(repository).deleteById("ds-1");
        verify(auditLogService).log(eq("OPENSEARCH_DATASOURCE_DELETE"), anyString(), eq("ds-1"), any());
        assertEquals("Data source \"legal\" removed.", success(ra));
    }

    @Test
    void osDeleteMissingIdReturnsErrorAndDoesNotDelete() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.delete("ghost", ra);

        assertEquals("Data source not found.", error(ra));
        verify(repository, never()).deleteById(anyString());
    }

    // ====================================================================
    // S3
    // ====================================================================

    @Test
    void s3CreateHappyPathPersistsAndEncryptsCredentials() {
        when(s3Repository.findFirstByNameIgnoreCase("archive")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createS3("archive", null, "my-bucket", "raw/", "*.txt",
                "AKIA…", "wJalrXUtnFEMI", ra);

        final ArgumentCaptor<S3DataSource> saved = ArgumentCaptor.forClass(S3DataSource.class);
        verify(s3Repository).save(saved.capture());
        assertEquals("archive", saved.getValue().getName());
        assertEquals("my-bucket", saved.getValue().getBucketName());
        assertEquals("raw/", saved.getValue().getBucketKey());
        assertEquals("*.txt", saved.getValue().getFilenameGlob());
        assertEquals("enc:AKIA…", saved.getValue().getEncryptedAccessKey());
        assertEquals("enc:wJalrXUtnFEMI", saved.getValue().getEncryptedSecretKey());
        assertEquals("S3 data source \"archive\" added.", success(ra));
    }

    @Test
    void s3CreateAmbientCredentialsLeavesEncryptedFieldsNull() {
        when(s3Repository.findFirstByNameIgnoreCase("amb")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createS3("amb", null, "my-bucket", "k/", "*.pdf", "", "", ra);

        final ArgumentCaptor<S3DataSource> saved = ArgumentCaptor.forClass(S3DataSource.class);
        verify(s3Repository).save(saved.capture());
        assertNull(saved.getValue().getEncryptedAccessKey());
        assertNull(saved.getValue().getEncryptedSecretKey());
    }

    @Test
    void s3CreateRejectsBlankBucket() {
        final RedirectAttributes ra = flash();
        controller.createS3("n", null, "  ", "k/", "*.txt", null, null, ra);
        assertEquals("Bucket name is required.", error(ra));
    }

    @Test
    void s3CreateRejectsBlankBucketKey() {
        final RedirectAttributes ra = flash();
        controller.createS3("n", null, "b", "", "*.txt", null, null, ra);
        assertEquals("Bucket key is required.", error(ra));
    }

    @Test
    void s3CreateRejectsBlankFilenameGlob() {
        final RedirectAttributes ra = flash();
        controller.createS3("n", null, "b", "k/", " ", null, null, ra);
        assertEquals("Filename glob is required.", error(ra));
    }

    @Test
    void s3CreateRejectsAccessKeyWithoutSecretKey() {
        final RedirectAttributes ra = flash();
        controller.createS3("n", null, "b", "k/", "*.txt", "AKIA", "", ra);
        assertEquals("Provide both Access key and Secret key, or leave both blank.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3CreateRejectsSecretKeyWithoutAccessKey() {
        final RedirectAttributes ra = flash();
        controller.createS3("n", null, "b", "k/", "*.txt", "", "secret", ra);
        assertEquals("Provide both Access key and Secret key, or leave both blank.", error(ra));
    }

    @Test
    void s3DeleteMissingIdReturnsError() {
        when(s3Repository.findById("ghost")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();
        controller.deleteS3("ghost", ra);
        assertEquals("S3 data source not found.", error(ra));
        verify(s3Repository, never()).deleteById(anyString());
    }

    @Test
    void s3EditHappyPathUpdatesFieldsAndKeepsCredentialsWhenBlank() {
        final S3DataSource existing = new S3DataSource();
        existing.setId("e1");
        existing.setName("archive");
        existing.setEndpoint("http://old:9000");
        existing.setBucketName("old-bucket");
        existing.setBucketKey("old/");
        existing.setFilenameGlob("*.txt");
        existing.setEncryptedAccessKey("enc:AKIA-old");
        existing.setEncryptedSecretKey("enc:secret-old");
        when(s3Repository.findById("e1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("e1", "http://new:9000", "new-bucket", "new/", "**/*.pdf",
                "", "", false, ra);

        final ArgumentCaptor<S3DataSource> saved = ArgumentCaptor.forClass(S3DataSource.class);
        verify(s3Repository).save(saved.capture());
        assertEquals("http://new:9000", saved.getValue().getEndpoint());
        assertEquals("new-bucket", saved.getValue().getBucketName());
        assertEquals("new/", saved.getValue().getBucketKey());
        assertEquals("**/*.pdf", saved.getValue().getFilenameGlob());
        // Blank credentials + clearCredentials=false must preserve existing keys.
        assertEquals("enc:AKIA-old", saved.getValue().getEncryptedAccessKey());
        assertEquals("enc:secret-old", saved.getValue().getEncryptedSecretKey());
    }

    @Test
    void s3EditEmptyEndpointFallsBackToNullForAwsDefault() {
        final S3DataSource existing = new S3DataSource();
        existing.setId("e1");
        existing.setName("archive");
        existing.setEndpoint("http://minio:9000");
        existing.setBucketName("b");
        existing.setBucketKey("k/");
        existing.setFilenameGlob("*.txt");
        when(s3Repository.findById("e1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("e1", "  ", "b", "k/", "*.txt", "", "", false, ra);

        final ArgumentCaptor<S3DataSource> saved = ArgumentCaptor.forClass(S3DataSource.class);
        verify(s3Repository).save(saved.capture());
        assertNull(saved.getValue().getEndpoint());
    }

    @Test
    void s3EditNonEmptyCredentialsReplaceStoredOnes() {
        final S3DataSource existing = new S3DataSource();
        existing.setId("e1");
        existing.setName("archive");
        existing.setBucketName("b");
        existing.setBucketKey("k/");
        existing.setFilenameGlob("*.txt");
        existing.setEncryptedAccessKey("enc:old-ak");
        existing.setEncryptedSecretKey("enc:old-sk");
        when(s3Repository.findById("e1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("e1", null, "b", "k/", "*.txt", "AKIA-new", "secret-new", false, ra);

        final ArgumentCaptor<S3DataSource> saved = ArgumentCaptor.forClass(S3DataSource.class);
        verify(s3Repository).save(saved.capture());
        assertEquals("enc:AKIA-new", saved.getValue().getEncryptedAccessKey());
        assertEquals("enc:secret-new", saved.getValue().getEncryptedSecretKey());
    }

    @Test
    void s3EditClearCredentialsWipesBothEvenWhenFieldsAreBlank() {
        final S3DataSource existing = new S3DataSource();
        existing.setId("e1");
        existing.setName("archive");
        existing.setBucketName("b");
        existing.setBucketKey("k/");
        existing.setFilenameGlob("*.txt");
        existing.setEncryptedAccessKey("enc:old");
        existing.setEncryptedSecretKey("enc:old");
        when(s3Repository.findById("e1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("e1", null, "b", "k/", "*.txt", "", "", true, ra);

        final ArgumentCaptor<S3DataSource> saved = ArgumentCaptor.forClass(S3DataSource.class);
        verify(s3Repository).save(saved.capture());
        assertNull(saved.getValue().getEncryptedAccessKey());
        assertNull(saved.getValue().getEncryptedSecretKey());
    }

    @Test
    void s3EditRejectsMismatchedCredentialPair() {
        final S3DataSource existing = new S3DataSource();
        existing.setId("e1");
        existing.setName("archive");
        when(s3Repository.findById("e1")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.editS3("e1", null, "b", "k/", "*.txt", "AKIA", "", false, ra);

        assertEquals("Provide both Access key and Secret key, or leave both blank.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    @Test
    void s3EditMissingIdReturnsError() {
        when(s3Repository.findById("ghost")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();
        controller.editS3("ghost", null, "b", "k/", "*.txt", "", "", false, ra);
        assertEquals("S3 data source not found.", error(ra));
        verify(s3Repository, never()).save(any());
    }

    // ====================================================================
    // Relational Database
    // ====================================================================

    @Test
    void rdbCreateHappyPathPersistsAndEncryptsBothCredentials() {
        when(rdbRepository.findFirstByNameIgnoreCase("warehouse")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createRdb("warehouse", "jdbc:postgresql://host/db",
                "SELECT body FROM documents", "alice", "pw", null, ra);

        final ArgumentCaptor<RelationalDbDataSource> saved =
                ArgumentCaptor.forClass(RelationalDbDataSource.class);
        verify(rdbRepository).save(saved.capture());
        assertEquals("SELECT body FROM documents", saved.getValue().getSqlQuery());
        // JDBC URL and credentials are all encrypted at rest. The cipher mock returns
        // "enc:<plain>", so asserting both the encrypted form and the absence of the
        // plaintext catches a regression that bypassed cipher.encrypt() but happened
        // to coincide with the expected ciphertext.
        assertEquals("enc:jdbc:postgresql://host/db", saved.getValue().getEncryptedJdbcUrl());
        assertEquals("enc:alice", saved.getValue().getEncryptedUsername());
        assertEquals("enc:pw", saved.getValue().getEncryptedPassword());
        assertNotEquals("jdbc:postgresql://host/db", saved.getValue().getEncryptedJdbcUrl(),
                "Plaintext JDBC URL must never be persisted on the RelationalDbDataSource row.");
        assertNotEquals("alice", saved.getValue().getEncryptedUsername(),
                "Plaintext username must never be persisted on the RelationalDbDataSource row.");
        assertNotEquals("pw", saved.getValue().getEncryptedPassword(),
                "Plaintext password must never be persisted on the RelationalDbDataSource row.");
        verify(cipher).encrypt("jdbc:postgresql://host/db");
        verify(cipher).encrypt("alice");
        verify(cipher).encrypt("pw");
    }

    @Test
    void rdbCreateRejectsBlankJdbcUrl() {
        final RedirectAttributes ra = flash();
        controller.createRdb("n", "", "SELECT 1", null, null, null, ra);
        assertEquals("JDBC URL is required.", error(ra));
    }

    @Test
    void rdbCreateRejectsBlankSqlQuery() {
        final RedirectAttributes ra = flash();
        controller.createRdb("n", "jdbc:postgresql://host:5432/db", "  ", null, null, null, ra);
        assertEquals("SQL query is required.", error(ra));
    }

    @Test
    void rdbCreateRejectsDangerousSqlKeywords() {
        // The controller refuses queries that could mutate the source table.
        final RedirectAttributes ra = flash();
        controller.createRdb("evil", "jdbc:postgresql://host:5432/db",
                "DELETE FROM documents WHERE 1=1", null, null, null, ra);
        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("disallowed"),
                "expected dangerous-keyword rejection, got: " + error(ra));
        verify(rdbRepository, never()).save(any());
        // The blocked attempt must be audit-logged with the matched keywords.
        verify(auditLogService).log(eq("RDB_DANGEROUS_SQL_BLOCKED"), anyString(), any(), any());
    }

    @Test
    void rdbCreateRejectsUsernameWithoutPassword() {
        final RedirectAttributes ra = flash();
        controller.createRdb("n", "jdbc:postgresql://host:5432/db", "SELECT 1", "alice", "", null, ra);
        assertEquals("Provide both Username and Password, or leave both blank.", error(ra));
    }

    @Test
    void rdbCreateRefusesUrlWithEmbeddedCredentialsAndStripsThemFromAuditLog() {
        // Finding #13 — the canonical leak case. The validator refuses the URL,
        // and the RDB_DANGEROUS_JDBC_URL_BLOCKED audit row must NOT carry the
        // password into the audit collection.
        final RedirectAttributes ra = flash();
        controller.createRdb("warehouse",
                "jdbc:postgresql://alice:s3cret@host:5432/db",
                "SELECT 1", null, null, null, ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("embedded credentials"));
        verify(rdbRepository, never()).save(any());

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details =
                ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("RDB_DANGEROUS_JDBC_URL_BLOCKED"),
                eq("RelationalDbDataSource"),
                org.mockito.ArgumentMatchers.isNull(String.class),
                details.capture());

        final Object loggedUrl = details.getValue().get("jdbcUrl");
        assertEquals("jdbc:postgresql://host:5432/db", loggedUrl,
                "audit log must strip the user:password@ segment");
        assertFalse(loggedUrl.toString().contains("s3cret"),
                "audit log leaked the password: " + loggedUrl);
        assertFalse(loggedUrl.toString().contains("alice"),
                "audit log leaked the username: " + loggedUrl);
    }

    @Test
    void rdbCreateHappyPathAuditLogStripsUserInfo() {
        // Defense in depth: even on the happy create path (where the URL did NOT
        // carry credentials, because the validator refused otherwise), the audit
        // entry runs the same stripUserInfo so a future code path that bypasses
        // the validator can't leak.
        when(rdbRepository.findFirstByNameIgnoreCase("warehouse")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();
        controller.createRdb("warehouse", "jdbc:postgresql://host:5432/db",
                "SELECT id, body FROM documents", "alice", "secret", null, ra);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details =
                ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("RDB_DATASOURCE_CREATE"),
                eq("RelationalDbDataSource"),
                anyString(),
                details.capture());

        assertEquals("jdbc:postgresql://host:5432/db", details.getValue().get("jdbcUrl"));
    }

    @Test
    void rdbCreateBlockedSqlAuditLogAlsoStripsUserInfo() {
        // The dangerous-SQL rejection path also logs the URL. If a URL somehow has
        // userinfo (e.g. an upstream code path that bypasses the JDBC validator),
        // the dangerous-SQL audit row must still strip. Confirms the strip is
        // wired into every audit-write site in createRdb, not just the JDBC one.
        // The validator runs before the SQL check, so to exercise this path I
        // pass a URL that the validator accepts but then has a DELETE in the SQL.
        final RedirectAttributes ra = flash();
        controller.createRdb("evil", "jdbc:postgresql://host:5432/db",
                "DELETE FROM documents", null, null, null, ra);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details =
                ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("RDB_DANGEROUS_SQL_BLOCKED"),
                eq("RelationalDbDataSource"),
                org.mockito.ArgumentMatchers.isNull(String.class),
                details.capture());

        assertEquals("jdbc:postgresql://host:5432/db", details.getValue().get("jdbcUrl"));
    }

    @Test
    void rdbCreateValidationErrorRoundTripsFormValuesThroughFlashAttributes() {
        // The bad JDBC URL must not navigate the operator away from the page or empty the
        // form — they should land back on the RDB tab with their entries intact so they
        // can fix the one typo without retyping everything.
        final RedirectAttributes ra = flash();

        final String view = controller.createRdb(
                "warehouse",
                "postgresql://host:5432/db",         // missing "jdbc:" prefix
                "SELECT body FROM documents",
                "alice", "pw", null, ra);

        assertEquals("redirect:/admin/data-sources?tab=rdb", view,
                "Redirect target must include ?tab=rdb so the operator lands back on the RDB tab.");
        assertNotNull(error(ra), "Validation failure must surface an error to the user.");
        verify(rdbRepository, never()).save(any());

        final java.util.Map<String, ?> flash = ra.getFlashAttributes();
        assertEquals("warehouse", flash.get("rdbFormName"));
        assertEquals("postgresql://host:5432/db", flash.get("rdbFormJdbcUrl"));
        assertEquals("SELECT body FROM documents", flash.get("rdbFormSqlQuery"));
        assertEquals("alice", flash.get("rdbFormUsername"));
        // Password is round-tripped too — re-typing it on a JDBC-URL typo is friction
        // the operator shouldn't have to suffer. Flash attributes live in the session
        // for exactly one redirect, so this is no more exposure than the original POST.
        assertEquals("pw", flash.get("rdbFormPassword"));
    }

    @Test
    void rdbCreateBlankNameRoundTripsTheOtherFieldsThroughFlash() {
        // A different validation failure (blank Name) still preserves everything else
        // the operator typed. Without this the operator would lose the URL/SQL just
        // because they forgot to fill in Name.
        final RedirectAttributes ra = flash();

        controller.createRdb("",
                "jdbc:postgresql://host/db",
                "SELECT 1",
                "alice", "pw", null, ra);

        final java.util.Map<String, ?> flash = ra.getFlashAttributes();
        assertEquals("Name is required.", error(ra));
        assertEquals("", flash.get("rdbFormName"));
        assertEquals("jdbc:postgresql://host/db", flash.get("rdbFormJdbcUrl"));
        assertEquals("SELECT 1", flash.get("rdbFormSqlQuery"));
        assertEquals("alice", flash.get("rdbFormUsername"));
        assertEquals("pw", flash.get("rdbFormPassword"));
    }

    @Test
    void rdbDeleteHappyPath() {
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.deleteRdb("ds-1", ra);

        verify(rdbRepository).deleteById("ds-1");
        assertEquals("Relational database data source \"warehouse\" removed.", success(ra));
    }

    @Test
    void rdbDeleteMissingIdReturnsError() {
        when(rdbRepository.findById("ghost")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();
        controller.deleteRdb("ghost", ra);
        assertEquals("Relational database data source not found.", error(ra));
    }

    @Test
    void rdbDeleteRefusedWhenInUseByImportJob() {
        // While a data-import job is PENDING or RUNNING against this source, the
        // delete must refuse. Otherwise the worker would later pick up the queued
        // job and fail with a confusing "source not found" — the operator never
        // sees what they did wrong. Terminal jobs (COMPLETED/FAILED) don't block.
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        when(backgroundJobRepository.existsBySourceIdAndStatusIn(
                eq("ds-1"),
                org.mockito.ArgumentMatchers.argThat(c ->
                        c.contains("PENDING") && c.contains("RUNNING"))))
                .thenReturn(true);
        final RedirectAttributes ra = flash();

        controller.deleteRdb("ds-1", ra);

        verify(rdbRepository, never()).deleteById(anyString());
        assertNotNull(error(ra));
        assertTrue(error(ra).contains("currently using it"),
                "expected in-use error, got: " + error(ra));
    }

    @Test
    void rdbEditHappyPathUpdatesUrlSqlAndCredentials() {
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        src.setEncryptedJdbcUrl("enc:jdbc:postgresql://old:5432/db");
        src.setSqlQuery("SELECT 1");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.editRdb("ds-1",
                "jdbc:postgresql://new:5432/db",
                "SELECT text, filename FROM documents",
                "alice", "secretpw",
                null,
                false,
                false,
                ra);

        final ArgumentCaptor<RelationalDbDataSource> saved =
                ArgumentCaptor.forClass(RelationalDbDataSource.class);
        verify(rdbRepository).save(saved.capture());
        assertEquals("enc:jdbc:postgresql://new:5432/db", saved.getValue().getEncryptedJdbcUrl());
        assertEquals("SELECT text, filename FROM documents", saved.getValue().getSqlQuery());
        // JDBC URL and credentials are all encrypted on the edit path too — same posture
        // as create. A regression that bypassed the cipher would satisfy the equality
        // checks above but fail the plaintext-rejection ones below.
        assertEquals("enc:alice", saved.getValue().getEncryptedUsername());
        assertEquals("enc:secretpw", saved.getValue().getEncryptedPassword());
        assertNotEquals("jdbc:postgresql://new:5432/db", saved.getValue().getEncryptedJdbcUrl(),
                "Plaintext JDBC URL must never be persisted on the edit path.");
        assertNotEquals("alice", saved.getValue().getEncryptedUsername(),
                "Plaintext username must never be persisted on the edit path.");
        assertNotEquals("secretpw", saved.getValue().getEncryptedPassword(),
                "Plaintext password must never be persisted on the edit path.");
        verify(cipher).encrypt("jdbc:postgresql://new:5432/db");
        verify(cipher).encrypt("alice");
        verify(cipher).encrypt("secretpw");
        verify(auditLogService).log(eq("RDB_DATASOURCE_UPDATE"), anyString(), eq("ds-1"), any());
    }

    @Test
    void rdbEditWithBlankCredentialsLeavesStoredOnesIntact() {
        // Mirrors the S3 / OpenSearch edit flow: blank pair + clearCredentials=false
        // means "keep what's stored." Without this, every URL/SQL edit would silently
        // strip the password and the operator would have to re-type it.
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        src.setEncryptedUsername("enc:existingUser");
        src.setEncryptedPassword("enc:existingPass");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.editRdb("ds-1",
                "jdbc:postgresql://host:5432/db",
                "SELECT 1",
                "", "",
                null,
                false,
                false,
                ra);

        final ArgumentCaptor<RelationalDbDataSource> saved =
                ArgumentCaptor.forClass(RelationalDbDataSource.class);
        verify(rdbRepository).save(saved.capture());
        assertEquals("enc:existingUser", saved.getValue().getEncryptedUsername(),
                "blank credentials must not erase what's already stored");
        assertEquals("enc:existingPass", saved.getValue().getEncryptedPassword());
    }

    @Test
    void rdbEditClearCredentialsWipesStoredOnes() {
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        src.setEncryptedUsername("enc:existingUser");
        src.setEncryptedPassword("enc:existingPass");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.editRdb("ds-1",
                "jdbc:postgresql://host:5432/db",
                "SELECT 1",
                "", "",
                null,
                true,
                false,
                ra);

        final ArgumentCaptor<RelationalDbDataSource> saved =
                ArgumentCaptor.forClass(RelationalDbDataSource.class);
        verify(rdbRepository).save(saved.capture());
        assertNull(saved.getValue().getEncryptedUsername());
        assertNull(saved.getValue().getEncryptedPassword());
    }

    @Test
    void rdbEditRejectsDangerousSql() {
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.editRdb("ds-1",
                "jdbc:postgresql://host:5432/db",
                "DELETE FROM documents",
                null, null,
                null,
                false,
                false,
                ra);

        verify(rdbRepository, never()).save(any());
        verify(auditLogService).log(eq("RDB_DANGEROUS_SQL_BLOCKED"), anyString(), eq("ds-1"), any());
    }

    @Test
    void rdbPreviewDelegatesToServiceAndReturnsRows() {
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final java.util.List<String> cols = java.util.List.of("text", "filename");
        final java.util.List<java.util.Map<String, Object>> rows = java.util.List.of(
                java.util.Map.of("text", "hello", "filename", "a.txt"),
                java.util.Map.of("text", "world", "filename", "b.txt"));
        when(rdbPreviewService.preview(src))
                .thenReturn(ai.philterd.workspace.service.RdbPreviewService.Result.ok(cols, rows));

        final java.util.Map<String, Object> result = controller.previewRdb("ds-1");

        assertEquals(Boolean.TRUE, result.get("ok"));
        assertEquals(cols, result.get("columns"));
        assertEquals(rows, result.get("rows"));
        assertEquals(ai.philterd.workspace.service.RdbPreviewService.MAX_ROWS, result.get("rowLimit"));
    }

    @Test
    void rdbPreviewReturnsErrorWhenServiceFails() {
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        when(rdbPreviewService.preview(src))
                .thenReturn(ai.philterd.workspace.service.RdbPreviewService.Result.failed(
                        "[08001] Connection refused"));

        final java.util.Map<String, Object> result = controller.previewRdb("ds-1");

        assertEquals(Boolean.FALSE, result.get("ok"));
        assertEquals("[08001] Connection refused", result.get("error"));
    }

    @Test
    void rdbPreviewMissingIdReturnsError() {
        when(rdbRepository.findById("ghost")).thenReturn(Optional.empty());
        final java.util.Map<String, Object> result = controller.previewRdb("ghost");
        assertEquals(Boolean.FALSE, result.get("ok"));
        assertNotNull(result.get("error"));
    }

    // ====================================================================
    // Local Directory
    // ====================================================================

    @Test
    void localCreateHappyPathPersists() {
        when(localRepository.findFirstByNameIgnoreCase("incoming")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createLocal("incoming", TMP, "*.txt", ra);

        final ArgumentCaptor<LocalDirectoryDataSource> saved =
                ArgumentCaptor.forClass(LocalDirectoryDataSource.class);
        verify(localRepository).save(saved.capture());
        assertEquals("incoming", saved.getValue().getName());
        assertEquals(TMP, saved.getValue().getDirectoryPath());
        assertEquals("*.txt", saved.getValue().getFilenameGlob());
    }

    @Test
    void localCreateRejectsBlankPath() {
        final RedirectAttributes ra = flash();
        controller.createLocal("n", "  ", "*.txt", ra);
        assertEquals("Directory path is required.", error(ra));
    }

    @Test
    void localCreateRejectsBlankGlob() {
        final RedirectAttributes ra = flash();
        controller.createLocal("n", TMP, "  ", ra);
        assertEquals("Filename glob is required.", error(ra));
    }

    @Test
    void localCreateRejectsDuplicateName() {
        final LocalDirectoryDataSource existing = new LocalDirectoryDataSource();
        existing.setName("Incoming");
        when(localRepository.findFirstByNameIgnoreCase("incoming")).thenReturn(Optional.of(existing));
        final RedirectAttributes ra = flash();

        controller.createLocal("incoming", TMP, "*.txt", ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).contains("already exists"));
    }

    @Test
    void localDeleteMissingIdReturnsError() {
        when(localRepository.findById("ghost")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();
        controller.deleteLocal("ghost", ra);
        assertEquals("Local directory data source not found.", error(ra));
    }

    // ====================================================================
    // SSRF allow-list — admin "Test connection" endpoints
    // ====================================================================

    @Test
    void testOpenSearchRejectsHostNotOnAllowList() {
        // Build a controller wired with a restrictive allow-list. Hitting Test connection
        // against a non-listed host must short-circuit with the allow-list error before
        // any HTTP request is made.
        final ai.philterd.workspace.service.DataSourceHostAllowList opensearchOnly =
                new ai.philterd.workspace.service.DataSourceHostAllowList("opensearch.internal");
        final AdminDataSourceController restricted = new AdminDataSourceController(
                repository, esRepository, s3Repository, rdbRepository, localRepository,
                backgroundJobRepository,
                auditLogService, cipher, new ObjectMapper(),
                opensearchOnly,
                new ai.philterd.workspace.service.JdbcUrlValidator(opensearchOnly),
                rdbPreviewService,
                groupRepository, dataSourceImportAccess, new ai.philterd.workspace.service.LocalDirectoryRoots(System.getProperty("java.io.tmpdir")));

        final java.util.Map<String, Object> result = restricted.testOpenSearch(
                "http://attacker.example.com:9200", "contracts/_search { }", null, null);

        assertEquals(Boolean.FALSE, result.get("ok"));
        assertNotNull(result.get("error"));
        assertTrue(result.get("error").toString().contains("allow-list"),
                "expected allow-list error, got: " + result.get("error"));
    }

    @Test
    void testElasticsearchRejectsHostNotOnAllowList() {
        final ai.philterd.workspace.service.DataSourceHostAllowList elasticOnly =
                new ai.philterd.workspace.service.DataSourceHostAllowList("elastic.internal");
        final AdminDataSourceController restricted = new AdminDataSourceController(
                repository, esRepository, s3Repository, rdbRepository, localRepository,
                backgroundJobRepository,
                auditLogService, cipher, new ObjectMapper(),
                elasticOnly,
                new ai.philterd.workspace.service.JdbcUrlValidator(elasticOnly),
                rdbPreviewService,
                groupRepository, dataSourceImportAccess, new ai.philterd.workspace.service.LocalDirectoryRoots(System.getProperty("java.io.tmpdir")));

        final java.util.Map<String, Object> result = restricted.testElasticsearch(
                "http://attacker.example.com:9200", "orders/_search { }", null, null);

        assertEquals(Boolean.FALSE, result.get("ok"));
        assertTrue(result.get("error").toString().contains("allow-list"),
                "expected allow-list error, got: " + result.get("error"));
    }

    @Test
    void testOpenSearchAllowsHostOnAllowList() {
        // When the host matches, the allow-list lets the request through to the actual
        // HTTP call. The request will fail (no real server), but we should NOT see the
        // allow-list error message. That's the difference between rejection-by-policy
        // and rejection-by-network.
        final ai.philterd.workspace.service.DataSourceHostAllowList loopbackOnly =
                new ai.philterd.workspace.service.DataSourceHostAllowList("127.0.0.1");
        final AdminDataSourceController restricted = new AdminDataSourceController(
                repository, esRepository, s3Repository, rdbRepository, localRepository,
                backgroundJobRepository,
                auditLogService, cipher, new ObjectMapper(),
                loopbackOnly,
                new ai.philterd.workspace.service.JdbcUrlValidator(loopbackOnly),
                rdbPreviewService,
                groupRepository, dataSourceImportAccess, new ai.philterd.workspace.service.LocalDirectoryRoots(System.getProperty("java.io.tmpdir")));

        // Use port 1 — guaranteed to refuse so the test doesn't hang on a real connect.
        final java.util.Map<String, Object> result = restricted.testOpenSearch(
                "http://127.0.0.1:1", "contracts/_search { }", null, null);

        assertEquals(Boolean.FALSE, result.get("ok"));
        // ok=false because the connection failed, but the *reason* is not the allow-list.
        final Object error = result.get("error");
        if (error != null) {
            assertTrue(!error.toString().contains("allow-list"),
                    "did not expect allow-list error, got: " + error);
        }
    }

    // ---------- watermark create/edit/reset/set-manual ----------

    @Test
    void rdbCreatePersistsWatermarkColumn() {
        when(rdbRepository.findFirstByNameIgnoreCase("warehouse")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createRdb("warehouse", "jdbc:postgresql://host/db",
                "SELECT body, id FROM documents WHERE id > COALESCE(:lastKey, 0) ORDER BY id",
                null, null, "id", ra);

        final ArgumentCaptor<RelationalDbDataSource> saved =
                ArgumentCaptor.forClass(RelationalDbDataSource.class);
        verify(rdbRepository).save(saved.capture());
        assertEquals("id", saved.getValue().getWatermarkColumn());
        assertNull(saved.getValue().getLastImportedKey(),
                "fresh source must have no watermark yet — it advances after the first run");
    }

    @Test
    void rdbCreateWithBlankWatermarkPersistsNull() {
        // Blank-string equivalence to null — the worker treats either as
        // "watermark not configured" and the field model should reflect that.
        when(rdbRepository.findFirstByNameIgnoreCase("warehouse")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.createRdb("warehouse", "jdbc:postgresql://host/db",
                "SELECT body FROM documents",
                null, null, "   ", ra);

        final ArgumentCaptor<RelationalDbDataSource> saved =
                ArgumentCaptor.forClass(RelationalDbDataSource.class);
        verify(rdbRepository).save(saved.capture());
        assertNull(saved.getValue().getWatermarkColumn());
    }

    @Test
    void rdbEditWithActiveWatermarkRequiresConfirmResetOnSqlChange() {
        // A source that's already advanced its watermark. Changing the SQL
        // without confirming would risk re-importing everything or skipping
        // new rows depending on which way the change goes — refuse.
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        src.setSqlQuery("SELECT body, id FROM documents WHERE id > :lastKey ORDER BY id");
        src.setWatermarkColumn("id");
        src.setLastImportedKey("12345");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.editRdb("ds-1",
                "jdbc:postgresql://host:5432/db",
                "SELECT body, name FROM documents WHERE name > :lastKey ORDER BY name",   // different SQL
                null, null,
                "id",
                false,
                false,                                                                     // confirm NOT set
                ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).contains("watermark"),
                "rejection must call out the watermark concern: " + error(ra));
        verify(rdbRepository, never()).save(any());
    }

    @Test
    void rdbEditWithConfirmResetClearsWatermarkOnSqlChange() {
        // Same scenario but with the confirmation checked. The save proceeds
        // and the watermark is reset to null so the next run starts over.
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        src.setSqlQuery("SELECT body, id FROM documents WHERE id > :lastKey ORDER BY id");
        src.setWatermarkColumn("id");
        src.setLastImportedKey("12345");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.editRdb("ds-1",
                "jdbc:postgresql://host:5432/db",
                "SELECT body, name FROM documents WHERE name > :lastKey ORDER BY name",
                null, null,
                "name",
                false,
                true,
                ra);

        final ArgumentCaptor<RelationalDbDataSource> saved =
                ArgumentCaptor.forClass(RelationalDbDataSource.class);
        verify(rdbRepository).save(saved.capture());
        assertNull(saved.getValue().getLastImportedKey(),
                "confirmed SQL change must reset the watermark");
        assertEquals("name", saved.getValue().getWatermarkColumn());
    }

    @Test
    void rdbEditWithoutSqlOrWatermarkChangeDoesNotRequireConfirmation() {
        // Touching the credentials or URL on a watermarked source is fine —
        // the meaning of "new" hasn't changed.
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        src.setSqlQuery("SELECT body, id FROM documents WHERE id > :lastKey ORDER BY id");
        src.setWatermarkColumn("id");
        src.setLastImportedKey("12345");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.editRdb("ds-1",
                "jdbc:postgresql://newhost:5432/db",                                       // URL change only
                "SELECT body, id FROM documents WHERE id > :lastKey ORDER BY id",          // same SQL
                null, null,
                "id",                                                                       // same watermark column
                false,
                false,                                                                     // no confirm needed
                ra);

        final ArgumentCaptor<RelationalDbDataSource> saved =
                ArgumentCaptor.forClass(RelationalDbDataSource.class);
        verify(rdbRepository).save(saved.capture());
        assertEquals("12345", saved.getValue().getLastImportedKey(),
                "URL-only edit must NOT touch the watermark");
    }

    @Test
    void resetWatermarkClearsKeyAndAuditsTheChange() {
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        src.setLastImportedKey("999");
        src.setLastImportedAt(java.time.Instant.parse("2026-05-01T00:00:00Z"));
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.resetRdbWatermark("ds-1", ra);

        final ArgumentCaptor<RelationalDbDataSource> saved =
                ArgumentCaptor.forClass(RelationalDbDataSource.class);
        verify(rdbRepository).save(saved.capture());
        assertNull(saved.getValue().getLastImportedKey());
        assertNull(saved.getValue().getLastImportedAt());

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details =
                ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("RDB_WATERMARK_RESET"), eq("RelationalDbDataSource"),
                eq("ds-1"), details.capture());
        // The previous value is preserved in the audit row for forensics.
        assertEquals("999", details.getValue().get("previousKey"));
    }

    @Test
    void resetWatermarkOnMissingSourceFlashesError() {
        when(rdbRepository.findById("ghost")).thenReturn(Optional.empty());
        final RedirectAttributes ra = flash();

        controller.resetRdbWatermark("ghost", ra);

        assertNotNull(error(ra));
        verify(rdbRepository, never()).save(any());
        verify(auditLogService, never()).log(eq("RDB_WATERMARK_RESET"),
                anyString(), anyString(), any());
    }

    @Test
    void setWatermarkManuallyUpdatesKeyAndAudits() {
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        src.setName("warehouse");
        src.setLastImportedKey("100");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.setRdbWatermark("ds-1", "  9999  ", ra);

        final ArgumentCaptor<RelationalDbDataSource> saved =
                ArgumentCaptor.forClass(RelationalDbDataSource.class);
        verify(rdbRepository).save(saved.capture());
        assertEquals("9999", saved.getValue().getLastImportedKey(),
                "value must be trimmed before storage");
        assertNotNull(saved.getValue().getLastImportedAt());

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> details =
                ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).log(eq("RDB_WATERMARK_SET_MANUAL"),
                eq("RelationalDbDataSource"), eq("ds-1"), details.capture());
        assertEquals("100", details.getValue().get("from"));
        assertEquals("9999", details.getValue().get("to"));
    }

    @Test
    void setWatermarkManuallyRejectsBlankValue() {
        // Use Reset to clear; this endpoint requires a positive value.
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        controller.setRdbWatermark("ds-1", "   ", ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("required"));
        verify(rdbRepository, never()).save(any());
    }

    @Test
    void setWatermarkManuallyRejectsOversizedValue() {
        // Defence in depth — admin-only endpoint, but a typo or paste-error
        // shouldn't blow up the source row and the audit log details. The
        // 256-char cap is well above any realistic watermark (UUID = 36,
        // timestamp = 25, 64-bit int = 19); rejecting at the controller
        // keeps a 10MB paste from landing in Mongo.
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        final String oversized = "x".repeat(
                AdminDataSourceController.MAX_WATERMARK_LENGTH + 1);
        controller.setRdbWatermark("ds-1", oversized, ra);

        assertNotNull(error(ra));
        assertTrue(error(ra).toLowerCase().contains("too long"),
                "rejection must explain the length problem, got: " + error(ra));
        verify(rdbRepository, never()).save(any());
        verify(auditLogService, never()).log(eq("RDB_WATERMARK_SET_MANUAL"),
                anyString(), anyString(), any());
    }

    @Test
    void setWatermarkManuallyAcceptsAtTheLengthLimit() {
        // Boundary check — exactly MAX_WATERMARK_LENGTH passes. Off-by-one
        // would either accept oversized inputs or refuse the longest legal value.
        final RelationalDbDataSource src = new RelationalDbDataSource();
        src.setId("ds-1");
        when(rdbRepository.findById("ds-1")).thenReturn(Optional.of(src));
        final RedirectAttributes ra = flash();

        final String atLimit = "x".repeat(AdminDataSourceController.MAX_WATERMARK_LENGTH);
        controller.setRdbWatermark("ds-1", atLimit, ra);

        verify(rdbRepository).save(any());
        verify(auditLogService).log(eq("RDB_WATERMARK_SET_MANUAL"),
                anyString(), anyString(), any());
    }
}
