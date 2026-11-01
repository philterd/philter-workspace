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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Performs live "can I actually write here?" probes for redaction destinations.
 * Used by the admin UI's Test buttons to give operators immediate feedback that
 * a destination is reachable and that the configured credentials work.
 *
 * <p>S3 calls use AWS SDK v2; credentials are either the explicitly supplied
 * static pair or the ambient credentials chain (instance profile, environment,
 * shared credentials file).</p>
 */
@Service
public class DestinationTester {

    private static final Logger LOG = LoggerFactory.getLogger(DestinationTester.class);

    private static final Region DEFAULT_S3_REGION = Region.US_EAST_1;

    private final LocalDirectoryRoots roots;

    public DestinationTester(final LocalDirectoryRoots roots) {
        this.roots = roots;
    }

    public TestResult testLocalDirectory(final String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) {
            return TestResult.failure("Directory path is required.");
        }
        final Path dir = Paths.get(directoryPath);
        if (!Files.exists(dir)) {
            return TestResult.failure("Directory does not exist: " + dir);
        }
        if (!Files.isDirectory(dir)) {
            return TestResult.failure("Path is not a directory: " + dir);
        }
        // #72: only under an administrator-configured root, by its real path.
        if (roots.confine(directoryPath) == null) {
            return TestResult.failure(LocalDirectoryRoots.NOT_ALLOWED);
        }
        final String filename = "philter-workspace-test-" + Instant.now().toEpochMilli() + ".txt";
        final Path target = dir.resolve(filename);
        try {
            Files.writeString(target, testContent("local directory"), StandardCharsets.UTF_8);
            return TestResult.success("Test file written to " + target.toAbsolutePath() + ".");
        } catch (IOException e) {
            LOG.warn("Local destination test failed for {}: {}", dir, e.toString());
            return TestResult.failure("Could not write test file to " + dir + ": " + e.getMessage());
        }
    }

    public TestResult testS3(final String bucketName, final String bucketKey,
                             final String accessKey, final String secretKey) {
        if (bucketName == null || bucketName.isBlank()) {
            return TestResult.failure("Bucket name is required.");
        }
        if (bucketKey == null || bucketKey.isBlank()) {
            return TestResult.failure("Bucket key is required.");
        }
        if (paired(accessKey, secretKey) == Paired.HALF) {
            return TestResult.failure("Provide both Access key and Secret key, or leave both blank.");
        }

        final String objectKey = joinKey(bucketKey, "philter-workspace-test-" + Instant.now().toEpochMilli() + ".txt");
        try (S3Client s3 = S3Client.builder()
                .region(DEFAULT_S3_REGION)
                .credentialsProvider(credentialsFor(accessKey, secretKey))
                .crossRegionAccessEnabled(true)
                .build()) {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(objectKey)
                            .build(),
                    RequestBody.fromString(testContent("S3"), StandardCharsets.UTF_8));
            return TestResult.success("Test object written to s3://" + bucketName + "/" + objectKey + ".");
        } catch (Exception e) {
            LOG.warn("S3 destination test failed for s3://{}/{}: {}", bucketName, objectKey, e.toString());
            return TestResult.failure("Could not write to S3: " + e.getMessage());
        }
    }

    private static AwsCredentialsProvider credentialsFor(final String accessKey, final String secretKey) {
        if (accessKey != null && !accessKey.isEmpty() && secretKey != null && !secretKey.isEmpty()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }

    private static String joinKey(final String prefix, final String filename) {
        if (prefix == null || prefix.isBlank()) return filename;
        return prefix.endsWith("/") ? prefix + filename : prefix + "/" + filename;
    }

    private static String testContent(final String destinationKind) {
        return "Philter Workspace " + destinationKind + " destination test\n"
                + "Generated at: " + Instant.now() + "\n"
                + "If you see this file, the destination is reachable from Philter Workspace.\n";
    }

    private enum Paired { BOTH, NEITHER, HALF }

    private static Paired paired(final String a, final String b) {
        final boolean aFilled = a != null && !a.isEmpty();
        final boolean bFilled = b != null && !b.isEmpty();
        if (aFilled && bFilled) return Paired.BOTH;
        if (!aFilled && !bFilled) return Paired.NEITHER;
        return Paired.HALF;
    }

    /** Outcome of a destination probe. */
    public static final class TestResult {
        private final boolean ok;
        private final String message;
        private final String error;

        private TestResult(final boolean ok, final String message, final String error) {
            this.ok = ok; this.message = message; this.error = error;
        }
        public static TestResult success(final String message) { return new TestResult(true, message, null); }
        public static TestResult failure(final String error) { return new TestResult(false, null, error); }
        public boolean isOk() { return ok; }
        public String getMessage() { return message; }
        public String getError() { return error; }
    }
}
