/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.ui;

/**
 * The name of a redacted download: the upload's name without its folder or extension, then {@code _redacted} and an
 * extension that matches what is downloaded (#66). {@code filing.pdf} downloaded as text is {@code filing_redacted.txt},
 * never {@code filing_redacted.pdf}.
 */
public final class RedactedFilenames {

    private RedactedFilenames() {
    }

    /**
     * {@code original}'s name with {@code _redacted.<extension>}, or {@code redacted.<extension>} without a name. Control
     * characters are removed.
     */
    public static String of(final String original, final String extension) {
        // Control characters dropped: a stored name comes from an upload or an import, and a CR or LF would break out
        // of the Content-Disposition header.
        String name = original == null ? "" : original.replaceAll("\\p{Cntrl}", "").strip();
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        final int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.isBlank() || name.chars().allMatch(c -> c == '.')
                ? "redacted." + extension
                : name + "_redacted." + extension;
    }
}
