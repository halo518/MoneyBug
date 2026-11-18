package com.example.moneybug.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class FileStorageSniffTest {

    @Test
    void detectsImages() {
        assertEquals("jpg", FileStorage.sniffExt(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0}));
        assertEquals("png", FileStorage.sniffExt(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}));
        assertEquals("webp", FileStorage.sniffExt("RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void heifNeedsImageBrand() {
        assertEquals("heic", FileStorage.sniffExt("\0\0\0\u0018ftypheic".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("heic", FileStorage.sniffExt("\0\0\0\u0018ftypmif1".getBytes(StandardCharsets.ISO_8859_1)));
        assertNull(FileStorage.sniffExt("\0\0\0\u0018ftypmp42".getBytes(StandardCharsets.ISO_8859_1)));
        assertNull(FileStorage.sniffExt("AAAAftypBBBB<html>".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void contentDecidesNotNameOrType(@TempDir Path dir) throws Exception {
        FileStorage fs = new FileStorage(dir.toString());
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        String p1 = fs.saveImage("c/1", new MockMultipartFile("photo", "photo.jfif", "application/octet-stream", jpg));
        String p2 = fs.saveImage("c/1", new MockMultipartFile("photo", "IMG_1.HEIF", "application/octet-stream", jpg));
        assertTrue(p1.endsWith(".jpg") && p2.endsWith(".jpg"));
        assertThrows(ApiException.class, () -> fs.saveImage("c/1",
                new MockMultipartFile("photo", "a.png", "image/png", "<html>".getBytes(StandardCharsets.UTF_8))));

        fs.delete(p1);
        assertFalse(Files.exists(dir.resolve(p1)));
        assertTrue(Files.exists(dir.resolve(p2)));
        fs.delete("../outside.png"); // 루트 밖은 무시
    }

    @Test
    void rejectsOthers() {
        assertNull(FileStorage.sniffExt("<html><script>".getBytes(StandardCharsets.UTF_8)));
        assertNull(FileStorage.sniffExt(new byte[0]));
    }
}
