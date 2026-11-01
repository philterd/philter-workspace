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

import java.net.URI;
import java.util.Locale;

/**
 * Whether a URL sends traffic unencrypted to another machine (#85): plain {@code http} to a host that is neither this
 * machine nor a container on the same compose network. Document text sent to such a host crosses a network in the
 * clear.
 */
public final class PlainHttp {

    private PlainHttp() {
    }

    /** True for an {@code http} URL whose host is not local. A URL that cannot be parsed is not judged. */
    public static boolean toAnotherHost(final String url) {
        if (url == null || url.isBlank()) return false;
        final URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
        return !local(uri.getHost());
    }

    /**
     * This machine (loopback names and addresses, and {@code host.docker.internal}, Docker's name for the machine a
     * container runs on), or a single-label name such as {@code ollama}, as a compose service is reached.
     */
    static boolean local(final String rawHost) {
        final String host = rawHost.toLowerCase(Locale.ROOT).replaceAll("^\\[|]$", "");
        return host.equals("localhost") || host.endsWith(".localhost")
                || host.equals("host.docker.internal")
                || host.startsWith("127.") && host.matches("[0-9.]+")
                || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1")
                || !host.contains(".") && !host.contains(":");
    }
}
