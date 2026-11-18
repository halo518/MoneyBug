package com.example.moneybug.auth;

import com.example.moneybug.auth.jwt.JwtProvider;
import com.example.moneybug.auth.kakao.KakaoClient;
import com.example.moneybug.auth.kakao.KakaoClient.KakaoUser;
import com.example.moneybug.entity.User; // [수정] user 패키지 삭제 -> entity 패키지로 변경
import com.example.moneybug.repository.UserRepository; // [수정] user 패키지 삭제 -> repository 패키지로 변경
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthService {

    public record Tokens(String accessToken, String refreshToken, String tokenType, long expiresIn) {}

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    // 개발용 우회 로그인 (auth.dev-login.enabled=true 일 때만). 실제 카카오 id 는 양수라서 음수 id 로 충돌을 피한다.
    private static final String DEV_TOKEN = "test_token";
    private static final long DEV_KAKAO_ID = -1L;
    private static final String DEV_NAME = "테스트유저";
    private static final String DEV_EMAIL = "test@moneybug.local";

    // 운영 테스트 계정 로그인: TEST_LOGIN_TOKEN(비밀, 16자 이상)과 정확히 같은 토큰이면 테스트 알바생으로 로그인한다. 비우면 꺼짐.
    private static final int TEST_TOKEN_MIN_LENGTH = 16;
    private static final long TEST_KAKAO_ID = -2L;
    private static final String TEST_NAME = "테스트알바";
    private static final String TEST_EMAIL = "test-employee@moneybug.local";

    private final KakaoClient kakaoClient;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final Duration refreshTtl;
    private final boolean devLoginEnabled;
    private final byte[] testLoginToken;
    private final TransactionTemplate tx;

    public AuthService(KakaoClient kakaoClient, UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository, JwtProvider jwtProvider,
                       PlatformTransactionManager txManager,
                       @Value("${jwt.refresh-ttl-seconds}") long refreshTtlSeconds,
                       @Value("${auth.dev-login.enabled:false}") boolean devLoginEnabled,
                       @Value("${auth.test-login.token:}") String testLoginToken) {
        this.kakaoClient = kakaoClient;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProvider = jwtProvider;
        this.tx = new TransactionTemplate(txManager);
        this.refreshTtl = Duration.ofSeconds(refreshTtlSeconds);
        this.devLoginEnabled = devLoginEnabled;
        this.testLoginToken = testLoginToken == null || testLoginToken.length() < TEST_TOKEN_MIN_LENGTH
                ? new byte[0] : testLoginToken.getBytes(StandardCharsets.UTF_8);
        if (devLoginEnabled) {
            log.warn("[DEV LOGIN] auth.dev-login.enabled=true: the Kakao token \"{}\" skips Kakao verification "
                    + "and logs in as a test user. NEVER enable this outside a local environment.", DEV_TOKEN);
        }
    }

    /** Mobile: the app already holds a Kakao access token from the Kakao SDK. Kakao calls run outside a transaction. */
    public Tokens loginWithKakaoAccessToken(String kakaoAccessToken) {
        // 개발용 우회: 토큰이 "test_token" 과 정확히 일치하면 카카오 검증 없이 테스트 사용자로 로그인한다.
        // 기본은 꺼짐. 로컬에서만 DEV_LOGIN_ENABLED=true 로 켠다.
        if (devLoginEnabled && DEV_TOKEN.equals(kakaoAccessToken)) {
            return login(new KakaoUser(DEV_KAKAO_ID, DEV_NAME, DEV_EMAIL, null));
        }
        if (testLoginToken.length > 0 && kakaoAccessToken != null && MessageDigest.isEqual(testLoginToken,
                kakaoAccessToken.getBytes(StandardCharsets.UTF_8))) {
            return login(new KakaoUser(TEST_KAKAO_ID, TEST_NAME, TEST_EMAIL, null));
        }
        kakaoClient.verifyAppId(kakaoAccessToken);
        return login(kakaoClient.fetchUser(kakaoAccessToken));
    }

    /** Web: the browser came back from Kakao with an authorization code. */
    public Tokens loginWithKakaoCode(String code) {
        String kakaoAccessToken = kakaoClient.exchangeCode(code);
        return login(kakaoClient.fetchUser(kakaoAccessToken));
    }

    /** Rotates the refresh token: the presented one is consumed and a new pair is issued. */
    @Transactional(noRollbackFor = AuthException.class)
    public Tokens refresh(String refreshToken) {
        // 잠그고 읽는다: 같은 토큰이 동시에 오면 진 요청은 지워진 뒤를 읽어 이미 쓴 토큰처럼 401 을 받는다.
        RefreshToken stored = refreshTokenRepository.findByTokenHashForUpdate(hash(refreshToken))
                .orElseThrow(AuthException::invalidRefreshToken);
        refreshTokenRepository.delete(stored);
        if (stored.isExpired()) {
            throw AuthException.refreshTokenExpired();
        }
        if (!userExists(stored.getUserId())) {
            // 지워진 사용자: 남은 refresh 토큰도 함께 폐기한다(FK 가 없어 행이 남는다).
            refreshTokenRepository.deleteAllByUserId(stored.getUserId());
            throw AuthException.invalidRefreshToken();
        }
        return issue(stored.getUserId());
    }

    /** 쓰이지 않고 만료된 refresh 토큰 행 정리 (매일 04:30). */
    @Scheduled(cron = "0 30 4 * * *")
    @Transactional
    public void purgeExpiredRefreshTokens() {
        int n = refreshTokenRepository.deleteExpired(Instant.now());
        if (n > 0) {
            log.info("만료된 refresh 토큰 {}개 삭제", n);
        }
    }

    private boolean userExists(Long userId) {
        return userId != null && userId > 0 && userId <= Integer.MAX_VALUE
                && userRepository.existsById(userId.intValue());
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.deleteByTokenHash(hash(refreshToken));
    }

    private Tokens login(KakaoUser kakaoUser) {
        try {
            return tx.execute(s -> issue(upsertUser(kakaoUser)));
        } catch (DataIntegrityViolationException e) {
            // 같은 카카오 계정의 첫 로그인이 동시에 들어와 다른 요청이 먼저 만들었다(kakao_id unique). 새 트랜잭션에서 그 사용자로 로그인.
            return tx.execute(s -> issue(upsertUser(kakaoUser)));
        }
    }

    private Long upsertUser(KakaoUser kakaoUser) {
        String kakaoId = String.valueOf(kakaoUser.kakaoId());
        // [수정] 신규 가입 시 name 은 카카오 닉네임으로, email/profileImage 는 카카오 값으로 채운다.
        // role 은 제거됨(관계로 결정). contact 는 선택 항목이라 비워 둔다.
        // 기존 사용자의 입력값은 덮어쓰지 않고, 비어 있는 email/profileImage 만 보충한다.
        User user = userRepository.findByKakaoId(kakaoId)
                .orElseGet(() -> {
                    User created = new User();
                    created.setKakaoId(kakaoId);
                    created.setName(tempName(kakaoUser.nickname()));
                    created.setFullName(created.getName().isEmpty() ? null : created.getName());
                    return created;
                });
        if (user.getEmail() == null) {
            user.setEmail(kakaoUser.email());
        }
        if (user.getProfileImage() == null) {
            user.setProfileImage(kakaoUser.profileImage());
        }
        return userRepository.save(user).getId().longValue();
    }

    // [수정] name 은 NOT NULL, VARCHAR(50) 이므로 닉네임이 없거나 길면 보정한다.
    private static String tempName(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "";
        }
        return nickname.length() > 50 ? nickname.substring(0, 50) : nickname;
    }

    private Tokens issue(Long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokenRepository.save(new RefreshToken(userId, hash(refreshToken), Instant.now().plus(refreshTtl)));
        return new Tokens(jwtProvider.createAccessToken(userId), refreshToken, "Bearer", jwtProvider.accessTtlSeconds());
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
