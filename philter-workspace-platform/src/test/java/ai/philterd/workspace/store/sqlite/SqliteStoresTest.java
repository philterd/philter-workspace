/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store.sqlite;

import ai.philterd.workspace.model.BackgroundJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** SQLite store behaviour with no MongoDB counterpart; the shared behaviour is in {@code SqliteStoreContractTest}. */
class SqliteStoresTest {

    @TempDir
    Path dir;

    private SqliteTestStore db;

    @BeforeEach
    void open() throws Exception {
        db = new SqliteTestStore(dir);
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    @Test
    void existingDatabaseWithTheOldIndexGetsTheNewOne() throws Exception {
        // A database created before the rename: the table and the old index, which left out S3_INGEST.
        db.jdbc.execute("CREATE TABLE background_jobs (id TEXT PRIMARY KEY NOT NULL, version INTEGER, doc TEXT NOT NULL) STRICT");
        db.jdbc.execute("CREATE UNIQUE INDEX \"ix_background_jobs_uniq_running_data_import_per_batch\" "
                + "ON background_jobs (json_extract(doc, '$.batchId')) WHERE json_extract(doc, '$.status') = 'RUNNING' "
                + "AND json_extract(doc, '$.type') IN ('OPENSEARCH_INGEST', 'ELASTICSEARCH_INGEST', "
                + "'LOCAL_DIRECTORY_INGEST', 'RDB_INGEST')");

        final SqliteBackgroundJobStore jobs = new SqliteBackgroundJobStore(db.store);
        final BackgroundJob running = db.store.save(job("b1", BackgroundJob.TYPE_RDB_INGEST));
        final BackgroundJob s3 = db.store.save(job("b1", BackgroundJob.TYPE_S3_INGEST));

        assertNotNull(jobs.claimPending(running.getId()));
        assertNull(jobs.claimPending(s3.getId()));
        assertEquals(List.of("ix_background_jobs_uniq_running_data_import_per_batch",
                        "ix_background_jobs_uniq_running_data_import_per_batch_v2"),
                db.jdbc.queryForList("SELECT name FROM sqlite_master WHERE type = 'index' "
                        + "AND name LIKE 'ix_background_jobs_uniq%' ORDER BY name", String.class));
    }

    @Test
    void theRetiredGlobalBatchNameIndexIsDropped() throws Exception {
        // A database from before #83, when batch names were unique across groups.
        db.jdbc.execute("CREATE TABLE batches (id TEXT PRIMARY KEY NOT NULL, version INTEGER, doc TEXT NOT NULL) STRICT");
        db.jdbc.execute("CREATE UNIQUE INDEX \"ix_batches_name\" ON batches (json_extract(doc, '$.name'))");

        for (String group : List.of("g1", "g2")) {
            final ai.philterd.workspace.model.Batch b = new ai.philterd.workspace.model.Batch();
            b.setGroupId(group);
            b.setName("Sample");
            db.store.save(b);
        }

        assertEquals(List.of("ix_batches_group_name"),
                db.jdbc.queryForList("SELECT name FROM sqlite_master WHERE type = 'index' "
                        + "AND tbl_name = 'batches' AND name LIKE 'ix_%' ORDER BY name", String.class));
    }

    private static BackgroundJob job(final String batchId, final String type) {
        final BackgroundJob j = new BackgroundJob();
        j.setBatchId(batchId);
        j.setType(type);
        j.setStatus(BackgroundJob.STATUS_PENDING);
        return j;
    }
}
