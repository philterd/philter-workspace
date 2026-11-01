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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates an admin-supplied JDBC URL before it is saved as a data source. Defends against
 * driver features that execute code at connect time — most notoriously H2's {@code INIT=}
 * (runs arbitrary SQL when the connection opens) and PostgreSQL/MySQL deserialization
 * gadgets in {@code socketFactory=}/{@code autoDeserialize=true} — by enforcing three rules:
 *
 * <ol>
 *   <li><strong>Scheme allow-list:</strong> only the major server-style drivers are
 *       accepted. Embedded/file-backed engines (H2, Derby, HSQLDB, SQLite) are refused
 *       outright because their URL parameters can run SQL or load JARs at connect time.</li>
 *   <li><strong>Dangerous-parameter block-list:</strong> any of the substrings in
 *       {@link #DANGEROUS_PARAMS} appearing anywhere in the URL (case-insensitive) is
 *       refused. The check is intentionally crude because driver param parsers are
 *       inconsistent across versions; refusing the whole URL is safer than guessing.</li>
 *   <li><strong>Host allow-list:</strong> the URL must name one host, in the form its driver
 *       parses ({@link #hostAndRest}), with no property that changes the host, port, or service
 *       ({@link #HOST_KEYS}); that host must pass {@link DataSourceHostAllowList}, which refuses
 *       private addresses and, when configured, hosts not on the list (#73).</li>
 * </ol>
 *
 * <p>The validator does not connect to anything; it resolves the host to check its addresses. It runs when a data
 * source is saved or edited and again just before each connection.
 */
@Component
public class JdbcUrlValidator {

    /**
     * Drivers known to run code at connect time, exposing the JVM to RCE if the admin
     * (or finding-#2-style XSS in an admin's browser) supplies a hostile URL. Rejected
     * before the scheme allow-list even comes into play so the error message is specific.
     */
    public static final Set<String> EMBEDDED_DRIVERS = Set.of(
            "h2", "derby", "hsqldb", "sqlite");

    /**
     * Default scheme allow-list — the major server-style drivers. Override only if the
     * deployment deliberately needs a less common driver and has independently audited
     * its URL surface.
     */
    public static final Set<String> DEFAULT_ALLOWED_DRIVERS = Set.of(
            "postgresql", "mysql", "mariadb", "sqlserver", "oracle", "db2");

    /**
     * Substrings that, if present anywhere in the JDBC URL (case-insensitive), are
     * grounds for refusal. These are well-known driver-specific levers used in JDBC
     * RCE / exfiltration chains:
     *
     * <ul>
     *   <li>{@code init=} — H2 runs the SQL on connect.</li>
     *   <li>{@code socketfactory} — PostgreSQL JDBC pre-42.2.5 + ObjectInputStream gadgets.</li>
     *   <li>{@code autodeserialize} — MySQL Connector/J deserialization gadget chain.</li>
     *   <li>{@code allowloadlocalinfile} / {@code allowurlinlocalinfile} —
     *       MySQL {@code LOAD DATA LOCAL INFILE} primitive turned into arbitrary file read.</li>
     *   <li>{@code allowmultiqueries} — defensive: multi-statement support has fueled
     *       past second-order injection chains.</li>
     *   <li>{@code script=}, {@code runscript=} — H2 / HSQLDB equivalents of {@code init=}.</li>
     *   <li>{@code restoreFrom=} — Derby connect-time DB restore from filesystem path.</li>
     *   <li>{@code queryinterceptors} — MySQL Connector/J pre-8.0.20 loads an arbitrary
     *       class on connect; the canonical RCE gadget for that driver.</li>
     *   <li>{@code propertiestransform} — MySQL SnakeYAML deserialization gadget.</li>
     *   <li>{@code allowpublickeyretrieval} — MySQL forces server-pubkey fetch over
     *       plaintext, opening a MITM vector even when the server requires SHA256
     *       authentication.</li>
     *   <li>{@code currentschema} — PostgreSQL: small SQLi surface when the driver
     *       interpolates the value into a {@code SET search_path} on connect.</li>
     *   <li>{@code databasemetadatacachettl} — driver-side cache used in a few
     *       second-order injection chains; not needed for read-only ingest.</li>
     *   <li>{@code logger=}, {@code logfile=} — MySQL / older PostgreSQL drivers that
     *       could be pointed at a file the JVM has write access to (audit-log
     *       smearing / file-write primitive).</li>
     * </ul>
     */
    public static final List<String> DANGEROUS_PARAMS = List.of(
            "init=",
            "socketfactory",
            "autodeserialize",
            "allowloadlocalinfile",
            "allowurlinlocalinfile",
            "allowmultiqueries",
            "script=",
            "runscript=",
            "restorefrom=",
            "queryinterceptors",
            "statementinterceptors",
            "propertiestransform",
            "allowpublickeyretrieval",
            "currentschema",
            "databasemetadatacachettl",
            "logger=",
            "logfile=");

    /**
     * The URL forms accepted, one per driver, so the host checked is the host each driver connects to (#73):
     *
     * <ul>
     *   <li>{@code jdbc:postgresql://host[:port]/db[?props]}</li>
     *   <li>{@code jdbc:mysql://host[:port]/db[?props]} and {@code jdbc:mariadb://...}, with no sub-protocol
     *       (such as {@code loadbalance:}, {@code replication:}, or {@code srv:}, which reaches the hosts a DNS
     *       SRV record names)</li>
     *   <li>{@code jdbc:sqlserver://host[\instance][:port][;props]}</li>
     *   <li>{@code jdbc:db2://host[:port]/db[:props;]}</li>
     * </ul>
     *
     * Group 1 is the host, group 2 the rest of the URL.
     */
    private static final Pattern AUTHORITY_URL = Pattern.compile(
            "^jdbc:(?:postgresql|mysql|mariadb|sqlserver|db2)://(\\[[^]]*]|[^/?;:\\\\]*)(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** What may follow the host in {@link #AUTHORITY_URL}: a SQL Server instance, a port, then the rest. */
    private static final Pattern AFTER_HOST = Pattern.compile(
            "^(\\\\[A-Za-z0-9_$-]+)?(:\\d{1,5})?([/?;].*)?$", Pattern.DOTALL);

    /**
     * Oracle's thin driver, {@code jdbc:oracle:thin:@[tcp[s]://][//]host:port(:sid|/service)}. Connect descriptors
     * ({@code (DESCRIPTION=...)}), LDAP lookups, and TNS aliases resolved from {@code tnsnames.ora} name hosts the URL
     * does not show, so they are refused. Group 1 is the host, group 2 the rest.
     */
    private static final Pattern ORACLE_URL = Pattern.compile(
            "^jdbc:oracle:thin:@(?:tcps?://)?(?://)?(\\[[^]]*]|[^/:?(),]+)(:\\d{1,5}(?:[:/][^(),]*)?|/[^(),]*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** A host name or an IPv4 address, or an IPv6 address in brackets. */
    private static final Pattern VALID_HOST = Pattern.compile(
            "^(?:[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]+])$");

    /**
     * Connection properties a driver reads as the host, port, or database service to connect to, or as another server
     * to connect to instead, in any of the URL's property syntaxes (#73). Matched case-insensitively, with spaces
     * removed. They include PostgreSQL's {@code PGHOST}, {@code PGPORT}, {@code PGDBNAME}, and {@code service}; SQL
     * Server's {@code serverName} and its synonyms, {@code portNumber}, {@code instanceName}, and
     * {@code failoverPartner}; DB2's client-reroute alternates; MySQL and MariaDB's socket and pipe settings; and
     * Oracle's {@code tns_admin}.
     */
    static final Set<String> HOST_KEYS = Set.of(
            "pghost", "pgport", "pgdbname", "service", "host", "hostaddr", "port",
            "servername", "server", "address", "addr", "networkaddress", "portnumber", "instancename",
            "failoverpartner", "clientreroutealternateservername", "clientreroutealternateportnumber",
            "localsocket", "pipe", "namedpipepath", "protocol", "socket", "oracle.net.tns_admin", "tns_admin");

    /** A property key in a URL: after {@code ?}, {@code &}, {@code ;}, {@code :}, or {@code (}, before {@code =}. */
    private static final Pattern PROPERTY_KEY = Pattern.compile("[?&;:(]\\s*([A-Za-z][A-Za-z0-9_. ]*?)\\s*=");

    private static final Pattern DRIVER_PATTERN = Pattern.compile(
            "^jdbc:([a-zA-Z0-9]+)", Pattern.CASE_INSENSITIVE);

    private final Set<String> allowedDrivers;
    private final DataSourceHostAllowList hostAllowList;

    @Autowired
    public JdbcUrlValidator(final DataSourceHostAllowList hostAllowList) {
        this(hostAllowList, DEFAULT_ALLOWED_DRIVERS);
    }

    /** Test seam — lets a test override the driver allow-list. */
    public JdbcUrlValidator(final DataSourceHostAllowList hostAllowList,
                            final Set<String> allowedDrivers) {
        this.hostAllowList = hostAllowList;
        this.allowedDrivers = new LinkedHashSet<>(allowedDrivers);
    }

    /** Outcome of a validation. {@link #error()} is null when {@link #ok()} is true. */
    public record Result(boolean ok, String error) {
        public static Result accepted() { return new Result(true, null); }
        public static Result rejected(final String message) { return new Result(false, message); }
    }

    /**
     * Strip the {@code user[:pass]@} segment out of a JDBC URL so the stripped value
     * can be persisted in audit logs without leaking the password. Operators sometimes
     * paste credential-bearing URLs into the connection field; the validator refuses
     * them outright (see {@link #validate(String)}), but the rejection event is itself
     * audited and must not retain the secret. The transformation is also a no-op for
     * URLs that have no userinfo or are not parseable as URIs, so it is safe to apply
     * unconditionally before logging.
     *
     * <p>Examples:
     * <pre>
     *   jdbc:postgresql://user:secret@host:5432/db  →  jdbc:postgresql://host:5432/db
     *   jdbc:mysql://host:3306/db                   →  jdbc:mysql://host:3306/db
     *   jdbc:oracle:thin:@host:1521:sid              →  jdbc:oracle:thin:@host:1521:sid
     * </pre>
     */
    public static String stripUserInfo(final String jdbcUrl) {
        if (jdbcUrl == null) return null;
        final String trimmed = jdbcUrl.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("jdbc:")) return trimmed;
        final java.net.URI uri;
        try {
            uri = java.net.URI.create(trimmed.substring(5));
        } catch (Exception e) {
            return trimmed;
        }
        final String authority = uri.getRawAuthority();
        if (authority == null) return trimmed;       // opaque URI (e.g. oracle:thin:@...)
        final int at = authority.indexOf('@');
        if (at < 0) return trimmed;                  // no userinfo present
        // Rebuild from raw parts so the path/query/fragment formatting is preserved
        // verbatim (incl. characters like ';' that SQL Server uses for parameters,
        // and IPv6 brackets that URI.getHost() would otherwise drop).
        final String hostPort = authority.substring(at + 1);
        final StringBuilder sb = new StringBuilder("jdbc:")
                .append(uri.getScheme()).append("://").append(hostPort);
        if (uri.getRawPath() != null) sb.append(uri.getRawPath());
        if (uri.getRawQuery() != null) sb.append('?').append(uri.getRawQuery());
        if (uri.getRawFragment() != null) sb.append('#').append(uri.getRawFragment());
        return sb.toString();
    }

    /**
     * MySQL/MariaDB sub-protocols that take comma-separated host lists. URLs of
     * the form {@code jdbc:mysql:loadbalance://h1,h2/db} parse as opaque URIs
     * (no authority), so the plain comma-in-authority check below misses them
     * — they need an explicit sub-protocol refusal.
     */
    private static final Set<String> MYSQL_MULTI_HOST_SUBPROTOCOLS =
            Set.of("loadbalance", "replication", "sequential");

    /**
     * Detect any JDBC URL that carries more than one host. Three forms are
     * refused (each defeats the single-host allow-list in different ways):
     *
     * <ol>
     *     <li>Comma-in-authority: {@code jdbc:mysql://h1,h2/db},
     *         {@code jdbc:postgresql://h1,h2/db}. Caught by the authority check.</li>
     *     <li>Sub-protocol failover: {@code jdbc:mysql:loadbalance://h1,h2/db},
     *         {@code jdbc:mysql:replication://master,replica/db}. The URI parser
     *         sees these as opaque (because the colon after {@code mysql} is in
     *         scheme position), so the authority check misses them — caught by
     *         an explicit sub-protocol scan.</li>
     *     <li>MySQL key-value authority: {@code jdbc:mysql://address=(host=h1)(host=h2)/db}.
     *         Two {@code (host=…)} key tokens in the authority — caught by a
     *         simple substring search.</li>
     * </ol>
     */
    private static boolean hasMultiHostAuthority(final String jdbcUrl) {
        if (jdbcUrl == null) return false;
        final String trimmed = jdbcUrl.trim();
        final String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("jdbc:")) return false;

        // 2) Sub-protocol failover (jdbc:mysql:loadbalance://… etc).
        for (String sub : MYSQL_MULTI_HOST_SUBPROTOCOLS) {
            if (lower.startsWith("jdbc:mysql:" + sub + ":")
                    || lower.startsWith("jdbc:mariadb:" + sub + ":")) {
                return true;
            }
        }

        // 3) MySQL key-value authority. Two or more (host= occurrences means a
        //    multi-host directive even though there's no comma in the URI authority.
        if (lower.contains("address=(host=")) {
            final int first = lower.indexOf("(host=");
            final int second = lower.indexOf("(host=", first + 1);
            if (second >= 0) return true;
        }

        // 1) Comma-in-authority — the original round-1 check.
        try {
            final String authority =
                    java.net.URI.create(trimmed.substring(5)).getRawAuthority();
            return authority != null && authority.indexOf(',') >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasUserInfo(final String jdbcUrl) {
        // Detection mirrors stripUserInfo's authority-based logic — any URI whose
        // raw authority contains '@' carries userinfo.
        if (jdbcUrl == null) return false;
        final String trimmed = jdbcUrl.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("jdbc:")) return false;
        try {
            final String authority =
                    java.net.URI.create(trimmed.substring(5)).getRawAuthority();
            return authority != null && authority.indexOf('@') >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Inspect a JDBC URL and return whether it is acceptable. The checks run in order so
     * the error returned is the most specific one applicable.
     */
    public Result validate(final String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return Result.rejected("JDBC URL is required.");
        }
        final String url = jdbcUrl.trim();
        final String lower = url.toLowerCase(Locale.ROOT);

        if (!lower.startsWith("jdbc:")) {
            return Result.rejected("JDBC URL must start with \"jdbc:\".");
        }

        final Matcher driverMatcher = DRIVER_PATTERN.matcher(url);
        if (!driverMatcher.find()) {
            return Result.rejected("JDBC URL is missing a driver scheme (expected jdbc:<driver>:...).");
        }
        final String driver = driverMatcher.group(1).toLowerCase(Locale.ROOT);

        if (EMBEDDED_DRIVERS.contains(driver)) {
            return Result.rejected("Embedded JDBC driver \"" + driver
                    + "\" is not allowed — its URL parameters can run code at connect time.");
        }

        if (!allowedDrivers.contains(driver)) {
            return Result.rejected("JDBC driver \"" + driver + "\" is not on the allow-list. "
                    + "Permitted drivers: " + String.join(", ", allowedDrivers) + ".");
        }

        // Refuse URLs that carry embedded credentials (user[:pass]@host). Persisting
        // such a URL — even to mark it rejected — would leak the password into every
        // place the saved JDBC URL is logged. Admins should use the Username and
        // Password fields, which flow through SymmetricCipher.encryptField at rest.
        if (hasUserInfo(url)) {
            return Result.rejected("JDBC URL must not contain embedded credentials "
                    + "(\"user:password@host\"). Use the Username and Password fields instead.");
        }

        for (String bad : DANGEROUS_PARAMS) {
            if (lower.contains(bad)) {
                return Result.rejected("JDBC URL contains the disallowed parameter \"" + bad
                        + "\". Driver-specific parameters that execute code on connect are blocked.");
            }
        }

        // Reject comma-separated multi-host authorities (mysql://h1,h2/db and
        // postgresql://h1,h2/db). Both drivers natively support failover/load-
        // balancing by stringing hosts together; HOST_PATTERN below extracts
        // only the first, so the allow-list check would otherwise accept the
        // public host while the driver still connects to the second internal
        // one — same SSRF reach the allow-list was added to block. Admins
        // needing failover should configure it at the connection-pool layer.
        if (hasMultiHostAuthority(url)) {
            return Result.rejected("JDBC URL must not contain comma-separated host "
                    + "lists. Use a single host; configure failover at the connection-pool layer.");
        }

        // The host the driver will connect to, parsed the way each driver parses its URL (#73).
        final String[] hostAndRest = hostAndRest(url);
        if (hostAndRest == null) {
            return Result.rejected("JDBC URL could not be parsed. Use the form jdbc:<driver>://host[:port]/database"
                    + " (or jdbc:oracle:thin:@host:port/service) with a single host.");
        }
        final String host = hostAndRest[0];
        final String key = hostKey(hostAndRest[1]);
        if (key != null) {
            return Result.rejected("JDBC URL sets \"" + key + "\", which changes the host, port, or service the"
                    + " driver connects to. Put the host and port in the URL itself.");
        }

        // Host allow-list — private/loopback/link-local addresses are always blocked by
        // DataSourceHostAllowList; the allowlist additionally restricts public hosts when
        // configured. Apply unconditionally so JDBC URLs get the same private-range
        // protection as HTTP data-source endpoints.
        if (hostAllowList != null) {
            if (!hostAllowList.isHostAllowed(host)) {
                return Result.rejected("JDBC URL host \"" + host
                        + "\" is not permitted. Private/loopback/link-local addresses are blocked"
                        + " by default; to allow this host add it to"
                        + " philter-workspace.data-sources.allowed-hosts.");
            }
        }

        return Result.accepted();
    }

    /**
     * The host a JDBC URL connects to and the rest of the URL after it, or null when the URL is not in a form this
     * validator accepts (see {@link #AUTHORITY_URL} and {@link #ORACLE_URL}). An empty or malformed host is not
     * accepted: PostgreSQL, for one, connects to {@code localhost} when the URL names no host.
     */
    static String[] hostAndRest(final String jdbcUrl) {
        if (jdbcUrl == null) return null;
        final String url = jdbcUrl.trim();
        Matcher m = AUTHORITY_URL.matcher(url);
        if (m.matches()) {
            if (!AFTER_HOST.matcher(m.group(2)).matches()) return null;
        } else {
            m = ORACLE_URL.matcher(url);
            if (!m.matches()) return null;
        }
        final String host = m.group(1);
        if (!VALID_HOST.matcher(host).matches()) return null;
        return new String[] {host, m.group(2)};
    }

    /**
     * The first property in {@code rest} that changes where the driver connects ({@link #HOST_KEYS}), looked for both
     * as written and percent-decoded, or null.
     */
    static String hostKey(final String rest) {
        if (rest == null || rest.isEmpty()) return null;
        String decoded = rest;
        try {
            decoded = java.net.URLDecoder.decode(rest, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Not valid percent-encoding: scanned as written only.
        }
        for (String text : List.of(rest, decoded)) {
            final Matcher m = PROPERTY_KEY.matcher(text);
            while (m.find()) {
                final String key = m.group(1).replace(" ", "").toLowerCase(Locale.ROOT);
                if (HOST_KEYS.contains(key)) return m.group(1).trim();
            }
        }
        return null;
    }
}
