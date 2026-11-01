/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.service;

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.OpenSearchDataSource;
import ai.philterd.workspace.repository.ElasticsearchDataSourceRepository;
import ai.philterd.workspace.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.workspace.repository.OpenSearchDataSourceRepository;
import ai.philterd.workspace.repository.RelationalDbDataSourceRepository;
import ai.philterd.workspace.repository.S3DataSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Who may import from a data source into a batch (#72). */
class DataSourceImportAccessTest {

    private final BatchAccessService batchAccess = mock(BatchAccessService.class);
    private final DataSourceImportAccess access = new DataSourceImportAccess(mock(OpenSearchDataSourceRepository.class),
            mock(ElasticsearchDataSourceRepository.class), mock(S3DataSourceRepository.class),
            mock(RelationalDbDataSourceRepository.class), mock(LocalDirectoryDataSourceRepository.class), batchAccess);

    private static Authentication as(final String role) {
        return new UsernamePasswordAuthenticationToken("lead@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Batch batch(final String groupId) {
        final Batch b = new Batch();
        b.setId("b-" + groupId);
        b.setGroupId(groupId);
        return b;
    }

    private static OpenSearchDataSource source(final boolean all, final String... groups) {
        final OpenSearchDataSource s = new OpenSearchDataSource();
        s.setAllGroups(all);
        s.setGroupIds(List.of(groups));
        return s;
    }

    @Test
    void aTeamLeadImportsOnlyFromSourcesAvailableToTheBatchsGroup() {
        final Authentication lead = as("USER");
        when(batchAccess.canLeadGroup(eq(lead), eq("claims"))).thenReturn(true);

        assertTrue(access.mayImport(lead, source(false, "claims"), batch("claims")));
        assertTrue(access.mayImport(lead, source(true), batch("claims")), "all groups");
        assertFalse(access.mayImport(lead, source(false, "litigation"), batch("claims")), "another group's source");
        assertFalse(access.mayImport(lead, source(false), batch("claims")), "administrators only");
        assertFalse(access.mayImport(lead, source(true), batch("litigation")), "a batch they do not lead");
        assertFalse(access.mayImport(lead, null, batch("claims")), "a missing source");
    }

    @Test
    void aReviewerWhoIsNotATeamLeadMayNotImport() {
        final Authentication reviewer = as("USER");
        when(batchAccess.canLeadGroup(any(), any())).thenReturn(false);
        assertFalse(access.mayImport(reviewer, source(true), batch("claims")));
    }

    @Test
    void anAdministratorImportsFromAnySourceIncludingAdministratorsOnlyOnes() {
        assertTrue(access.mayImport(as("ADMIN"), source(false), batch("claims")));
        assertTrue(access.mayImport(as("ADMIN"), source(false, "litigation"), batch("claims")));
    }

    @Test
    void theImportTabsOfferOnlyBatchesTheyLeadAndSourcesUsableWithThem() {
        final Authentication lead = as("USER");
        when(batchAccess.canLeadGroup(eq(lead), eq("claims"))).thenReturn(true);
        final List<Batch> batches = access.importBatches(lead, List.of(batch("claims"), batch("litigation")));
        assertEquals(List.of("b-claims"), batches.stream().map(Batch::getId).toList());

        final OpenSearchDataSource ours = source(false, "claims");
        final OpenSearchDataSource theirs = source(false, "litigation");
        assertEquals(List.of(ours), access.importableFrom(lead, List.of(ours, theirs), batches));
        assertNull(access.find("ftp", "x"), "an unknown type");
    }
}
