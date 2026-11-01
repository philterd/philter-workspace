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

import ai.philterd.workspace.model.GeneralSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Host allow-list for data-source URLs (OpenSearch / Elasticsearch test endpoints,
 * ingest workers, and Ollama test-connection calls).
 *
 * <p><strong>Default-deny for private ranges.</strong> Private and reserved addresses
 * ({@link #isPrivateAddress(InetAddress)}: RFC-1918, loopback, link-local, carrier-grade
 * NAT, IPv6 unique-local, and IPv6 forms that carry any of them) are blocked by default.
 * Every address a host resolves to is checked, and when no allow-list is configured,
 * every private-range host is refused.
 * This closes the admin-triggered SSRF path against internal infrastructure (cloud
 * metadata endpoints, MongoDB / Redis on loopback, intranet HTTP services on RFC-1918,
 * etc.) without requiring any operator configuration. To admit a private host the
 * operator genuinely needs to reach (a legitimate internal OpenSearch, for example),
 * list it explicitly in the allow-list:
 *
 * <pre>
 *   philter-workspace.data-sources.allowed-hosts=opensearch.internal,192.168.1.100
 * </pre>
 *
 * <p>That admits both {@code opensearch.internal} and the private literal
 * {@code 192.168.1.100}. The default-deny is therefore not absolute — patterns override
 * it for the hosts they name.
 *
 * <p><strong>Allow-list behavior.</strong> The format is a comma-separated list of host
 * patterns. Each pattern is either:
 *
 * <ul>
 *   <li>An <strong>exact hostname</strong> like {@code opensearch.internal}, or
 *   <li>A <strong>leading-wildcard</strong> like {@code *.search.example.com},
 *       which matches one-or-more left-side labels (so {@code a.search.example.com}
 *       and {@code search.example.com} both match, but {@code search.example.org} does not).
 * </ul>
 *
 * <p>Combined behavior:
 *
 * <ul>
 *   <li>Allow-list unset, public host → accepted.</li>
 *   <li>Allow-list unset, private-range host → rejected.</li>
 *   <li>Allow-list set, host matches a pattern (public or private) → accepted.</li>
 *   <li>Allow-list set, host doesn't match → rejected.</li>
 * </ul>
 *
 * <p><strong>Public-host master switch.</strong> An admin can relax the pattern check
 * for <em>public</em> hosts from Admin → Security ({@link
 * GeneralSettings#isHostAllowListEnabled()}). When disabled, any well-formed public
 * host is accepted regardless of the configured patterns. The toggle <strong>does not
 * disable the private-range backstop</strong>: loopback, link-local (including the
 * cloud-metadata-service literal 169.254.169.254), RFC-1918, IPv6 ULA, and any-local
 * addresses are always refused unless their host appears explicitly in the
 * {@code philter-workspace.data-sources.allowed-hosts} list. The escape hatch widens
 * acceptance for the public Internet; it cannot be used to open a door to internal
 * services like cloud metadata, MongoDB on loopback, or intranet HTTP endpoints.
 */
@Component
public class DataSourceHostAllowList {

    private static final Logger log = LoggerFactory.getLogger(DataSourceHostAllowList.class);

    private final List<String> patterns;
    /**
     * Lazy reference to the settings service so a runtime toggle of the allow-list
     * is honored on the next {@link #isAllowed(String)} call without restarting.
     * {@code ObjectProvider} avoids the eager-construction cycle Spring would
     * otherwise build between this component and {@code GeneralSettingsService}.
     */
    private final ObjectProvider<GeneralSettingsService> generalSettingsServiceProvider;

    @Autowired
    public DataSourceHostAllowList(
            @Value("${philter-workspace.data-sources.allowed-hosts:}") final String csv,
            final ObjectProvider<GeneralSettingsService> generalSettingsServiceProvider) {
        this(csv, generalSettingsServiceProvider, true);
    }

    /**
     * Test-only constructor that takes just the CSV. Skips the runtime toggle
     * lookup (the allow-list always behaves as-if-enabled), which is the right
     * shape for the existing unit tests that exercise pattern matching and
     * private-range rules in isolation.
     */
    public DataSourceHostAllowList(final String csv) {
        this(csv, null, false);
    }

    private DataSourceHostAllowList(final String csv,
                                    final ObjectProvider<GeneralSettingsService> generalSettingsServiceProvider,
                                    @SuppressWarnings("unused") final boolean unusedDistinguisher) {
        this.generalSettingsServiceProvider = generalSettingsServiceProvider;
        if (csv == null || csv.isBlank()) {
            this.patterns = List.of();
        } else {
            this.patterns = Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .toList();
            if (!patterns.isEmpty()) {
                log.info("Data-source host allow-list active with {} pattern(s): {}",
                        patterns.size(), patterns);
            }
        }
    }

    /** {@code true} if the allow-list is configured (i.e. a non-empty pattern list was supplied). */
    public boolean isEnforced() {
        return !patterns.isEmpty();
    }

    /**
     * Returns {@code true} if the URL's host is permitted.
     *
     * <p>Private/loopback/link-local addresses are always blocked unless the host is
     * explicitly listed in the configured allow-list. Public addresses are blocked only
     * when an allow-list is configured and the host doesn't match any pattern.
     *
     * <p>A {@code null}, blank, or unparseable URL (one without a recognisable host
     * component) is always rejected.
     */
    public boolean isAllowed(final String url) {
        if (url == null || url.isBlank()) return false;
        final String host;
        try {
            final URI uri = URI.create(url.trim());
            host = uri.getHost();
        } catch (Exception e) {
            return false;
        }
        return isHostAllowed(host);
    }

    /**
     * {@link #isAllowed(String)} for a bare host name or address, such as one taken from a JDBC URL. Every address the
     * host resolves to is checked, and one private address is enough to refuse it (#73). The JVM caches the answer
     * (30 seconds by default), so a connection made straight after this check uses the addresses it checked.
     */
    public boolean isHostAllowed(final String host) {
        if (host == null || host.isBlank()) return false;
        final String h = host.toLowerCase(Locale.ROOT).replaceAll("^\\[|]$", "");

        // Private/loopback/link-local addresses are blocked at *all* times — the admin
        // toggle never widens this rule. Only an explicit allow-list entry can admit an
        // internal host. This closes the cloud-metadata / loopback / RFC-1918 SSRF path
        // even when an operator has turned the public-host check off.
        if (resolve(h).stream().anyMatch(DataSourceHostAllowList::isPrivateAddress)) {
            return !patterns.isEmpty() && matchesPatterns(h);
        }

        // Public host with the admin toggle off: accept regardless of pattern config.
        // This is the documented escape hatch — it widens *public* acceptance only.
        if (!isEnabledByAdmin()) {
            return true;
        }

        // Public host with the admin toggle on: allowed when no allow-list is
        // configured; otherwise must match a pattern.
        return patterns.isEmpty() || matchesPatterns(h);
    }

    /**
     * Reads the admin toggle from settings. Defaults to {@code true} when no
     * settings row exists yet (fresh install) or the service isn't available
     * (test contexts that wire only this component); the safest default for an
     * SSRF defence is "on".
     */
    private boolean isEnabledByAdmin() {
        final GeneralSettingsService svc = generalSettingsServiceProvider == null
                ? null : generalSettingsServiceProvider.getIfAvailable();
        if (svc == null) return true;
        try {
            return svc.load().isHostAllowListEnabled();
        } catch (RuntimeException e) {
            // If the settings store is briefly unreachable, fall back to the
            // safe default rather than leaving the allow-list unenforced.
            log.debug("Could not read host-allow-list toggle, defaulting to enabled: {}",
                    e.getMessage());
            return true;
        }
    }

    /** Refuses a connection to a host this list does not allow, checked immediately before connecting (#73). */
    public static final class HostNotAllowedException extends IllegalStateException {
        public HostNotAllowedException() {
            super("Endpoint host is not permitted by the data-source host allow-list.");
        }
    }

    /** Throws {@link HostNotAllowedException} unless {@link #isAllowed(String)}. Call it just before connecting. */
    public void requireAllowed(final String url) {
        if (!isAllowed(url)) {
            throw new HostNotAllowedException();
        }
    }

    /** Useful in error messages so admins know what to add to their allow-list. */
    public List<String> patterns() {
        return patterns;
    }

    /** Every address {@code host} resolves to; none when it does not resolve, since nothing can connect to it. */
    private static List<InetAddress> resolve(final String host) {
        try {
            return List.of(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            return List.of();
        }
    }

    /**
     * Whether an address is private, reserved, or otherwise not a public Internet address. Covers:
     *
     * <ul>
     *   <li><strong>IPv4:</strong> 0.0.0.0/8 (this network), 10.0.0.0/8, 100.64.0.0/10 (carrier-grade NAT, which
     *       holds some clouds' metadata addresses, such as 100.100.100.200), 127.0.0.0/8, 169.254.0.0/16
     *       (link-local, incl. the cloud-metadata 169.254.169.254), 172.16.0.0/12, 192.0.0.0/24 (IETF protocol
     *       assignments, incl. 192.0.0.192), 192.168.0.0/16, 198.18.0.0/15 (benchmarking), and 224.0.0.0/3
     *       (multicast, reserved, and broadcast).</li>
     *   <li><strong>IPv6:</strong> :: and ::1, fe80::/10 (link-local), fec0::/10 (site-local), fc00::/7 (unique
     *       local, incl. AWS's fd00:ec2::254), ff00::/8 (multicast), and 64:ff9b:1::/48 (local-use NAT64).</li>
     *   <li><strong>IPv6 that carries an IPv4 address</strong> (#73), judged by that address: IPv4-mapped
     *       ::ffff:0:0/96 (which Java turns into IPv4), IPv4-compatible ::/96, NAT64 64:ff9b::/96, 6to4 2002::/16,
     *       and Teredo 2001::/32 (its server and its client, which Teredo stores inverted).</li>
     * </ul>
     *
     * <p>Numeric literals are parsed by the JVM exactly as a connection would parse them, so forms such as
     * {@code 127.1} or {@code 2130706433} are judged by the address they connect to.
     */
    static boolean isPrivateAddress(final InetAddress addr) {
        final byte[] b = addr.getAddress();
        if (b.length == 4) {
            return isPrivateV4(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff);
        }
        final int b0 = b[0] & 0xff;
        final int b1 = b[1] & 0xff;
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || (b0 & 0xfe) == 0xfc || b0 == 0xff) {
            return true;
        }
        // IPv4-compatible ::a.b.c.d (the first 96 bits zero).
        if (allZero(b, 0, 12)) {
            return isPrivateV4(b[12] & 0xff, b[13] & 0xff, b[14] & 0xff);
        }
        // NAT64: the well-known prefix 64:ff9b::/96 carries an IPv4 address in its last 32 bits; the local-use
        // prefix 64:ff9b:1::/48 maps to anything the network chooses.
        if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
            if ((b[4] & 0xff) == 0 && (b[5] & 0xff) == 1) return true;
            return !allZero(b, 4, 12) || isPrivateV4(b[12] & 0xff, b[13] & 0xff, b[14] & 0xff);
        }
        // 6to4: 2002:aabb:ccdd::/48 carries a.b.c.d in bits 16 to 47.
        if (b0 == 0x20 && b1 == 0x02) {
            return isPrivateV4(b[2] & 0xff, b[3] & 0xff, b[4] & 0xff);
        }
        // Teredo 2001:0000::/32: the server's IPv4 address in bits 32 to 63, the client's inverted in the last 32.
        if (b0 == 0x20 && b1 == 0x01 && b[2] == 0 && b[3] == 0) {
            return isPrivateV4(b[4] & 0xff, b[5] & 0xff, b[6] & 0xff)
                    || isPrivateV4(~b[12] & 0xff, ~b[13] & 0xff, ~b[14] & 0xff);
        }
        return false;
    }

    private static boolean isPrivateV4(final int a, final int b, final int c) {
        return a == 0 || a == 10 || a == 127
                || (a == 100 && (b & 0xc0) == 64)
                || (a == 169 && b == 254)
                || (a == 172 && (b & 0xf0) == 16)
                || (a == 192 && b == 0 && c == 0)
                || (a == 192 && b == 168)
                || (a == 198 && (b & 0xfe) == 18)
                || a >= 224;
    }

    private static boolean allZero(final byte[] b, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    private boolean matchesPatterns(final String h) {
        for (String spec : patterns) {
            if (spec.startsWith("*.")) {
                // *.foo.com matches anything ending in .foo.com plus the bare apex foo.com.
                final String suffix = spec.substring(1); // ".foo.com"
                final String apex = spec.substring(2);   // "foo.com"
                if (h.endsWith(suffix) || h.equals(apex)) return true;
            } else if (spec.equals(h)) {
                return true;
            }
        }
        return false;
    }
}
