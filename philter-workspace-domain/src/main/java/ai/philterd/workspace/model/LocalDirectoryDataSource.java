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

@Document(collection = "local_directory_data_sources")
public class LocalDirectoryDataSource implements GroupScopedSource {

    @Id
    private String id;

    @Indexed(unique = true, sparse = true)
    private String name;

    /** Absolute filesystem path on the server, e.g. {@code /var/lib/philter-workspace/incoming}. */
    private String directoryPath;

    /** File glob filter applied within the directory, e.g. {@code *.txt} or {@code **\/*.pdf}. */
    private String filenameGlob;

    private LocalDateTime createdAt;

    public LocalDirectoryDataSource() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getDirectoryPath() { return directoryPath; }
    public void setDirectoryPath(final String directoryPath) { this.directoryPath = directoryPath; }

    public String getFilenameGlob() { return filenameGlob; }
    public void setFilenameGlob(final String filenameGlob) { this.filenameGlob = filenameGlob; }

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
