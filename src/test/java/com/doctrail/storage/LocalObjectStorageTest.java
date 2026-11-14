package com.doctrail.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalObjectStorageTest {
    @Test
    void writesAndDeletesOnlyWithinPrefixedRoot(@TempDir Path tempDir) throws Exception {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir, "doctrail");
        storage.put("2026/09/document.txt", "hello".getBytes(StandardCharsets.UTF_8), "text/plain");

        Path stored = tempDir.resolve("doctrail/2026/09/document.txt");
        assertTrue(Files.exists(stored));
        assertTrue(storage.exists("2026/09/document.txt"));

        storage.delete("2026/09/document.txt");
        assertFalse(Files.exists(stored));
    }

    @Test
    void rejectsTraversalBeforeTouchingDisk(@TempDir Path tempDir) {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir, "doctrail");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> storage.put("../outside.txt", new byte[]{1}, "text/plain"));
    }

    @Test
    void rejectsSymlinkEscapeFromUploadRoot(@TempDir Path tempDir) throws Exception {
        Path uploadRoot = tempDir.resolve("upload-root");
        Path prefixRoot = uploadRoot.resolve("doctrail");
        Files.createDirectories(prefixRoot);
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path link = prefixRoot.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "symbolic links are unavailable in this environment");
        } catch (java.io.IOException exception) {
            assumeTrue(false, "symbolic links require elevated Windows privileges");
        }
        assumeTrue(Files.isSymbolicLink(link), "symbolic link creation was not preserved in this environment");

        LocalObjectStorage storage = new LocalObjectStorage(uploadRoot, "doctrail");
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("escape/file.txt", new byte[]{1}, "text/plain"));
        assertTrue(Files.notExists(outside.resolve("file.txt")));
    }
}
