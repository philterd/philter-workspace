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

import ai.philterd.workspace.model.Group;
import ai.philterd.workspace.model.OpenSearchDataSource;
import ai.philterd.workspace.service.DataSourceHostAllowList;
import ai.philterd.workspace.service.DataSourceImportAccess;
import ai.philterd.workspace.service.JdbcUrlValidator;
import ai.philterd.workspace.service.LocalDirectoryRoots;
import ai.philterd.workspace.service.RdbPreviewService;
import ai.philterd.workspace.service.SymmetricCipher;
import ai.philterd.workspace.webapp.controllers.AdminDataSourceController;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin → Data Sources shows each source's groups and a form to change them (#72). */
@WebMvcTest(controllers = AdminDataSourceController.class)
@Import(SecurityConfig.class)
class AdminDataSourcesPageTest extends WebMvcSliceMocks {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private SymmetricCipher cipher;
    @MockitoBean private DataSourceHostAllowList hostAllowList;
    @MockitoBean private JdbcUrlValidator jdbcUrlValidator;
    @MockitoBean private RdbPreviewService rdbPreviewService;
    @MockitoBean private DataSourceImportAccess dataSourceImportAccess;
    @MockitoBean private LocalDirectoryRoots localDirectoryRoots;

    @Test
    void eachSourceShowsItsGroupsAndAFormToChangeThem() throws Exception {
        final OpenSearchDataSource scoped = new OpenSearchDataSource();
        scoped.setId("os-1");
        scoped.setName("Claims archive");
        scoped.setEndpoint("https://claims.example.com");
        scoped.setGroupIds(List.of("g-claims"));
        final OpenSearchDataSource unscoped = new OpenSearchDataSource();
        unscoped.setId("os-2");
        unscoped.setName("Old archive");
        unscoped.setEndpoint("https://old.example.com");
        when(openSearchDataSourceRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(scoped, unscoped)));
        when(elasticsearchDataSourceRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(s3DataSourceRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(relationalDbDataSourceRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(localDirectoryDataSourceRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        final Group claims = new Group();
        claims.setId("g-claims");
        claims.setName("Claims");
        when(groupRepository.findAll(any(Sort.class))).thenReturn(List.of(claims));

        final String html = mockMvc.perform(get("/admin/data-sources").with(signedIn("admin", "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // The scoped source's label, and the group's checkbox in each of the two rows' forms.
        org.junit.jupiter.api.Assertions.assertEquals(3, html.split(">Claims</span>", -1).length - 1,
                "the scoped source names its group");
        assertTrue(html.contains(">Administrators only</span>"), "a source with no groups, as after upgrade");
        assertTrue(html.contains("action=\"/admin/data-sources/groups/opensearch/os-1\""), html);
        assertTrue(html.contains("value=\"g-claims\" checked"), "the form starts from the source's groups");
    }
}
