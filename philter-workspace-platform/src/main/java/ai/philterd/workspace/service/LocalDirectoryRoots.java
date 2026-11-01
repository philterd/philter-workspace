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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The folders Local Directory data sources and destinations may use (#72), from
 * {@code philter-workspace.local-directory.allowed-roots}, a comma-separated list. With none configured, Local
 * Directory is refused entirely. Paths are compared by their real path, so a symbolic link cannot lead out of a root.
 */
@Component
public class LocalDirectoryRoots {

    /** Why a directory was refused, for the admin pages and job errors. */
    public static final String NOT_ALLOWED = "The directory is not under an allowed root. Local Directory sources and"
            + " destinations must be existing folders under philter-workspace.local-directory.allowed-roots.";

    private final List<String> configured;

    public LocalDirectoryRoots(@Value("${philter-workspace.local-directory.allowed-roots:}") final String csv) {
        this.configured = csv == null ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * The real path of {@code directory}, when it is an existing directory under an allowed root (or a root itself);
     * otherwise null.
     */
    public Path confine(final String directory) {
        if (directory == null || directory.isBlank()) return null;
        final Path real;
        try {
            real = Paths.get(directory.trim()).toRealPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return Files.isDirectory(real) && contains(real) ? real : null;
    }

    /** Whether {@code realPath}, already resolved with {@link Path#toRealPath}, is under an allowed root. */
    public boolean contains(final Path realPath) {
        return roots().stream().anyMatch(realPath::startsWith);
    }

    /** The configured roots that exist, by their real paths, read each time so a root created later counts. */
    private List<Path> roots() {
        return configured.stream().map(root -> {
            try {
                return Paths.get(root).toRealPath();
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }).filter(Objects::nonNull).toList();
    }
}
