package com.doctrail.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeDocMigrationTest {
    @Test
    void v3AddsNullableStorageMetadataWithoutChangingV1() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V3__knowledge_doc_storage_metadata.sql");
        String sql = Files.readString(migration, StandardCharsets.UTF_8).toLowerCase();

        assertTrue(sql.contains("alter table knowledge_doc"));
        assertTrue(sql.contains("add column file_size bigint null"));
        assertTrue(sql.contains("add column content_type varchar(128) null"));
        assertTrue(sql.contains("after content_hash"));
        assertTrue(sql.contains("after file_size"));
    }
}
