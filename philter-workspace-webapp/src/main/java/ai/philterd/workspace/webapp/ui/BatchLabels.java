/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.ui;

import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.model.Group;
import ai.philterd.workspace.repository.GroupRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Each batch's label in a list someone chooses from: its name, followed by its group's name when another batch in
 * the same list has the same name. Names are unique only within a group (#83), so someone who sees several groups
 * could otherwise not tell two batches apart. Only the groups of the batches listed are named, and the caller lists
 * only batches the person may see.
 */
@Component
public class BatchLabels {

    private final GroupRepository groupRepository;

    public BatchLabels(final GroupRepository groupRepository) {
        this.groupRepository = groupRepository;
    }

    /** Batch id to label, for {@code batches}. */
    public Map<String, String> of(final List<Batch> batches) {
        final Map<String, Long> counts = batches.stream().filter(b -> b.getName() != null)
                .collect(Collectors.groupingBy(Batch::getName, Collectors.counting()));
        final Map<String, String> groupNames = new HashMap<>();
        final Map<String, String> labels = new HashMap<>();
        for (Batch b : batches) {
            final String name = b.getName() == null ? b.getId() : b.getName();
            if (counts.getOrDefault(b.getName(), 0L) < 2) {
                labels.put(b.getId(), name);
                continue;
            }
            final String group = b.getGroupId() == null ? "no group" : groupNames.computeIfAbsent(b.getGroupId(),
                    id -> groupRepository.findById(id).map(Group::getName).orElse(id));
            labels.put(b.getId(), name + " (" + group + ")");
        }
        return labels;
    }
}
