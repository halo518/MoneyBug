package com.example.moneybug.controller;

import com.example.moneybug.dto.CommonDto.ErrorResponse;
import com.example.moneybug.service.ApiException;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.ConstraintViolation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 설계서 §1.5 에러 응답 { code, message, details } 로 통일. 인증 오류는 AuthExceptionHandler 가 처리한다.
 * 여기서 못 잡은 예외(500 등)는 config/ApiErrorAttributes 가 같은 모양으로 바꾼다.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(ErrorResponse.of(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ErrorResponse.of("FILE_TOO_LARGE", "파일이 너무 커요 (최대 10MB)"));
    }

    /** @Valid 실패 (JSON 본문과 form 바인딩 모두). details 키는 FE JSON 이름(@JsonProperty), 값은 한국어. */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleValidation(BindException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        Class<?> target = e.getTarget() == null ? null : e.getTarget().getClass();
        e.getFieldErrors().forEach(f -> details.putIfAbsent(jsonName(target, f.getField()),
                message(f.contains(ConstraintViolation.class) ? f.unwrap(ConstraintViolation.class) : null)));
        return validationFailed(details);
    }

    /** @RequestParam·@PathVariable 의 제약(@Min 등) 실패. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        e.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(err -> {
            ConstraintViolation<?> cv = null;
            try {
                cv = r.unwrap(err, ConstraintViolation.class);
            } catch (IllegalArgumentException ignored) {
                // 제약 위반이 아닌 오류
            }
            details.putIfAbsent(String.valueOf(r.getMethodParameter().getParameterName()), message(cv));
        }));
        return validationFailed(details);
    }

    /** 깨진 JSON, 타입·enum 변환 실패, 정수 칸의 소수. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        Map<String, Object> details = null;
        if (e.getCause() instanceof JacksonException je && !je.getPath().isEmpty()) {
            details = Map.of(jsonPath(je), "형식이 올바르지 않아요");
        }
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_BODY",
                "요청 내용을 읽을 수 없어요. 값의 형식을 확인해 주세요", details));
    }

    /** 경로·쿼리 값 변환 실패 (예: /stores/abc, since=abc). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_PARAM",
                "요청 값의 형식이 올바르지 않아요", Map.of(e.getName(), "형식이 올바르지 않아요")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return missing(e.getParameterName());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
        return missing(e.getRequestPartName());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorResponse.of("UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식이에요"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        HttpHeaders headers = new HttpHeaders();
        if (e.getSupportedHttpMethods() != null) headers.setAllow(e.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers)
                .body(ErrorResponse.of("METHOD_NOT_ALLOWED", "지원하지 않는 요청이에요"));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoHandler(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("NOT_FOUND", "요청한 주소를 찾을 수 없어요"));
    }

    /**
     * 동시 요청 충돌(중복 키, 낙관적 잠금, 데드락·잠금 대기 초과) → 409. 다시 하면 되는 상황이다.
     * DB 가 값 자체를 거절한 경우(너무 긴 문자열, 범위 밖 날짜)는 400.
     * 로그에는 예외 종류와 SQL 상태만 남긴다(메시지에 사용자 값이 들어 있다).
     */
    @ExceptionHandler({DataIntegrityViolationException.class, ConcurrencyFailureException.class,
            jakarta.persistence.OptimisticLockException.class, jakarta.persistence.PessimisticLockException.class,
            jakarta.persistence.LockTimeoutException.class, org.hibernate.StaleStateException.class})
    public ResponseEntity<ErrorResponse> handleConflict(Exception e) {
        SQLException sql = sqlCause(e);
        String state = sql == null ? null : sql.getSQLState() + "/" + sql.getErrorCode();
        if (isBadValue(sql)) {
            log.warn("DB 가 값을 거절함: {} ({})", e.getClass().getSimpleName(), state);
            return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_VALUE",
                    "입력 값이 너무 길거나 범위를 벗어났어요"));
        }
        log.warn("동시 요청 충돌: {} ({})", e.getClass().getSimpleName(), state);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of("CONFLICT",
                "다른 요청과 겹쳐서 처리하지 못했어요. 다시 시도해 주세요"));
    }

    // ---- helpers ----

    private static ResponseEntity<ErrorResponse> validationFailed(Map<String, Object> details) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", "요청 값이 올바르지 않아요", details));
    }

    private static ResponseEntity<ErrorResponse> missing(String name) {
        return ResponseEntity.badRequest().body(new ErrorResponse("MISSING_PARAM",
                "필요한 값이 빠졌어요", Map.of(name, "값을 입력해 주세요")));
    }

    /** MySQL: 22xxx(값이 너무 김·범위 밖·잘못된 날짜), 1048(NULL 불가), 1366(잘못된 문자열 값). */
    static boolean isBadValue(SQLException sql) {
        if (sql == null) return false;
        String st = sql.getSQLState();
        return (st != null && st.startsWith("22")) || sql.getErrorCode() == 1048 || sql.getErrorCode() == 1366;
    }

    private static SQLException sqlCause(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof SQLException s) return s;
        }
        return null;
    }

    private static String jsonPath(JacksonException e) {
        StringBuilder b = new StringBuilder();
        for (JacksonException.Reference r : e.getPath()) {
            if (r.getPropertyName() != null) {
                if (!b.isEmpty()) b.append('.');
                b.append(r.getPropertyName());
            } else if (r.getIndex() >= 0) {
                b.append('[').append(r.getIndex()).append(']');
            }
        }
        return b.toString();
    }

    /** 맨 앞 필드 이름을 DTO 의 @JsonProperty 이름으로 (예: hourlyWage → wage). 중첩 경로의 뒷부분은 그대로. */
    static String jsonName(Class<?> type, String path) {
        int cut = indexOfAny(path);
        String head = cut < 0 ? path : path.substring(0, cut);
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(head);
                JsonProperty jp = f.getAnnotation(JsonProperty.class);
                if (jp != null && !jp.value().isEmpty()) return jp.value() + (cut < 0 ? "" : path.substring(cut));
                break;
            } catch (NoSuchFieldException ignored) {
                // 부모 클래스에서 찾는다.
            }
        }
        return path;
    }

    private static int indexOfAny(String path) {
        int dot = path.indexOf('.'), br = path.indexOf('[');
        if (dot < 0) return br;
        if (br < 0) return dot;
        return Math.min(dot, br);
    }

    private static final Set<String> REQUIRED = Set.of("NotNull", "NotBlank", "NotEmpty");

    /** DTO 가 message= 를 주었으면 그대로, 기본 메시지(영어)면 한국어로. 변환 실패는 예외 문구 대신 짧은 안내. */
    static String message(ConstraintViolation<?> cv) {
        if (cv == null) return "형식이 올바르지 않아요";
        String template = cv.getMessageTemplate();
        if (!template.startsWith("{jakarta.validation.") && !template.startsWith("{org.hibernate.validator.")) {
            return cv.getMessage();
        }
        Map<String, Object> a = cv.getConstraintDescriptor().getAttributes();
        String name = cv.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        if (REQUIRED.contains(name)) return "값을 입력해 주세요";
        String unit = cv.getInvalidValue() instanceof CharSequence ? "자" : "개";
        return switch (name) {
            case "Size", "Length" -> {
                Object min = a.get("min"), max = a.get("max");
                if (Integer.valueOf(0).equals(min)) yield max + unit + " 이하로 입력해 주세요";
                if (Integer.valueOf(Integer.MAX_VALUE).equals(max)) yield min + unit + " 이상 입력해 주세요";
                yield min + "~" + max + unit + "로 입력해 주세요";
            }
            case "Min", "DecimalMin" -> a.get("value") + " 이상이어야 해요";
            case "Max", "DecimalMax" -> a.get("value") + " 이하여야 해요";
            case "Positive" -> "0보다 커야 해요";
            case "PositiveOrZero" -> "0 이상이어야 해요";
            case "Pattern", "Email" -> "형식이 올바르지 않아요";
            default -> "값이 올바르지 않아요";
        };
    }
}
