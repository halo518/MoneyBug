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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    /** 앱(iOS/Android) 로그인 표시. 콜백이 웹 대신 앱 스킴으로 돌려보낸다. */
    private static final String APP_COOKIE = "kakao_app";
    static final String APP_AUTH = "donbug://auth";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthService authService;
    private final KakaoClient kakaoClient;

    private final String frontendUrl;
    private final boolean secureCookies;

    public AuthController(AuthService authService, KakaoClient kakaoClient,
                          @Value("${app.frontend-url}") String frontendUrl) {
        this.authService = authService;
        this.kakaoClient = kakaoClient;
        this.frontendUrl = stripTrailingSlash(frontendUrl);
        this.secureCookies = this.frontendUrl.startsWith("https://");
    }

    /** Mobile app: send the access token obtained from the Kakao SDK. */
    @Operation(summary = "모바일 카카오 로그인", description = "카카오 SDK 에서 받은 accessToken 으로 로그인하고 자체 JWT(access/refresh)를 발급받아요.")
    @PostMapping("/kakao/token")
    public Tokens kakaoToken(@Valid @RequestBody KakaoTokenRequest request) {
        return authService.loginWithKakaoAccessToken(request.accessToken());
    }

    /** Web: redirect the browser to Kakao. A random state is kept in an HttpOnly cookie to prevent login CSRF. */
    @Operation(summary = "웹 카카오 로그인 시작", description = "카카오 인증 페이지로 302 리다이렉트해요. state 는 HttpOnly 쿠키에 저장돼요. "
            + "app=true 면 콜백이 웹 대신 앱 스킴 donbug://auth?access=..&refresh=.. 으로 돌려보내요.")
    @GetMapping("/kakao/login")
    public ResponseEntity<Void> kakaoLogin(@RequestParam(defaultValue = "false") boolean app,
                                           HttpServletResponse response) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        response.addCookie(authCookie(STATE_COOKIE, state, 300));
        response.addCookie(authCookie(APP_COOKIE, app ? "1" : "", app ? 300 : 0));

        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, kakaoClient.authorizeUrl(state))
                .build();
    }

    /** Web: Kakao redirects back here. Always answers 302 to the web app (tokens or an error code in the hash). */
    @Operation(summary = "웹 카카오 로그인 콜백", description = "카카오가 인가 코드와 함께 돌려보내는 주소예요. state 를 검증한 뒤 토큰을 발급하고, "
            + "웹앱으로 302 리다이렉트해요: 성공 시 {frontendUrl}/#/auth?access=..&refresh=.. , 실패 시 {frontendUrl}/#/auth?error=<코드>.")
    @GetMapping("/kakao/callback")
    public ResponseEntity<Void> kakaoCallback(@RequestParam(required = false) String code,
                                              @RequestParam(required = false) String state,
                                              @RequestParam(required = false) String error,
                                              @CookieValue(name = STATE_COOKIE, required = false) String expectedState,
                                              @CookieValue(name = APP_COOKIE, required = false) String appFlag,
                                              HttpServletResponse response) {
        response.addCookie(authCookie(STATE_COOKIE, "", 0));
        response.addCookie(authCookie(APP_COOKIE, "", 0));
        String base = "1".equals(appFlag) ? APP_AUTH : frontendUrl + "/#/auth";

        String location;
        if (error != null) {
            location = errorRedirect(base, "kakao_denied");
        } else if (expectedState == null || !expectedState.equals(state)) {
            location = errorRedirect(base, "invalid_state");
        } else if (code == null || code.isBlank()) {
            location = errorRedirect(base, "missing_code");
        } else {
            try {
                location = successRedirect(base, authService.loginWithKakaoCode(code));
            } catch (RuntimeException e) {
                location = errorRedirect(base, "login_failed");
            }
        }
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, location).build();
    }

    /** [base]: 웹 {frontendUrl}/#/auth 또는 앱 donbug://auth */
    static String successRedirect(String base, Tokens t) {
        return base + "?access=" + enc(t.accessToken()) + "&refresh=" + enc(t.refreshToken());
    }

    static String errorRedirect(String base, String code) {
        return base + "?error=" + enc(code);
    }

    private Cookie authCookie(String name, String value, int maxAge) {
        Cookie c = new Cookie(name, value);
        c.setHttpOnly(true);
        c.setSecure(secureCookies);
        c.setAttribute("SameSite", "Lax");
        c.setPath("/api/auth/kakao");
        c.setMaxAge(maxAge);
        return c;
    }

    static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? stripTrailingSlash(url.substring(0, url.length() - 1)) : url;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
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
