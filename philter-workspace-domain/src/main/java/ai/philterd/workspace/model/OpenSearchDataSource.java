/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "opensearch_data_sources")
public class OpenSearchDataSource implements GroupScopedSource {

    @Id
    private String id;

    @Indexed(unique = true, sparse = true)
    private String name;

    private String endpoint;

    /**
     * OpenSearch query (typically JSON DSL, e.g. {@code GET /index/_search} body) that returns
     * the documents to import.
     */
    private String query;

    /**
     * Field name within each hit's {@code _source} that holds the text to import for redaction.
     * For example, if hits look like {@code {"_source": {"body": "..."}}}, this is {@code body}.
     */
    private String textField;

    /**
     * Optional field name within each hit's {@code _source} whose value is used as the imported
     * document's filename. When {@code null} or empty, the OpenSearch {@code _id} is used as the
     * filename instead.
     */
    private String filenameField;

    private String username;

    /**
     * AES-GCM ciphertext of the OpenSearch password (see {@code SymmetricCipher}). Null/empty
     * means no credentials are configured for the instance.
     */
    private String encryptedPassword;

    private LocalDateTime createdAt;

    public OpenSearchDataSource() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(final String endpoint) { this.endpoint = endpoint; }

    public String getQuery() { return query; }
    public void setQuery(final String query) { this.query = query; }

    public String getTextField() { return textField; }
    public void setTextField(final String textField) { this.textField = textField; }

    public String getFilenameField() { return filenameField; }
    public void setFilenameField(final String filenameField) { this.filenameField = filenameField; }

    public String getUsername() { return username; }
    public void setUsername(final String username) { this.username = username; }

    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(final String encryptedPassword) { this.encryptedPassword = encryptedPassword; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }

    /** The groups this source is available to (#72). With none, and not {@link #allGroups}, administrators only. */
    private java.util.List<String> groupIds = new java.util.ArrayList<>();
    /** Whether this source is available to every group (#72). */
    private boolean allGroups;

    @Override
    public java.util.List<String> getGroupIds() { return groupIds; }
    @Override
    public void setGroupIds(final java.util.List<String> groupIds) {
        this.groupIds = groupIds == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(groupIds);
    }

    @Override
    public boolean isAllGroups() { return allGroups; }
    @Override
    public void setAllGroups(final boolean allGroups) { this.allGroups = allGroups; }
}
