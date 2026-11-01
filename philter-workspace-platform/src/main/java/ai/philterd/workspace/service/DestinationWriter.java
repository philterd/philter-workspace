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

import ai.philterd.workspace.model.LocalDirectoryDestination;
import ai.philterd.workspace.model.S3Destination;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Writes a finished export payload (e.g. a JSONL file) to a configured destination.
 *
 * <p>This is the write-side counterpart to {@link DestinationTester}, which only does
 * "can I reach this?" probes. {@code DestinationWriter} is what actual export jobs
 * call to push their bytes through. Each write produces a single result describing
 * either the location written (success) or the reason it failed.
 */
@Service
public class DestinationWriter {

    private static final Logger LOG = LoggerFactory.getLogger(DestinationWriter.class);

    private static final Region DEFAULT_S3_REGION = Region.US_EAST_1;

    private final SymmetricCipher symmetricCipher;

    private final LocalDirectoryRoots roots;

    public DestinationWriter(final SymmetricCipher symmetricCipher, final LocalDirectoryRoots roots) {
        this.symmetricCipher = symmetricCipher;
        this.roots = roots;
    }

    /**
     * Writes {@code payload} as {@code filename} into the destination's directory.
     * Returns the absolute path actually written so the caller can show it back to
     * the operator in a success banner.
     */
    public Result writeLocal(final LocalDirectoryDestination destination,
                             final String filename,
                             final byte[] payload) {
        final LocalTarget t = resolveLocalTarget(destination, filename);
        if (t.error != null) {
            return Result.failure(t.error);
        }
        try {
            final Path part = partFile(t.target);
            try {
                // CREATE_NEW: never write through anything already at the temporary name, such as a link (#72).
                Files.write(part, payload, java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE);
                publish(part, t.target);
            } finally {
                Files.deleteIfExists(part);
            }
            return Result.success("Wrote " + payload.length + " bytes to " + t.target);
        } catch (IOException e) {
            return localWriteFailure(t.target, e);
        }
    }

    /**
     * Streams {@code source} (a file on disk) into the destination directory as {@code filename},
     * so a large export is copied without holding its bytes in memory.
     */
    public Result writeLocal(final LocalDirectoryDestination destination,
                             final String filename,
                             final Path source) {
        final LocalTarget t = resolveLocalTarget(destination, filename);
        if (t.error != null) {
            return Result.failure(t.error);
        }
        try {
            final Path part = partFile(t.target);
            try {
                // Without REPLACE_EXISTING: never write through anything already at the temporary name (#72).
                Files.copy(source, part);
                publish(part, t.target);
            } finally {
                Files.deleteIfExists(part);
            }
            return Result.success("Wrote " + Files.size(t.target) + " bytes to " + t.target);
        } catch (IOException e) {
            return localWriteFailure(t.target, e);
        }
    }

    /**
     * A hidden temporary file beside {@code target}. The content is written there first and then moved into place,
     * so a process watching the destination never reads a partly written file.
     */
    private static Path partFile(final Path target) {
        // Not Files.createTempFile, which makes the file readable by its owner only; the file keeps the process's
        // usual permissions, as a direct write would, so whatever reads the destination can still read it.
        return target.resolveSibling(".philter-workspace-" + java.util.UUID.randomUUID() + ".part");
    }

    private static void publish(final Path part, final Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Result localWriteFailure(final Path target, final IOException e) {
        // The file name can come from an uploaded document's name, so only the directory is logged.
        LOG.warn("Local destination write failed under {}: {}", target.getParent(), e.getClass().getName());
        if (e instanceof java.nio.file.NoSuchFileException) {
            // NoSuchFileException's getMessage() is just the path, which leads to messages like
            // "Could not write to /a/b: /a/b". Translate it into something an operator can act on.
            return Result.failure("Could not write to " + target
                    + ": parent directory does not exist or is not writable.");
        }
        return Result.failure("Could not write to " + target + ": " + e.getMessage());
    }

    /** Validates the destination directory and resolves (and creates the parent of) the target path. */
    private LocalTarget resolveLocalTarget(final LocalDirectoryDestination destination, final String filename) {
        if (destination == null || destination.getDirectoryPath() == null
                || destination.getDirectoryPath().isBlank()) {
            return LocalTarget.fail("Local directory destination is missing a directory path.");
        }
        final Path configured = Paths.get(destination.getDirectoryPath()).toAbsolutePath().normalize();
        if (!Files.exists(configured)) {
            return LocalTarget.fail("Directory does not exist: " + configured);
        }
        if (!Files.isDirectory(configured)) {
            return LocalTarget.fail("Path is not a directory: " + configured);
        }
        // #72: only under an administrator-configured root, by its real path.
        final Path dir = roots.confine(destination.getDirectoryPath());
        if (dir == null) {
            return LocalTarget.fail(LocalDirectoryRoots.NOT_ALLOWED);
        }
        final Path target = dir.resolve(filename).normalize();
        // Path-traversal defence: a filename like "../etc/passwd" would resolve outside the configured
        // destination directory. Refuse anything that doesn't ultimately live under the configured root.
        if (!target.startsWith(dir)) {
            return LocalTarget.fail("Refusing to write outside the destination directory: " + filename);
        }
        // The BIO export uses "<batch-slug>/<doc-slug>.bio" which needs the per-batch subdirectory to
        // exist first. createDirectories is a no-op when it already exists; the startsWith check above
        // guarantees we only mkdir inside the configured destination root.
        final Path parent = target.getParent();
        if (parent != null && !parent.equals(dir)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                return LocalTarget.fail("Could not create destination subdirectory " + parent + ": " + e.getMessage());
            }
        }
        // A subfolder that is a symbolic link out of the directory is refused (#72).
        try {
            if (parent != null && !parent.toRealPath().startsWith(dir)) {
                return LocalTarget.fail("Refusing to write outside the destination directory: " + filename);
            }
        } catch (IOException e) {
            return LocalTarget.fail("Could not check destination subdirectory " + parent + ": " + e.getMessage());
        }
        return LocalTarget.ok(target);
    }

    /** Either a resolved target path or the reason it could not be resolved. */
    private static final class LocalTarget {
        private final Path target;
        private final String error;
        private LocalTarget(final Path target, final String error) { this.target = target; this.error = error; }
        static LocalTarget ok(final Path target) { return new LocalTarget(target, null); }
        static LocalTarget fail(final String error) { return new LocalTarget(null, error); }
    }

    /**
     * Uploads {@code payload} to {@code s3://<bucket>/<bucketKey>/<filename>}. Credentials
     * come from the destination's encrypted access/secret pair when set, otherwise from
     * the application's ambient AWS credentials chain.
     */
    public Result writeS3(final S3Destination destination,
                          final String filename,
                          final byte[] payload) {
        return putS3(destination, filename, RequestBody.fromBytes(payload), payload.length);
    }

    /**
     * Uploads {@code source} (a file on disk) to S3, streaming it from disk rather than holding its
     * bytes in memory. The content length is taken from the file, so no multipart upload is needed.
     */
    public Result writeS3(final S3Destination destination,
                          final String filename,
                          final Path source) {
        final long bytes;
        try {
            bytes = Files.size(source);
        } catch (IOException e) {
            return Result.failure("Could not read export file for upload: " + e.getMessage());
        }
        return putS3(destination, filename, RequestBody.fromFile(source), bytes);
    }

    private Result putS3(final S3Destination destination, final String filename,
                         final RequestBody body, final long bytes) {
        if (destination == null) {
            return Result.failure("S3 destination is missing.");
        }
        if (destination.getBucketName() == null || destination.getBucketName().isBlank()) {
            return Result.failure("S3 destination has no bucket name.");
        }
        final String objectKey = joinKey(destination.getBucketKey(), filename);
        final String access = decryptOrEmpty(destination.getEncryptedAccessKey());
        final String secret = decryptOrEmpty(destination.getEncryptedSecretKey());
        try (S3Client s3 = S3Client.builder()
                .region(DEFAULT_S3_REGION)
                .credentialsProvider(credentialsFor(access, secret))
                .crossRegionAccessEnabled(true)
                .build()) {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(destination.getBucketName())
                            .key(objectKey)
                            .build(),
                    body);
            return Result.success("Uploaded " + bytes + " bytes to s3://"
                    + destination.getBucketName() + "/" + objectKey);
        } catch (Exception e) {
            // The object key can carry an uploaded document's name, so only the bucket is logged.
            LOG.warn("S3 destination write failed for bucket {}: {}", destination.getBucketName(),
                    e.getClass().getName());
            return Result.failure("Could not write to S3: " + e.getMessage());
        }
    }

    private String decryptOrEmpty(final String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) return "";
        try {
            return symmetricCipher.decrypt(ciphertext);
        } catch (RuntimeException e) {
            LOG.warn("Could not decrypt destination credential, falling back to ambient AWS credentials.");
            return "";
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

    /** Outcome of a destination write. */
    public static final class Result {
        private final boolean ok;
        private final String message;
        private final String error;

        private Result(final boolean ok, final String message, final String error) {
            this.ok = ok; this.message = message; this.error = error;
        }
        public static Result success(final String message) { return new Result(true, message, null); }
        public static Result failure(final String error) { return new Result(false, null, error); }
        public boolean isOk() { return ok; }
        public String getMessage() { return message; }
        public String getError() { return error; }
    }
}
