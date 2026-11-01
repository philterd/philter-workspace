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

import java.util.List;

/**
 * A data source scoped to groups (#72): documents can be imported from it only into a batch whose group it is
 * available to. With no groups and not {@link #isAllGroups()}, it is for administrators only, which is where a source
 * saved before scoping existed starts.
 */
public interface GroupScopedSource {

    String getId();

    String getName();

    /** The groups the source is available to; ignored when {@link #isAllGroups()}. */
    List<String> getGroupIds();

    void setGroupIds(List<String> groupIds);

    /** Whether the source is available to every group. */
    boolean isAllGroups();

    void setAllGroups(boolean allGroups);

    /** Whether the source is available to a batch in {@code groupId}. */
    default boolean isAvailableToGroup(final String groupId) {
        return isAllGroups() || groupId != null && getGroupIds() != null && getGroupIds().contains(groupId);
    }
}
