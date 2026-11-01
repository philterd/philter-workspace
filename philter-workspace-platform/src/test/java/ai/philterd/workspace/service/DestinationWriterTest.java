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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DestinationWriterTest {

    private final DestinationWriter writer = new DestinationWriter(mock(SymmetricCipher.class), new LocalDirectoryRoots(System.getProperty("java.io.tmpdir")));

    private static LocalDirectoryDestination dest(final Path dir) {
        final LocalDirectoryDestination d = new LocalDirectoryDestination();
        d.setDirectoryPath(dir.toString());
        return d;
    }

    @Test
    void writeLocalBytesWritesContent(@TempDir final Path dir) throws Exception {
        final byte[] payload = "hello\nworld".getBytes(StandardCharsets.UTF_8);

        final DestinationWriter.Result result = writer.writeLocal(dest(dir), "out.jsonl", payload);

        assertTrue(result.isOk(), () -> "expected success, got: " + result.getError());
        assertArrayEquals(payload, Files.readAllBytes(dir.resolve("out.jsonl")));
    }

    @Test
    void writeLocalFileStreamsContent(@TempDir final Path dir, @TempDir final Path src) throws Exception {
        // The streaming overload copies a source file (the temp export spool) into the destination.
        final Path source = src.resolve("spool.jsonl");
        final byte[] payload = "{\"text\":\"a\",\"spans\":[]}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(source, payload);

        final DestinationWriter.Result result = writer.writeLocal(dest(dir), "out.jsonl", source);

        assertTrue(result.isOk(), () -> "expected success, got: " + result.getError());
        assertArrayEquals(payload, Files.readAllBytes(dir.resolve("out.jsonl")));
    }

    @Test
    void writeLocalFileRefusesPathTraversal(@TempDir final Path dir, @TempDir final Path src) throws Exception {
        final Path source = src.resolve("spool.jsonl");
        Files.write(source, "x".getBytes(StandardCharsets.UTF_8));

        final DestinationWriter.Result result = writer.writeLocal(dest(dir), "../escaped.jsonl", source);

        assertFalse(result.isOk(), "a filename escaping the destination directory must be refused");
        assertFalse(Files.exists(dir.getParent().resolve("escaped.jsonl")));
    }

    @Test
    void writeLocalFailsWhenDirectoryMissing(@TempDir final Path dir) throws Exception {
        final Path source = dir.resolve("spool.jsonl");
        Files.write(source, "x".getBytes(StandardCharsets.UTF_8));
        final LocalDirectoryDestination missing = dest(dir.resolve("does-not-exist"));

        final DestinationWriter.Result result = writer.writeLocal(missing, "out.jsonl", source);

        assertFalse(result.isOk(), "writing to a non-existent directory must fail");
    }

    @Test
    void aWriteReplacesTheFileWholeAndLeavesNoTemporaryFileBehind(@TempDir final Path dir) throws Exception {
        final DestinationWriter writer = new DestinationWriter(org.mockito.Mockito.mock(SymmetricCipher.class), new LocalDirectoryRoots(System.getProperty("java.io.tmpdir")));
        final ai.philterd.workspace.model.LocalDirectoryDestination d = new ai.philterd.workspace.model.LocalDirectoryDestination();
        d.setDirectoryPath(dir.toString());

        assertTrue(writer.writeLocal(d, "d1/out.txt", "first".getBytes(java.nio.charset.StandardCharsets.UTF_8)).isOk());
        assertTrue(writer.writeLocal(d, "d1/out.txt", "second".getBytes(java.nio.charset.StandardCharsets.UTF_8)).isOk());

        assertEquals("second", java.nio.file.Files.readString(dir.resolve("d1/out.txt")));
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dir.resolve("d1"))) {
            assertEquals(java.util.List.of("out.txt"), files.map(f -> f.getFileName().toString()).toList());
        }
        final Path direct = java.nio.file.Files.writeString(dir.resolve("direct.txt"), "x");
        assertEquals(java.nio.file.Files.getPosixFilePermissions(direct),
                java.nio.file.Files.getPosixFilePermissions(dir.resolve("d1/out.txt")),
                "the same permissions as a direct write, not narrowed to the owner");
    }

    @Test
    void aDestinationOutsideTheAllowedRootsOrASubfolderLinkedOutOfItIsRefused(@TempDir final Path tmp) throws Exception {
        // #72: the destination must be under an allowed root, and a write may not follow a link out of it.
        final Path root = java.nio.file.Files.createDirectory(tmp.resolve("root"));
        final Path outside = java.nio.file.Files.createDirectory(tmp.resolve("outside"));
        final DestinationWriter confined = new DestinationWriter(mock(SymmetricCipher.class),
                new LocalDirectoryRoots(root.toString()));

        final DestinationWriter.Result elsewhere = confined.writeLocal(dest(outside), "a.txt", "x".getBytes());
        org.junit.jupiter.api.Assertions.assertFalse(elsewhere.isOk());
        org.junit.jupiter.api.Assertions.assertEquals(LocalDirectoryRoots.NOT_ALLOWED, elsewhere.getError());

        java.nio.file.Files.createSymbolicLink(root.resolve("batch"), outside);
        final DestinationWriter.Result linked = confined.writeLocal(dest(root), "batch/a.txt", "x".getBytes());
        org.junit.jupiter.api.Assertions.assertFalse(linked.isOk());
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(outside.resolve("a.txt")),
                "nothing is written through the link");

        org.junit.jupiter.api.Assertions.assertTrue(confined.writeLocal(dest(root), "ok/a.txt", "x".getBytes()).isOk());
    }
}
