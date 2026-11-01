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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local Directory sources and destinations are confined to allowed roots (#72). */
class LocalDirectoryRootsTest {

    @Test
    void aFolderUnderARootIsAllowedByItsRealPath(@TempDir final Path tmp) throws Exception {
        final Path root = Files.createDirectory(tmp.resolve("root"));
        final Path inside = Files.createDirectories(root.resolve("incoming/2026"));
        final LocalDirectoryRoots roots = new LocalDirectoryRoots(root + ", /nonexistent/elsewhere");

        assertEquals(inside.toRealPath(), roots.confine(inside.toString()));
        assertEquals(root.toRealPath(), roots.confine(root.toString()), "a root itself");
        assertEquals(inside.toRealPath(), roots.confine(root + "/incoming/../incoming/2026"));
    }

    @Test
    void aFolderOutsideTheRootsOrMissingIsRefused(@TempDir final Path tmp) throws Exception {
        final Path root = Files.createDirectory(tmp.resolve("root"));
        final Path outside = Files.createDirectory(tmp.resolve("outside"));
        final LocalDirectoryRoots roots = new LocalDirectoryRoots(root.toString());

        assertNull(roots.confine(outside.toString()));
        assertNull(roots.confine(root + "/../outside"), "a relative step out of the root");
        assertNull(roots.confine(root.resolve("missing").toString()));
        assertNull(roots.confine(""));
        assertNull(roots.confine(Files.createFile(root.resolve("a.txt")).toString()), "not a folder");
        // A sibling whose name starts with the root's is not under it.
        assertNull(roots.confine(Files.createDirectory(tmp.resolve("root-other")).toString()));
    }

    @Test
    void aSymbolicLinkOutOfTheRootIsRefused(@TempDir final Path tmp) throws Exception {
        final Path root = Files.createDirectory(tmp.resolve("root"));
        final Path secrets = Files.createDirectory(tmp.resolve("secrets"));
        final Path link = Files.createSymbolicLink(root.resolve("link"), secrets);
        final LocalDirectoryRoots roots = new LocalDirectoryRoots(root.toString());

        assertNull(roots.confine(link.toString()));
        assertFalse(roots.contains(link.toRealPath()));
        assertTrue(roots.contains(root.toRealPath()));
    }

    @Test
    void withNoRootsConfiguredLocalDirectoryIsRefused(@TempDir final Path tmp) {
        assertNull(new LocalDirectoryRoots("").confine(tmp.toString()));
        assertNull(new LocalDirectoryRoots(null).confine(tmp.toString()));
    }
}
