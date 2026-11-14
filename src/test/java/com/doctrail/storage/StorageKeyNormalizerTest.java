package com.doctrail.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageKeyNormalizerTest {
    @Test
    void normalizesRepeatedSeparatorsAndPrefix() {
        assertEquals("2026/09/document.pdf", StorageKeyNormalizer.normalizeRelative(" 2026//09/document.pdf "));
        assertEquals("doctrail/2026/09/document.pdf", StorageKeyNormalizer.joinPrefix("/doctrail//", "2026//09/document.pdf"));
    }

    @Test
    void rejectsAbsoluteAndTraversalKeys() {
        assertThrows(IllegalArgumentException.class, () -> StorageKeyNormalizer.normalizeRelative("/data/file.pdf"));
        assertThrows(IllegalArgumentException.class, () -> StorageKeyNormalizer.normalizeRelative("C:/uploads/file.pdf"));
        assertThrows(IllegalArgumentException.class, () -> StorageKeyNormalizer.normalizeRelative("a/../file.pdf"));
        assertThrows(IllegalArgumentException.class, () -> StorageKeyNormalizer.normalizeRelative("a/./file.pdf"));
        assertThrows(IllegalArgumentException.class, () -> StorageKeyNormalizer.joinPrefix("doctrail/../escape", "file.pdf"));
        assertThrows(IllegalArgumentException.class, () -> StorageKeyNormalizer.joinPrefix("C:/uploads", "file.pdf"));
    }
}
