/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store.mongo;

import ai.philterd.workspace.model.RetiredIndexes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/** Drops the indexes each entity's {@link RetiredIndexes} names, once every bean, and so every index, is set up. */
@Component
@ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
public class MongoRetiredIndexes implements SmartInitializingSingleton {

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoRetiredIndexes.class);

    private final MongoOperations mongoOperations;
    private final ObjectProvider<MongoMappingContext> mappingContext;

    public MongoRetiredIndexes(final MongoOperations mongoOperations,
                               final ObjectProvider<MongoMappingContext> mappingContext) {
        this.mongoOperations = mongoOperations;
        this.mappingContext = mappingContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // Absent only where MongoDB itself is not configured, such as a web test slice.
        final MongoMappingContext context = mappingContext.getIfAvailable();
        if (context == null) return;
        for (MongoPersistentEntity<?> entity : context.getPersistentEntities()) {
            final RetiredIndexes retired = entity.getType().getAnnotation(RetiredIndexes.class);
            if (retired == null) continue;
            final IndexOperations indexes = mongoOperations.indexOps(entity.getType());
            final List<String> present = indexes.getIndexInfo().stream().map(IndexInfo::getName).toList();
            for (String name : retired.value()) {
                if (present.contains(name)) {
                    indexes.dropIndex(name);
                    LOGGER.info("Dropped the retired index {} from {}.", name, entity.getCollection());
                }
            }
        }
    }
}
