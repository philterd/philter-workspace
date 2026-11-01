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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Ingest failures are logged by class, never by message (#77). */
class ErrorClassesTest {

    @Test
    void namesTheClassesAndNeverTheMessages() {
        final Exception e = new IllegalStateException("could not import 'Jane Roe'",
                new java.sql.SQLException("Duplicate entry 'jroe@example.com'"));

        final String logged = ErrorClasses.of(e);

        assertEquals("java.lang.IllegalStateException caused by java.sql.SQLException", logged);
        assertFalse(logged.contains("Jane Roe") || logged.contains("jroe@example.com"));
        assertEquals("", ErrorClasses.of(null));
    }

    @Test
    void stopsOnALongOrCircularChain() {
        final Exception a = new Exception("a");
        final Exception b = new Exception("b", a);
        a.initCause(b);
        assertEquals(2, ErrorClasses.of(a).split(" caused by ").length, "a cycle back to the first ends it");

        Exception deep = new Exception("0");
        for (int i = 1; i < 10; i++) {
            deep = new Exception(String.valueOf(i), deep);
        }
        assertEquals(6, ErrorClasses.of(deep).split(" caused by ").length, "the class and at most five causes");
    }
}
