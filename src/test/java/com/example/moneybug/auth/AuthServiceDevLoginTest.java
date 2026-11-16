package com.example.moneybug.auth;

import com.example.moneybug.auth.jwt.JwtProvider;
import com.example.moneybug.auth.kakao.KakaoClient;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.UserRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** DB·카카오 없이 개발용 우회 로그인(test_token) 분기만 검증하는 단위 테스트. */
class AuthServiceDevLoginTest {

    private final KakaoClient kakaoClient = mock(KakaoClient.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final JwtProvider jwtProvider = mock(JwtProvider.class);

    private AuthService service(boolean devLoginEnabled) {
        return service(devLoginEnabled, "");
    }

    private AuthService service(boolean devLoginEnabled, String testLoginToken) {
        when(userRepository.findByKakaoId(any())).thenReturn(java.util.Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(7);
            return u;
        });
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtProvider.createAccessToken(any(Long.class))).thenReturn("our-own-jwt");
        when(jwtProvider.accessTtlSeconds()).thenReturn(1800L);
        return new AuthService(kakaoClient, userRepository, refreshTokenRepository, jwtProvider, 1209600L,
                devLoginEnabled, testLoginToken);
    }

    @Test
    void testTokenSkipsKakaoAndIssuesOurJwt() {
        AuthService.Tokens tokens = service(true).loginWithKakaoAccessToken("test_token");

        assertEquals("our-own-jwt", tokens.accessToken());
        assertEquals("Bearer", tokens.tokenType());
        verifyNoInteractions(kakaoClient); // 카카오 서버를 전혀 호출하지 않는다
    }

    @Test
    void otherTokensAreStillVerifiedAgainstKakao() {
        doThrow(new AuthException("Invalid Kakao access token")).when(kakaoClient).verifyAppId("fake");

        assertThrows(AuthException.class, () -> service(true).loginWithKakaoAccessToken("fake"));
    }

    @Test
    void testTokenIsRejectedWhenSwitchedOff() {
        doThrow(new AuthException("Invalid Kakao access token")).when(kakaoClient).verifyAppId("test_token");

        assertThrows(AuthException.class, () -> service(false).loginWithKakaoAccessToken("test_token"));
    }

    @Test
    void secretTestTokenLogsInWithoutKakao() {
        String secret = "s3cret-test-login-0123";
        assertEquals("our-own-jwt", service(false, secret).loginWithKakaoAccessToken(secret).accessToken());
        verifyNoInteractions(kakaoClient);
    }

    @Test
    void shortOrWrongTestTokenIsVerifiedAgainstKakao() {
        doThrow(new AuthException("Invalid Kakao access token")).when(kakaoClient).verifyAppId(any());
        assertThrows(AuthException.class, () -> service(false, "short").loginWithKakaoAccessToken("short"));
        assertThrows(AuthException.class,
                () -> service(false, "s3cret-test-login-0123").loginWithKakaoAccessToken("s3cret-test-login-0124"));
    }
}
