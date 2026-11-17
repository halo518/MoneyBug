package com.example.moneybug.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FileStorageSniffTest {

    @Test
    void detectsImages() {
        assertEquals("jpg", FileStorage.sniffExt(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0}));
        assertEquals("png", FileStorage.sniffExt(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}));
        assertEquals("webp", FileStorage.sniffExt("RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void rejectsOthers() {
        assertNull(FileStorage.sniffExt("<html><script>".getBytes(StandardCharsets.UTF_8)));
        assertNull(FileStorage.sniffExt(new byte[0]));
    }
}
