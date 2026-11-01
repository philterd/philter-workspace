/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DestinationTester}. Local directory tests run against a real
 * temporary directory. S3 network calls are not exercised here — those require
 * live AWS or LocalStack and are covered by the controller's mock-based tests
 * in philter-workspace-webapp.
 */
class DestinationTesterTest {

    private final DestinationTester tester = new DestinationTester(new LocalDirectoryRoots(System.getProperty("java.io.tmpdir")));

    // --- Local directory ----------------------------------------------------

    @Test
    void localTestWritesFileToExistingDirectory(@TempDir final Path dir) throws IOException {
        final DestinationTester.TestResult result = tester.testLocalDirectory(dir.toString());

        assertTrue(result.isOk(), () -> "expected success, got: " + result.getError());
        assertNotNull(result.getMessage());
        try (Stream<Path> files = Files.list(dir)) {
            final long count = files.filter(p -> p.getFileName().toString().startsWith("philter-workspace-test-"))
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .count();
            assertEquals(1, count, "exactly one philter-workspace-test-*.txt file should have been written");
        }
    }

    @Test
    void localTestWrittenFileIsReferencedInTheSuccessMessage(@TempDir final Path dir) {
        final DestinationTester.TestResult result = tester.testLocalDirectory(dir.toString());

        assertTrue(result.isOk());
        assertTrue(result.getMessage().contains(dir.toAbsolutePath().toString()),
                () -> "success message should reference the directory: " + result.getMessage());
    }

    @Test
    void localTestRejectsBlankPath() {
        final DestinationTester.TestResult result = tester.testLocalDirectory("   ");

        assertFalse(result.isOk());
        assertEquals("Directory path is required.", result.getError());
    }

    @Test
    void localTestRejectsNullPath() {
        final DestinationTester.TestResult result = tester.testLocalDirectory(null);

        assertFalse(result.isOk());
        assertEquals("Directory path is required.", result.getError());
    }

    @Test
    void localTestReportsMissingDirectory() {
        final DestinationTester.TestResult result =
                tester.testLocalDirectory("/this/path/should/not/exist/anywhere/12345");

        assertFalse(result.isOk());
        assertNotNull(result.getError());
        assertTrue(result.getError().startsWith("Directory does not exist:"));
    }

    @Test
    void localTestReportsNonDirectoryPath(@TempDir final Path dir) throws IOException {
        final Path file = Files.writeString(dir.resolve("not-a-dir.txt"), "hi");
        final DestinationTester.TestResult result = tester.testLocalDirectory(file.toString());

        assertFalse(result.isOk());
        assertTrue(result.getError().startsWith("Path is not a directory:"));
    }

    // --- S3 input validation (no network) ----------------------------------

    @Test
    void s3RejectsBlankBucket() {
        final DestinationTester.TestResult result = tester.testS3("", "k/", null, null);
        assertFalse(result.isOk());
        assertEquals("Bucket name is required.", result.getError());
    }

    @Test
    void s3RejectsBlankBucketKey() {
        final DestinationTester.TestResult result = tester.testS3("bucket", "", null, null);
        assertFalse(result.isOk());
        assertEquals("Bucket key is required.", result.getError());
    }

    @Test
    void s3RejectsHalfSuppliedCredentials() {
        final DestinationTester.TestResult result = tester.testS3("bucket", "k/", "AKIA", "");
        assertFalse(result.isOk());
        assertEquals("Provide both Access key and Secret key, or leave both blank.", result.getError());
    }

}
