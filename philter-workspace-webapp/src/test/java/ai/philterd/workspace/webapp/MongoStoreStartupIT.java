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

import ai.philterd.workspace.model.BackgroundJob;
import ai.philterd.workspace.model.Batch;
import ai.philterd.workspace.service.DocumentLockService;
import ai.philterd.workspace.store.mongo.MongoDocumentLockService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole application starts on a real MongoDB server with {@code philter-workspace.store=mongodb}.
 * Philter need not be running. Runs in {@code mvn verify}; skipped without Docker, except in CI.
 */
// The main application.properties is loaded explicitly: the test resources' file of the same name
// replaces it on the classpath, and it holds settings such as spring.data.mongodb.auto-index-creation.
// The store is set here rather than in @DynamicPropertySource, which is applied after the store
// post-processor reads it. Spring Session is left out, so no Valkey is needed.
@TestPropertySource(locations = "file:src/main/resources/application.properties")
@SpringBootTest(classes = PhilterWorkspaceApplication.class, properties = {
        "philter-workspace.store=mongodb",
        "philter-workspace.demo-data.enabled=false",
        "management.health.redis.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.session.autoconfigure.SessionAutoConfiguration,"
                + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"})
@AutoConfigureMockMvc
class MongoStoreStartupIT {

    /** Not 8.0 or 8.3.7, which refuse to start on Linux 6.19 and newer (SERVER-121912). */
    private static final String IMAGE = "mongo:8.2.12";

    private static GenericContainer<?> mongo;

    @BeforeAll
    static void startMongo() {
        DockerRequirement.require("starting Philter Workspace on MongoDB");
        mongo = new GenericContainer<>(IMAGE)
                .withExposedPorts(27017)
                .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        // Not stopped after the tests: Spring closes the cached context at JVM exit, and its MongoDB
        // client would wait for a stopped server. Testcontainers removes the container then.
        mongo.start();
        // An installation from before #83, when batch names were unique across groups through the index "name".
        try (com.mongodb.client.MongoClient client = com.mongodb.client.MongoClients.create(
                "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017))) {
            client.getDatabase("philter-workspace").getCollection("batches").createIndex(
                    new org.bson.Document("name", 1),
                    new com.mongodb.client.model.IndexOptions().name("name").unique(true).sparse(true));
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
                () -> "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/philter-workspace");
        registry.add("philter-workspace.philter.url", () -> "http://localhost:1");
        registry.add("philter-workspace.philter.api-key", () -> "sk_startup_test");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;
    @Autowired private MongoOperations mongoOperations;

    @Test
    void healthIsUpOnMongoDb() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void theMongoDbStoreIsInUseAndSqliteIsNot() {
        assertInstanceOf(MongoDocumentLockService.class, context.getBean(DocumentLockService.class));
        assertEquals(0, context.getBeanNamesForType(DataSource.class).length);
    }

    @Test
    void indexesAreCreatedOnStartup() {
        final IndexInfo groupName = index(Batch.class, "group_name");
        assertTrue(groupName.isUnique(), groupName.toString());
        assertNotNull(groupName.getPartialFilterExpression(), groupName.toString());
        // The global index on name is retired (#83), and dropped from an installation that has it.
        assertTrue(mongoOperations.indexOps(Batch.class).getIndexInfo().stream()
                .noneMatch(i -> i.getName().equals("name")), "the retired index is dropped");

        final IndexInfo running = index(BackgroundJob.class, "uniq_running_data_import_per_batch_v2");
        assertTrue(running.isUnique(), running.toString());
        assertNotNull(running.getPartialFilterExpression(), running.toString());
    }

    private IndexInfo index(final Class<?> type, final String name) {
        final List<IndexInfo> indexes = mongoOperations.indexOps(type).getIndexInfo();
        return indexes.stream().filter(i -> i.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No index " + name + " in " + indexes));
    }
}
