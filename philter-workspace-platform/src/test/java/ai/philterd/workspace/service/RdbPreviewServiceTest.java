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

import ai.philterd.workspace.model.RelationalDbDataSource;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A preview re-validates the stored JDBC URL before connecting (#73). */
class RdbPreviewServiceTest {

    private final SymmetricCipher cipher = new SymmetricCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final RdbPreviewService service =
            new RdbPreviewService(cipher, new JdbcUrlValidator(new DataSourceHostAllowList("")));

    @Test
    void aStoredUrlThatNoLongerPassesIsRefusedBeforeConnecting() {
        for (String url : List.of("jdbc:postgresql://10.0.0.5:5432/db",
                "jdbc:postgresql://db.example.com/db?PGHOST=127.0.0.1")) {
            final RelationalDbDataSource source = new RelationalDbDataSource();
            source.setEncryptedJdbcUrl(cipher.encrypt(url));
            source.setSqlQuery("SELECT text, filename FROM documents");

            final RdbPreviewService.Result result = service.preview(source);

            assertFalse(result.ok(), url);
            assertTrue(result.error().contains("not permitted") || result.error().contains("changes the host"),
                    result.error());
        }
    }
}
