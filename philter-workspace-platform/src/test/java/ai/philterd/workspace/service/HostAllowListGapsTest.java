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

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gaps in the data-source host allow-list (#73). */
class HostAllowListGapsTest {

    private final DataSourceHostAllowList allowList = new DataSourceHostAllowList("");
    private final JdbcUrlValidator validator = new JdbcUrlValidator(allowList);

    // ---- private ranges ----

    @Test
    void carrierGradeNatAndOtherReservedIpv4RangesArePrivate() throws Exception {
        for (String ip : List.of("100.64.0.1", "100.100.100.200", "100.127.255.254", "0.1.2.3", "192.0.0.192",
                "198.18.0.1", "198.19.255.255", "224.0.0.1", "240.0.0.1", "255.255.255.255",
                "10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.1.1")) {
            assertTrue(DataSourceHostAllowList.isPrivateAddress(InetAddress.getByName(ip)), ip);
        }
        for (String ip : List.of("100.63.255.255", "100.128.0.1", "8.8.8.8", "198.20.0.1", "192.0.1.1")) {
            assertFalse(DataSourceHostAllowList.isPrivateAddress(InetAddress.getByName(ip)), ip);
        }
    }

    @Test
    void ipv6ThatCarriesAPrivateIpv4AddressIsPrivate() throws Exception {
        for (String ip : List.of("64:ff9b::a9fe:a9fe", "64:ff9b::7f00:1", "64:ff9b::6464:64c8", "64:ff9b:1::1",
                "2002:a9fe:a9fe::1", "2002:0a00:0001::", "::a9fe:a9fe", "::ffff:169.254.169.254",
                "2001:0:a9fe:a9fe::1", "2001:0:808:808::f5ff:fffe", "fd00:ec2::254", "fe80::1", "::1", "::",
                "ff02::1")) {
            assertTrue(DataSourceHostAllowList.isPrivateAddress(InetAddress.getByName(ip)), ip);
        }
        // The same forms carrying a public address are not.
        for (String ip : List.of("64:ff9b::808:808", "2002:808:808::1", "2001:0:808:808::f7f7:f7f7",
                "2606:4700::1111")) {
            assertFalse(DataSourceHostAllowList.isPrivateAddress(InetAddress.getByName(ip)), ip);
        }
    }

    @Test
    void numericFormsAreJudgedByTheAddressTheyConnectTo() {
        assertFalse(allowList.isAllowed("http://2130706433:9200"));
        assertFalse(allowList.isAllowed("http://127.1:9200"));
        assertFalse(allowList.isAllowed("http://[64:ff9b::a9fe:a9fe]:80"));
        assertFalse(allowList.isAllowed("http://100.100.100.200/latest/meta-data"));
        assertTrue(allowList.isAllowed("http://8.8.8.8:9200"));
    }

    @Test
    void aHostIsRefusedWhenAnyOfItsAddressesIsPrivate() {
        // localhost resolves to 127.0.0.1 and often ::1 too; each is checked.
        assertFalse(allowList.isHostAllowed("localhost"));
        assertFalse(allowList.isHostAllowed("[::1]"));
    }

    // ---- JDBC URLs ----

    @Test
    void propertiesThatChangeTheHostPortOrServiceAreRefused() {
        for (String url : List.of(
                "jdbc:postgresql://db.example.com:5432/app?PGHOST=10.0.0.5",
                "jdbc:postgresql://db.example.com/app?sslmode=require&PGPORT=6379",
                "jdbc:postgresql://db.example.com/app?PGDBNAME=other",
                "jdbc:postgresql://db.example.com/app?service=internal",
                "jdbc:postgresql://db.example.com/app?%50GHOST=10.0.0.5",
                "jdbc:sqlserver://db.example.com:1433;serverName=10.0.0.5;databaseName=app",
                "jdbc:sqlserver://db.example.com;failoverPartner=10.0.0.5",
                "jdbc:sqlserver://db.example.com;instanceName=x",
                "jdbc:sqlserver://db.example.com;network address=10.0.0.5",
                "jdbc:db2://db.example.com:50000/APP:clientRerouteAlternateServerName=10.0.0.5;",
                "jdbc:mariadb://db.example.com/app?localSocket=/var/run/mysqld/mysqld.sock",
                "jdbc:mysql://db.example.com/app?protocol=pipe",
                "jdbc:oracle:thin:@//db.example.com:1521/svc?TNS_ADMIN=/tmp")) {
            final JdbcUrlValidator.Result result = validator.validate(url);
            assertFalse(result.ok(), url);
            assertTrue(result.error().contains("changes the host"), url + ": " + result.error());
        }
    }

    @Test
    void urlFormsThatHideTheHostAreRefused() {
        for (String url : List.of(
                "jdbc:postgresql:///app",                                  // no host: PostgreSQL uses localhost
                "jdbc:postgresql:app",
                "jdbc:mysql://address=(host=10.0.0.5)(port=3306)/app",     // one host, in key-value form
                "jdbc:mysql:srv://db.example.com/app",                     // hosts from a DNS SRV record
                "jdbc:mariadb:failover://db.example.com/app",
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=10.0.0.5)(PORT=1521)))",
                "jdbc:oracle:thin:@ldap://ldap.example.com/svc",
                "jdbc:oracle:thin:@prod",                                  // a TNS alias from tnsnames.ora
                "jdbc:oracle:thin:@db.example.com:1521,10.0.0.5:1521/svc",
                "jdbc:sqlserver://;serverName=10.0.0.5")) {
            assertFalse(validator.validate(url).ok(), url);
        }
    }

    @Test
    void ordinaryUrlsForEachDriverAreAccepted() {
        for (String url : List.of(
                "jdbc:postgresql://8.8.8.8:5432/app?sslmode=verify-full&ApplicationName=workspace",
                "jdbc:mysql://8.8.8.8:3306/app?useSSL=true",
                "jdbc:mariadb://8.8.8.8/app",
                "jdbc:sqlserver://8.8.8.8\\SQLEXPRESS:1433;databaseName=app;encrypt=true",
                "jdbc:db2://8.8.8.8:50000/APP:currentFunctionPath=X;",
                "jdbc:oracle:thin:@8.8.8.8:1521:orcl",
                "jdbc:oracle:thin:@//8.8.8.8:1521/svc",
                "jdbc:oracle:thin:@tcps://8.8.8.8:2484/svc")) {
            final JdbcUrlValidator.Result result = validator.validate(url);
            assertTrue(result.ok(), url + ": " + result.error());
        }
    }

    @Test
    void theHostCheckedIsTheHostEachDriverUses() {
        assertNotNull(JdbcUrlValidator.hostAndRest("jdbc:postgresql://[2606:4700::1111]:5432/app"));
        assertFalse(validator.validate("jdbc:postgresql://[::1]:5432/app").ok());
        assertFalse(validator.validate("jdbc:sqlserver://100.100.100.200\\x;databaseName=app").ok());
        assertFalse(validator.validate("jdbc:oracle:thin:@//169.254.169.254:1521/svc").ok());
    }
}
