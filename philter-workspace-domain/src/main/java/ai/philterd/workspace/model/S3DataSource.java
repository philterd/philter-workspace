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

@Document(collection = "s3_data_sources")
public class S3DataSource implements GroupScopedSource {

    @Id
    private String id;

    @Indexed(unique = true, sparse = true)
    private String name;

    /**
     * Optional S3-API endpoint URL. Null/blank means use the AWS default endpoint
     * for the configured region. Set to {@code http://minio:9000} (or another
     * S3-compatible host like Cloudflare R2 or Backblaze B2) to point this data
     * source at non-AWS storage. The matching admin form labels this field
     * "Endpoint URL (optional)".
     */
    private String endpoint;

    private String bucketName;

    private String bucketKey;

    private String filenameGlob;

    /**
     * AES-GCM ciphertext of the AWS access key id (see {@code SymmetricCipher}). Null/empty
     * means the bucket is read with the application's ambient AWS credentials.
     */
    private String encryptedAccessKey;

    /**
     * AES-GCM ciphertext of the AWS secret access key. Null/empty means no explicit credential
     * was configured for this data source.
     */
    private String encryptedSecretKey;

    private LocalDateTime createdAt;

    public S3DataSource() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(final String endpoint) { this.endpoint = endpoint; }

    public String getBucketName() { return bucketName; }
    public void setBucketName(final String bucketName) { this.bucketName = bucketName; }

    public String getBucketKey() { return bucketKey; }
    public void setBucketKey(final String bucketKey) { this.bucketKey = bucketKey; }

    public String getFilenameGlob() { return filenameGlob; }
    public void setFilenameGlob(final String filenameGlob) { this.filenameGlob = filenameGlob; }

    public String getEncryptedAccessKey() { return encryptedAccessKey; }
    public void setEncryptedAccessKey(final String encryptedAccessKey) { this.encryptedAccessKey = encryptedAccessKey; }

    public String getEncryptedSecretKey() { return encryptedSecretKey; }
    public void setEncryptedSecretKey(final String encryptedSecretKey) { this.encryptedSecretKey = encryptedSecretKey; }

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
