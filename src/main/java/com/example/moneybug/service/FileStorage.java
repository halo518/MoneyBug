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
public class FileStorage {

    private final Path root;

    public FileStorage(@Value("${app.upload-dir:uploads}") String uploadDir) {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    /** 이미지 파일만 허용한다. folder 는 서버가 정한 상대 경로(사용자 입력 아님). */
    public String saveImage(String folder, MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "이미지 파일만 올릴 수 있어요");
        }
        String ext = "jpg";
        String original = file.getOriginalFilename();
        if (original != null && original.contains(".")) {
            String candidate = original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (candidate.matches("[a-z0-9]{1,5}")) {
                ext = candidate;
            }
        }
        try {
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
}
