package com.example.moneybug.auth.kakao;

import com.example.moneybug.auth.AuthException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class KakaoClient {

    private static final String AUTHORIZE_URL = "https://kauth.kakao.com/oauth/authorize";
    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String USER_ME_URL = "https://kapi.kakao.com/v2/user/me";
    private static final String TOKEN_INFO_URL = "https://kapi.kakao.com/v1/user/access_token_info";

    public record KakaoUser(Long kakaoId, String nickname, String email, String profileImage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenInfo(@JsonProperty("id") Long id, @JsonProperty("app_id") String appId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserMe(Long id, @JsonProperty("kakao_account") Account kakaoAccount) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Account(String email, Profile profile) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Profile(String nickname, @JsonProperty("profile_image_url") String profileImageUrl) {}
    }

    private final RestClient restClient;
    private final String restApiKey;
    private final String clientSecret;
    private final String redirectUri;
    private final String appId;

    public KakaoClient(@Value("${kakao.rest-api-key}") String restApiKey,
                       @Value("${kakao.client-secret}") String clientSecret,
                       @Value("${kakao.redirect-uri}") String redirectUri,
                       @Value("${kakao.app-id}") String appId) {
        this.restClient = RestClient.create();
        this.restApiKey = restApiKey;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.appId = appId;
    }

    public String authorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", restApiKey)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .build().encode().toUriString();
    }

    /** Web flow: authorization code -> Kakao access token. */
    public String exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", restApiKey);
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        if (!clientSecret.isBlank()) {
            form.add("client_secret", clientSecret);
        }
        try {
            TokenResponse res = restClient.post().uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(TokenResponse.class);
            if (res == null || res.accessToken() == null) {
                throw loginFailed(null);
            }
            return res.accessToken();
        } catch (RestClientException e) {
            throw loginFailed(e);
        }
    }

    /** Mobile flow: reject tokens issued for a different Kakao app (only when kakao.app-id is set). */
    public void verifyAppId(String accessToken) {
        if (appId.isBlank()) {
            return;
        }
        requireHeaderSafe(accessToken);
        try {
            TokenInfo info = restClient.get().uri(TOKEN_INFO_URL)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve().body(TokenInfo.class);
            if (info == null || !appId.equals(info.appId())) {
                throw invalidToken(null);
            }
        } catch (RestClientException | IllegalArgumentException e) {
            throw invalidToken(e);
        }
    }

    public KakaoUser fetchUser(String accessToken) {
        requireHeaderSafe(accessToken);
        try {
            UserMe me = restClient.get().uri(USER_ME_URL)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve().body(UserMe.class);
            if (me == null || me.id() == null) {
                throw loginFailed(null);
            }
            UserMe.Account account = me.kakaoAccount();
            UserMe.Profile profile = account == null ? null : account.profile();
            return new KakaoUser(
                    me.id(),
                    profile == null ? null : profile.nickname(),
                    account == null ? null : account.email(),
                    profile == null ? null : profile.profileImageUrl());
        } catch (RestClientException | IllegalArgumentException e) {
            throw invalidToken(e);
        }
    }

    /** 카카오 토큰은 ASCII 라서 한글·이모지·공백·제어문자가 있으면 헤더를 만들기 전에 거절한다(JDK 가 IllegalArgumentException 을 던져 500 이 되던 것). */
    static void requireHeaderSafe(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()
                || !accessToken.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            throw invalidToken(null);
        }
    }

    private static AuthException invalidToken(Throwable cause) {
        return new AuthException(AuthException.INVALID_KAKAO_TOKEN,
                "카카오 로그인 정보가 올바르지 않아요. 다시 로그인해 주세요", cause);
    }

    private static AuthException loginFailed(Throwable cause) {
        return new AuthException(AuthException.KAKAO_LOGIN_FAILED, "카카오 로그인에 실패했어요. 다시 시도해 주세요", cause);
    }
}
