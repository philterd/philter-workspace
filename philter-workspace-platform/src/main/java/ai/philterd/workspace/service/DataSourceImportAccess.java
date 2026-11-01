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
import ai.philterd.workspace.model.GroupScopedSource;
import ai.philterd.workspace.repository.ElasticsearchDataSourceRepository;
import ai.philterd.workspace.repository.LocalDirectoryDataSourceRepository;
import ai.philterd.workspace.repository.OpenSearchDataSourceRepository;
import ai.philterd.workspace.repository.RelationalDbDataSourceRepository;
import ai.philterd.workspace.repository.S3DataSourceRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * Who may import documents from a data source into a batch (#72): an administrator, from any source into any batch;
 * otherwise a team lead of the batch's group, from a source available to that group. A source with no groups is for
 * administrators only.
 */
@Service
public class DataSourceImportAccess {

    private final OpenSearchDataSourceRepository openSearch;
    private final ElasticsearchDataSourceRepository elasticsearch;
    private final S3DataSourceRepository s3;
    private final RelationalDbDataSourceRepository rdb;
    private final LocalDirectoryDataSourceRepository local;
    private final BatchAccessService batchAccessService;

    public DataSourceImportAccess(final OpenSearchDataSourceRepository openSearch,
                                  final ElasticsearchDataSourceRepository elasticsearch,
                                  final S3DataSourceRepository s3,
                                  final RelationalDbDataSourceRepository rdb,
                                  final LocalDirectoryDataSourceRepository local,
                                  final BatchAccessService batchAccessService) {
        this.openSearch = openSearch;
        this.elasticsearch = elasticsearch;
        this.s3 = s3;
        this.rdb = rdb;
        this.local = local;
        this.batchAccessService = batchAccessService;
    }

    /** The source of {@code type} ({@code opensearch}, {@code elasticsearch}, {@code s3}, {@code rdb}, {@code local}), or null. */
    public GroupScopedSource find(final String type, final String id) {
        if (type == null || id == null) return null;
        return switch (type) {
            case "opensearch" -> openSearch.findById(id).orElse(null);
            case "elasticsearch" -> elasticsearch.findById(id).orElse(null);
            case "s3" -> s3.findById(id).orElse(null);
            case "rdb" -> rdb.findById(id).orElse(null);
            case "local" -> local.findById(id).orElse(null);
            default -> null;
        };
    }

    /** Saves {@code source} to its type's repository. */
    public void save(final GroupScopedSource source) {
        if (source instanceof ai.philterd.workspace.model.OpenSearchDataSource s) openSearch.save(s);
        else if (source instanceof ai.philterd.workspace.model.ElasticsearchDataSource s) elasticsearch.save(s);
        else if (source instanceof ai.philterd.workspace.model.S3DataSource s) s3.save(s);
        else if (source instanceof ai.philterd.workspace.model.RelationalDbDataSource s) rdb.save(s);
        else if (source instanceof ai.philterd.workspace.model.LocalDirectoryDataSource s) local.save(s);
        else throw new IllegalArgumentException("Not a data source.");
    }

    /** Whether {@code auth} may import from {@code source} into {@code batch}. */
    public boolean mayImport(final Authentication auth, final GroupScopedSource source, final Batch batch) {
        if (source == null || batch == null) return false;
        if (AuthUtils.isAdmin(auth)) return true;
        return batch.getGroupId() != null
                && batchAccessService.canLeadGroup(auth, batch.getGroupId())
                && source.isAvailableToGroup(batch.getGroupId());
    }

    /** The batches {@code auth} may import into at all: every one for an administrator, else those whose group they lead. */
    public List<Batch> importBatches(final Authentication auth, final Collection<Batch> batches) {
        if (AuthUtils.isAdmin(auth)) return List.copyOf(batches);
        return batches.stream()
                .filter(b -> b.getGroupId() != null && batchAccessService.canLeadGroup(auth, b.getGroupId()))
                .toList();
    }

    /** The sources {@code auth} may import from into at least one of {@code batches}, for the import tabs' lists. */
    public <S extends GroupScopedSource> List<S> importableFrom(final Authentication auth, final Collection<S> sources,
                                                                final Collection<Batch> batches) {
        return sources.stream().filter(s -> batches.stream().anyMatch(b -> mayImport(auth, s, b))).toList();
    }
}
