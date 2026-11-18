package com.example.moneybug.auth;

/** 401 인증 오류. AuthExceptionHandler 가 {code, message} 로 돌려준다(message 는 화면에 보이는 한국어). */
public class AuthException extends RuntimeException {

    public static final String INVALID_KAKAO_TOKEN = "INVALID_KAKAO_TOKEN";
    public static final String KAKAO_LOGIN_FAILED = "KAKAO_LOGIN_FAILED";
    public static final String INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN";
    public static final String REFRESH_TOKEN_EXPIRED = "REFRESH_TOKEN_EXPIRED";

    private final String code;

    public AuthException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AuthException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    static AuthException invalidRefreshToken() {
        return new AuthException(INVALID_REFRESH_TOKEN, "로그인 정보가 올바르지 않아요. 다시 로그인해 주세요");
    }

    static AuthException refreshTokenExpired() {
        return new AuthException(REFRESH_TOKEN_EXPIRED, "로그인이 만료됐어요. 다시 로그인해 주세요");
    }
}
