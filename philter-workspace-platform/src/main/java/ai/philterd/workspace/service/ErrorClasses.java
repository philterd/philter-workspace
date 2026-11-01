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

/**
 * An exception's class and its causes' classes, for a log line. Never its message or stack trace: an exception from a
 * source, such as a database driver error or a JSON parse error, can quote the values it failed on (#77).
 */
public final class ErrorClasses {

    private static final int MAX_CAUSES = 5;

    private ErrorClasses() {
    }

    /** For example {@code org.springframework.dao.DataAccessException caused by java.sql.SQLException}. */
    public static String of(final Throwable e) {
        if (e == null) return "";
        final StringBuilder out = new StringBuilder(e.getClass().getName());
        Throwable cause = e.getCause();
        for (int i = 0; cause != null && cause != e && i < MAX_CAUSES; i++, cause = cause.getCause()) {
            out.append(" caused by ").append(cause.getClass().getName());
        }
        return out.toString();
    }
}
