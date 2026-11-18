package com.example.moneybug.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

/**
 * 업로드 파일 저장 (설계서 §10-9). 지금은 로컬 디렉터리(app.upload-dir, 기본 ./uploads)에 저장하고
 * 루트 기준 상대 경로를 돌려준다. S3 등으로 바꿀 때 이 클래스만 교체하면 된다.
 * 접근 권한(본인·소속 사장님만)은 파일을 내려주는 API를 만들 때 처리해야 한다.
 */
@Component
@lombok.extern.slf4j.Slf4j
public class FileStorage {

    /** HEIC/HEIF 이미지의 ftyp 브랜드 (mp4·avif 등 다른 ISO 미디어는 거부). */
    private static final java.util.Set<String> HEIF_BRANDS = java.util.Set.of(
            "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1");

    private final Path root;

    public FileStorage(@Value("${app.upload-dir:uploads}") String uploadDir) {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    /**
     * 이미지 파일만 허용한다. folder 는 서버가 정한 상대 경로(사용자 입력 아님).
     * 클라이언트가 보낸 Content-Type·확장자는 보지 않는다(앱은 octet-stream, 웹은 .jfif·.heif 이름도 보낸다).
     */
    public String saveImage(String folder, MultipartFile file) {
        try {
            // 클라이언트가 보낸 타입·확장자는 믿지 않고 파일 앞 바이트로 실제 형식을 정한다.
            byte[] head;
            try (java.io.InputStream in = file.getInputStream()) {
                head = in.readNBytes(12);
            }
            String ext = sniffExt(head);
            if (ext == null) {
                throw ApiException.badRequest("INVALID_FILE_TYPE", "이미지 파일만 올릴 수 있어요");
            }
            Path dir = root.resolve(folder).normalize();
            if (!dir.startsWith(root)) {
                throw ApiException.badRequest("INVALID_PATH", "저장 경로가 올바르지 않아요");
            }
            Files.createDirectories(dir);
            String name = UUID.randomUUID() + "." + ext;
            file.transferTo(dir.resolve(name));
            return folder + "/" + name;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_SAVE_FAILED", "파일을 저장하지 못했어요");
        }
    }

    /** 매직 바이트로 jpg/png/webp/gif/heic 판별. 아니면 null. heic 는 ftyp 브랜드까지 본다. */
    static String sniffExt(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "gif";
        if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p'
                && HEIF_BRANDS.contains(new String(b, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1))) {
            return "heic";
        }
        return null;
    }

    /** 저장된 파일 삭제. 없거나 루트 밖이면 아무것도 안 한다. 실패는 로그만 남긴다(요청은 이미 끝남). */
    public void delete(String path) {
        if (path == null || path.isBlank()) return;
        try {
            Path file = root.resolve(path).normalize();
            if (file.startsWith(root)) Files.deleteIfExists(file);
        } catch (IOException | java.nio.file.InvalidPathException e) {
            log.warn("파일 삭제 실패: {}", e.toString());
        }
    }

    /** 저장된 파일 바이트. 루트 밖이거나 없으면 404. path 는 saveImage 가 돌려준 값. */
    public byte[] read(String path) {
        try {
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                throw ApiException.notFound("파일을 찾을 수 없어요");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_READ_FAILED", "파일을 읽지 못했어요");
        }
    }

    /** 확장자로 Content-Type 추정. 모르면 jpeg. */
    public static String contentType(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".heic")) return "image/heic";
        return "image/jpeg";
    }
}
