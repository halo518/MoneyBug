package com.example.moneybug.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

public class AiDto {

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        @NotBlank(message = "질문을 입력해 주세요")
        // 글자 수(1000자) 검사는 AiLaborService 가 FE 처럼 글자(그래핌) 단위로 한다. 여기는 터무니없는 크기만 막는다.
        @Size(max = 20000, message = "질문은 1000자까지 쓸 수 있어요")
        private String question;
    }

    @Getter
    @AllArgsConstructor
    public static class Response {
        private String answer;
        /** 근거 조항 (예: "근로기준법 제55조"). 없으면 빈 배열. */
        private List<String> citations;
        private LocalDateTime createdAt;
    }

    /** POST /ai/labor-audit. aiUsed = summary·action 을 AI 가 다듬었는지(false 면 규칙 기본 문장). */
    public record AuditResponse(String summary, boolean aiUsed, LocalDateTime createdAt, List<Finding> findings) {}

    /** severity: high | medium | low. staffIds 는 FeIdJsonModule 이 문자열로 내보낸다. */
    public record Finding(String code, String severity, String title, String detail, String action,
                          List<Integer> staffIds, List<String> citations) {
        public Finding withText(String detail, String action) {
            return new Finding(code, severity, title, detail, action, staffIds, citations);
        }
    }
}
