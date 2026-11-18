package com.example.moneybug.auth;

import com.example.moneybug.dto.CommonDto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 다른 API 와 같은 {code, message, details} 모양. FE api_client 는 refresh 의 401 을 "갱신 실패"로 본다. */
@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handle(AuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(e.getCode(), e.getMessage()));
    }
}
