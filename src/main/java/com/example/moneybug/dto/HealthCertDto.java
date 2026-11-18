package com.example.moneybug.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/** PUT /me/health-cert — multipart/form-data (설계서 §4.4). */
public class HealthCertDto {

    /** 알림 시점 최대 개수·최대 일수. 10 × "365," 도 users.cert_alert_days(50자) 안에 든다. */
    public static final int MAX_ALERTS = 10;
    public static final int MAX_ALERT_DAY = 365;
    /** 만료일 상한 (보건증 유효기간 1년, 여유를 둬 오늘부터 2년). */
    public static final int MAX_EXPIRY_YEARS = 2;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate expiry;

        // "30,14,7,0" 형태의 form 값이 리스트로 바인딩된다. 범위·개수는 서비스에서도 다시 본다(빈 항목 = null).
        @NotEmpty
        @Size(max = MAX_ALERTS, message = "알림 시점은 " + MAX_ALERTS + "개까지 고를 수 있어요")
        private List<@NotNull(message = "알림 시점에 빈 값이 있어요")
                @Min(value = 0, message = "알림 시점은 0 이상이어야 해요")
                @Max(value = MAX_ALERT_DAY, message = "알림 시점은 " + MAX_ALERT_DAY + "일 전까지예요") Integer> alertDays;

        // 필수 ("보건증 사진을 올려 주세요") — 비어 있는지는 서비스에서 검증
        private MultipartFile photo;
    }
}
