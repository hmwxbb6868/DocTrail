package com.doctrail.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeDocEntityTest {
    @Test
    void mapsStorageMetadataAndLifecycleFields() {
        KnowledgeDocEntity entity = new KnowledgeDocEntity();
        entity.setId(7L);
        entity.setStorageKey("2026/10/document.md");
        entity.setContentHash("9e8b62f81ea5c66fa06ee53da032751386b37702153070c0e14dd1d316282fa7");
        entity.setFileSize(128L);
        entity.setContentType("text/markdown");
        entity.setStatus("PENDING");
        entity.setErrorMessage(null);
        entity.setCreateTime(OffsetDateTime.parse("2026-10-05T00:00:00Z"));
        entity.setUpdateTime(entity.getCreateTime());

        assertEquals("knowledge_doc", KnowledgeDocEntity.class.getAnnotation(TableName.class).value());
        assertEquals("storage_key", field("storageKey").value());
        assertEquals("content_hash", field("contentHash").value());
        assertEquals("file_size", field("fileSize").value());
        assertEquals("content_type", field("contentType").value());
        assertEquals("2026/10/document.md", entity.getStorageKey());
        assertEquals(128L, entity.getFileSize());
        assertEquals("text/markdown", entity.getContentType());
        assertEquals("PENDING", entity.getStatus());
    }

    private static TableField field(String name) {
        try {
            return KnowledgeDocEntity.class.getDeclaredField(name).getAnnotation(TableField.class);
        } catch (NoSuchFieldException exception) {
            throw new AssertionError(exception);
        }
    }
}
