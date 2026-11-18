package com.example.moneybug.auth;

import com.example.moneybug.auth.jwt.JwtAuthenticationFilter;
import com.example.moneybug.auth.jwt.JwtProvider;
import com.example.moneybug.auth.kakao.KakaoClient;
import com.example.moneybug.dto.CommonDto.ErrorResponse;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** QA 2026-11-17 01 인증 발견 수정 확인 (DB·카카오 없이). */
class AuthFixesTest {

    private static final String SECRET = "0123456789012345678901234567890123456789";

    private final KakaoClient kakaoClient = mock(KakaoClient.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
    private final JwtProvider jwt = new JwtProvider(SECRET, 1800);
    private final AuthService service = new AuthService(kakaoClient, users, tokens, jwt,
            mock(PlatformTransactionManager.class), 1209600L, true, "");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refreshAlreadyConsumedConcurrentlyIs401() {
        when(tokens.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());
        AuthException e = assertThrows(AuthException.class, () -> service.refresh("r"));
        assertEquals(AuthException.INVALID_REFRESH_TOKEN, e.getCode());
    }

    @Test
    void refreshOfDeletedUserIs401AndDropsTheirTokens() {
        RefreshToken stored = new RefreshToken(42L, "h", Instant.now().plusSeconds(60));
        when(tokens.findByTokenHashForUpdate(any())).thenReturn(Optional.of(stored));
        when(users.existsById(42)).thenReturn(false);

        AuthException e = assertThrows(AuthException.class, () -> service.refresh("r"));
        assertEquals(AuthException.INVALID_REFRESH_TOKEN, e.getCode());
        verify(tokens).delete(stored);
        verify(tokens).deleteAllByUserId(42L);
        verify(tokens, never()).save(any());
    }

    @Test
    void refreshRotatesForExistingUser() {
        when(tokens.findByTokenHashForUpdate(any()))
                .thenReturn(Optional.of(new RefreshToken(42L, "h", Instant.now().plusSeconds(60))));
        when(users.existsById(42)).thenReturn(true);
        assertEquals(Optional.of(42L), jwt.parseUserId(service.refresh("r").accessToken()));
    }

    @Test
    void concurrentFirstLoginRetriesWithTheUserTheOtherRequestCreated() {
        User existing = new User();
        existing.setId(9);
        existing.setKakaoId("-1");
        when(users.findByKakaoId("-1")).thenReturn(Optional.empty(), Optional.of(existing));
        when(users.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry '-1'"))
                .thenAnswer(inv -> inv.getArgument(0));

        AuthService.Tokens t = service.loginWithKakaoAccessToken("test_token");
        assertEquals(Optional.of(9L), jwt.parseUserId(t.accessToken()));
    }

    @Test
    void nonAsciiKakaoTokenIs401NotHeaderError() {
        KakaoClient real = new KakaoClient("k", "", "http://localhost/cb", "app");
        for (String bad : new String[]{"한글토큰", "abc\r\nX-Evil: 1", "😀", "a\u0000b", "a b"}) {
            assertEquals(AuthException.INVALID_KAKAO_TOKEN,
                    assertThrows(AuthException.class, () -> real.fetchUser(bad)).getCode());
            assertEquals(AuthException.INVALID_KAKAO_TOKEN,
                    assertThrows(AuthException.class, () -> real.verifyAppId(bad)).getCode());
        }
    }

    @Test
    void authErrorBodyHasCodeAndKoreanMessage() {
        ResponseEntity<ErrorResponse> res = new AuthExceptionHandler().handle(AuthException.refreshTokenExpired());
        assertEquals(401, res.getStatusCode().value());
        assertEquals(AuthException.REFRESH_TOKEN_EXPIRED, res.getBody().code());
        assertTrue(res.getBody().message().contains("다시 로그인"));
    }

    @Test
    void jwtOfDeletedUserIsNotAuthenticated() throws Exception {
        when(users.existsById(5)).thenReturn(false);
        when(users.existsById(6)).thenReturn(true);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt, users);

        assertNull(authenticate(filter, 5L));
        assertEquals(6L, authenticate(filter, 6L));
    }

    private Object authenticate(JwtAuthenticationFilter filter, long userId) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + jwt.createAccessToken(userId));
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getPrincipal();
    }

    @Test
    void blankStateCookieAndParamIsInvalidState() {
        AuthController controller = new AuthController(service, kakaoClient, "https://f.x");
        ResponseEntity<Void> res = controller.kakaoCallback("x", "", null, "", null, new MockHttpServletResponse());
        assertEquals("https://f.x/#/auth?error=invalid_state", res.getHeaders().getFirst("Location"));
        verifyNoInteractions(kakaoClient);
    }
}
