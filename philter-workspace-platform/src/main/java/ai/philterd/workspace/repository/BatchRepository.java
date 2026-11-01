/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.repository;

import ai.philterd.workspace.model.Batch;
import org.springframework.stereotype.Repository;

import java.util.Collection;

@Repository
public interface BatchRepository extends WorkspaceRepository<Batch, String> {
    /** Whether {@code groupId} has a batch named {@code name}. Names are unique within a group (#83). */
    boolean existsByGroupIdAndName(String groupId, String name);
    boolean existsByWeightSetId(String weightSetId);
    java.util.List<Batch> findByWeightSetId(String weightSetId);
    boolean existsByFinalizationPolicyId(String finalizationPolicyId);
    java.util.List<Batch> findByFinalizationPolicyId(String finalizationPolicyId);
    java.util.List<Batch> findByComplianceProfileId(String complianceProfileId);
    boolean existsByDestinationTypeAndDestinationId(String destinationType, String destinationId);
    java.util.List<Batch> findByGroupIdIn(Collection<String> groupIds);
}
