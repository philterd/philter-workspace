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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain HTTP to another machine is warned about (#85). */
class PlainHttpTest {

    @Test
    void plainHttpToAnotherMachineIsFlagged() {
        for (String url : List.of("http://192.168.1.20:11434", "http://10.0.0.5:11434", "http://ollama.example.com:11434",
                "HTTP://gpu-box.internal:11434", "http://[fd00::5]:11434")) {
            assertTrue(PlainHttp.toAnotherHost(url), url);
        }
    }

    @Test
    void thisMachineTheComposeNetworkAndHttpsAreNot() {
        for (String url : List.of("http://localhost:11434", "http://127.0.0.1:11434", "http://127.10.0.1:11434",
                "http://[::1]:11434", "http://host.docker.internal:11434", "http://ollama:11434",
                "http://app.localhost:11434", "https://ollama.example.com:11434", "https://10.0.0.5:11434", "",
                "not a url")) {
            assertFalse(PlainHttp.toAnotherHost(url), url);
        }
        assertFalse(PlainHttp.toAnotherHost(null));
    }
}
