package com.example.moneybug.dto;

import java.util.List;
import java.util.Map;

/** 공통 응답 형식 (설계서 §1.4 목록, §1.5 에러). */
public class CommonDto {

    /** { "items": [...], "nextCursor": "opaque-or-null" } */
    public record ListResponse<T>(List<T> items, String nextCursor) {
        public static <T> ListResponse<T> of(List<T> items) {
            return new ListResponse<>(items, null);
        }
    }

    /** { "code": "WAGE_BELOW_MINIMUM", "message": "...", "details": {...} } */
    public record ErrorResponse(String code, String message, Map<String, Object> details) {
        public static ErrorResponse of(String code, String message) {
            return new ErrorResponse(code, message, null);
        }
    }

    /** 미읽음 알림 수 등 단일 숫자 응답 */
    public record CountResponse(long count) {}
}
