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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A redacted download's name matches what is downloaded (#66). */
class RedactedFilenamesTest {

    @Test
    void theExtensionIsTheDownloadsNotTheUploads() {
        assertEquals("letter_redacted.txt", RedactedFilenames.of("letter.txt", "txt"));
        assertEquals("filing_redacted.txt", RedactedFilenames.of("filing.pdf", "txt"));
        assertEquals("filing_redacted.txt", RedactedFilenames.of("filing.PDF", "txt"));
        assertEquals("filing_redacted.pdf", RedactedFilenames.of("filing.pdf", "pdf"));
        assertEquals("my.notes_redacted.txt", RedactedFilenames.of("my.notes.txt", "txt"), "only the last extension");
        assertEquals("README_redacted.txt", RedactedFilenames.of("README", "txt"));
        assertEquals("claim_redacted.txt", RedactedFilenames.of("uploads/2026/claim.txt", "txt"));
        assertEquals(".env_redacted.txt", RedactedFilenames.of(".env", "txt"), "a leading dot is part of the name");
        assertEquals("aX-Injected: 1_redacted.txt", RedactedFilenames.of("a\r\nX-Injected: 1.txt", "txt"),
                "no CR or LF can reach the Content-Disposition header");
        assertEquals("tabbed_redacted.txt", RedactedFilenames.of("tab\tbed.txt", "txt"));
        for (String none : new String[] {null, "", "  ", "...", "folder/"}) {
            assertEquals("redacted.txt", RedactedFilenames.of(none, "txt"), String.valueOf(none));
        }
    }
}
