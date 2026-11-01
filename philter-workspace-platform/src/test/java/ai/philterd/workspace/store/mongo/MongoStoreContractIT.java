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

import ai.philterd.workspace.service.AuditLogQueryService;
import ai.philterd.workspace.service.DocumentLockService;
import ai.philterd.workspace.service.PiiFieldEncryption;
import ai.philterd.workspace.service.SymmetricCipher;
import ai.philterd.workspace.store.BackgroundJobStore;
import ai.philterd.workspace.store.DocumentQueueStore;
import ai.philterd.workspace.store.StoreContract;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;


/**
 * The shared store behaviour, on a real MongoDB server started in Docker, with the MongoDB store
 * configured as the application configures it. Runs in {@code mvn verify}. Without Docker it is
 * skipped, except in CI, where it fails rather than pass without running.
 */
@SpringBootTest(classes = MongoStoreContractIT.Store.class, properties = {
        "philter-workspace.store=mongodb",
        "spring.data.mongodb.auto-index-creation=true",
        "spring.main.web-application-type=none"})
class MongoStoreContractIT extends StoreContract {

    /** Not 8.0 or 8.3.7, which refuse to start on Linux 6.19 and newer (SERVER-121912). */
    static final String IMAGE = "mongo:8.2.12";

    private static GenericContainer<?> mongo;

    @BeforeAll
    static void startMongo() {
        DockerRequirement.require("the MongoDB store tests");
        mongo = new GenericContainer<>(IMAGE)
                .withExposedPorts(27017)
                .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        // Not stopped after the tests: Spring closes the cached context at JVM exit, and its MongoDB
        // client would wait for a stopped server. Testcontainers removes the container then.
        mongo.start();
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
                () -> "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/philter-workspace-test");
        registry.add("philter-workspace.crypto.secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    /** The MongoDB store's beans, as the application's component scan finds them. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "ai.philterd.workspace.model")
    @Import({MongoStoreConfiguration.class, MongoDocumentLockService.class, MongoDocumentQueueStore.class,
            MongoBackgroundJobStore.class, MongoAuditLogQueryService.class, SymmetricCipher.class,
            PiiFieldEncryption.DocumentCallbacks.class, PiiFieldEncryption.SpanCallbacks.class,
            PiiFieldEncryption.GeneralSettingsCallbacks.class, PiiFieldEncryption.DocumentCommentCallbacks.class,
            PiiFieldEncryption.RedactionCertificateCallbacks.class, PiiFieldEncryption.PendingUploadCallbacks.class,
            PiiFieldEncryption.DetectionRuleCallbacks.class, PiiFieldEncryption.NotificationSettingsCallbacks.class,
            PiiFieldEncryption.AuditLogCallbacks.class, PiiFieldEncryption.BackgroundJobCallbacks.class,
            PiiFieldEncryption.DataImportLogEntryCallbacks.class})
    static class Store {
    }

    @Autowired private ApplicationContext context;
    @Autowired private MongoOperations mongoOperations;
    @Autowired private DocumentLockService locks;
    @Autowired private DocumentQueueStore queue;
    @Autowired private BackgroundJobStore jobs;
    @Autowired private AuditLogQueryService audit;

    /** Empties every collection but keeps its indexes, which are created once at startup. */
    @BeforeEach
    void empty() {
        for (String name : mongoOperations.getCollectionNames()) {
            mongoOperations.getCollection(name).deleteMany(new org.bson.Document());
        }
    }

    @Override
    protected <R> R repository(final Class<R> type) {
        return context.getBean(type);
    }

    @Override
    protected DocumentLockService locks() {
        return locks;
    }

    @Override
    protected DocumentQueueStore queue() {
        return queue;
    }

    @Override
    protected BackgroundJobStore jobs() {
        return jobs;
    }

    @Override
    protected AuditLogQueryService audit() {
        return audit;
    }

    @Override
    protected <T> T save(final T entity) {
        return mongoOperations.save(entity);
    }

    @Override
    protected <T> Optional<T> findById(final Class<T> type, final String id) {
        return Optional.ofNullable(mongoOperations.findById(id, type));
    }

    @Override
    protected String raw(final Class<?> type, final String id) {
        // Spring Data stores an id that is a valid ObjectId as one, and any other id as a string.
        final Object key = org.bson.types.ObjectId.isValid(id) ? new org.bson.types.ObjectId(id) : id;
        final org.bson.Document doc = mongoOperations.getCollection(mongoOperations.getCollectionName(type))
                .find(new org.bson.Document("_id", key)).first();
        return doc == null ? null : doc.toJson();
    }

    @Override
    protected byte[] rawBytes(final Class<?> type, final String id, final String field) {
        final Object key = org.bson.types.ObjectId.isValid(id) ? new org.bson.types.ObjectId(id) : id;
        final org.bson.Document doc = mongoOperations.getCollection(mongoOperations.getCollectionName(type))
                .find(new org.bson.Document("_id", key)).first();
        return doc == null ? null : doc.get(field, org.bson.types.Binary.class).getData();
    }

    @Override
    protected void setField(final Class<?> type, final String id, final String field, final Object value) {
        // By collection name, so Spring Data does not add a version increment.
        mongoOperations.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set(field, value),
                mongoOperations.getCollectionName(type));
    }

    @Override
    protected void removeField(final Class<?> type, final String id, final String field) {
        mongoOperations.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().unset(field),
                mongoOperations.getCollectionName(type));
    }
}
