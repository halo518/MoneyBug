package com.example.moneybug.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/** PUT /me/health-cert — multipart/form-data (설계서 §4.4). */
public class HealthCertDto {

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate expiry;

        // "30,14,7,0" 형태의 form 값이 리스트로 바인딩된다.
        @NotEmpty
        private List<Integer> alertDays;

        // 필수 ("보건증 사진을 올려 주세요") — 비어 있는지는 서비스에서 검증
        private MultipartFile photo;
    }
}
