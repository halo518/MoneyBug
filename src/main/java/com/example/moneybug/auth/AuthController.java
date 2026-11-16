package com.example.moneybug.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.auth.AuthService.Tokens;
import com.example.moneybug.auth.kakao.KakaoClient;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;

@Tag(name = "인증", description = "카카오 로그인 / 토큰 갱신 / 로그아웃 (인증 불필요)")
@SecurityRequirements
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record KakaoTokenRequest(@NotBlank String accessToken) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    private static final String STATE_COOKIE = "kakao_state";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthService authService;
    private final KakaoClient kakaoClient;

    public AuthController(AuthService authService, KakaoClient kakaoClient) {
        this.authService = authService;
        this.kakaoClient = kakaoClient;
    }

    /** Mobile app: send the access token obtained from the Kakao SDK. */
    @Operation(summary = "모바일 카카오 로그인", description = "카카오 SDK 에서 받은 accessToken 으로 로그인하고 자체 JWT(access/refresh)를 발급받아요.")
    @PostMapping("/kakao/token")
    public Tokens kakaoToken(@Valid @RequestBody KakaoTokenRequest request) {
        return authService.loginWithKakaoAccessToken(request.accessToken());
    }

    /** Web: redirect the browser to Kakao. A random state is kept in an HttpOnly cookie to prevent login CSRF. */
    @Operation(summary = "웹 카카오 로그인 시작", description = "카카오 인증 페이지로 302 리다이렉트해요. state 는 HttpOnly 쿠키에 저장돼요.")
    @GetMapping("/kakao/login")
    public ResponseEntity<Void> kakaoLogin(HttpServletResponse response) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Cookie cookie = new Cookie(STATE_COOKIE, state);
        cookie.setHttpOnly(true);
        cookie.setPath("/api/auth/kakao");
        cookie.setMaxAge(300);
        response.addCookie(cookie);

        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, kakaoClient.authorizeUrl(state))
                .build();
    }

    /** Web: Kakao redirects back here with the authorization code. */
    @Operation(summary = "웹 카카오 로그인 콜백", description = "카카오가 인가 코드와 함께 돌려보내는 주소예요. state 를 검증한 뒤 토큰을 발급해요.")
    @GetMapping("/kakao/callback")
    public Tokens kakaoCallback(@RequestParam String code, @RequestParam String state,
                                @CookieValue(name = STATE_COOKIE, required = false) String expectedState,
                                HttpServletResponse response) {
        if (expectedState == null || !expectedState.equals(state)) {
            throw new AuthException("Invalid OAuth state");
        }
        Cookie clear = new Cookie(STATE_COOKIE, "");
        clear.setPath("/api/auth/kakao");
        clear.setMaxAge(0);
        response.addCookie(clear);
        return authService.loginWithKakaoCode(code);
    }

    @Operation(summary = "토큰 갱신", description = "refreshToken 을 소모하고 새 access/refresh 토큰 쌍을 발급해요(회전).")
    @PostMapping("/refresh")
    public Tokens refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @Operation(summary = "로그아웃", description = "전달한 refreshToken 을 폐기해요.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
