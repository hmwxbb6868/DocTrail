package com.doctrail.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadValidatorTest {
    @Test
    void validatesTextUsingServerDetectedTypeAndSha256() {
        byte[] bytes = "# hello\n".getBytes(StandardCharsets.UTF_8);
        UploadMetadata metadata = new UploadValidator(1024).validate("hello.md", "application/octet-stream", bytes);

        assertEquals("text/markdown", metadata.contentType());
        assertEquals(bytes.length, metadata.size());
        assertEquals("9e8b62f81ea5c66fa06ee53da032751386b37702153070c0e14dd1d316282fa7", metadata.contentHash());
        assertEquals("pending/" + metadata.contentHash() + ".md", metadata.storageKey());
    }

    @Test
    void rejectsTooLargeAndUnsupportedFiles() {
        assertThrows(UploadTooLargeException.class,
                () -> new UploadValidator(2).validate("hello.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)));
        assertThrows(UnsupportedUploadTypeException.class,
                () -> new UploadValidator(1024).validate("hello.exe", "application/octet-stream", new byte[]{1, 2, 3}));
        assertThrows(UnsupportedUploadTypeException.class,
                () -> new UploadValidator(1024).validate("legacy.doc", "application/msword", new byte[]{1, 2, 3}));
    }
}
