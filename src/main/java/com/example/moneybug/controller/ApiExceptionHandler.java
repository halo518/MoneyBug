package com.example.moneybug.controller;

import com.example.moneybug.dto.CommonDto.ErrorResponse;
import com.example.moneybug.service.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** 설계서 §1.5 에러 응답 { code, message, details } 로 통일. 인증 오류는 AuthExceptionHandler 가 처리한다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(ErrorResponse.of(e.getCode(), e.getMessage()));
    }

    /** @Valid 실패 (JSON 본문과 form 바인딩 모두). */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleValidation(BindException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        e.getFieldErrors().forEach(f -> details.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", "요청 값이 올바르지 않아요", details));
    }
}
