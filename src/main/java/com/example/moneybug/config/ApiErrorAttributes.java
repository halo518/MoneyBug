package com.example.moneybug.config;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ApiExceptionHandler 가 못 잡은 오류(/error 로 가는 것: 처리 안 된 예외 500, 보안 403 등)도
 * FE ApiClient 계약 {code, message, details} 로 내보낸다. 예외 메시지는 응답에 넣지 않는다
 * (스택 트레이스는 서블릿 컨테이너가 로그에 남긴다).
 * 처리 안 된 예외를 @ExceptionHandler(Exception.class) 로 잡지 않는 이유: 다른 @RestControllerAdvice
 * (AuthExceptionHandler)보다 먼저 고르면 그쪽 예외까지 500 이 된다.
 */
@Component
public class ApiErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Object s = super.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults()).get("status");
        int status = s instanceof Integer i ? i : 500;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code(status));
        body.put("message", message(status));
        body.put("details", null);
        return body;
    }

    static String code(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 409 -> "CONFLICT";
            case 413 -> "FILE_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "HTTP_" + status;
        };
    }

    static String message(int status) {
        return switch (status) {
            case 401 -> "다시 로그인해 주세요";
            case 403 -> "접근 권한이 없어요";
            case 404 -> "요청한 주소를 찾을 수 없어요";
            case 405 -> "지원하지 않는 요청이에요";
            case 409 -> "다른 요청과 겹쳐서 처리하지 못했어요. 다시 시도해 주세요";
            case 413 -> "파일이 너무 커요 (최대 10MB)";
            case 415 -> "지원하지 않는 요청 형식이에요";
            default -> status >= 500 ? "서버에서 문제가 생겼어요. 잠시 뒤 다시 시도해 주세요" : "요청을 처리하지 못했어요";
        };
    }
}
