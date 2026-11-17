package com.example.moneybug.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/** 매장별 회전 QR 출근 토큰. 형식 v1.{storeId}.{step}.{base64url HMAC-SHA256(storeId:step)}. */
@Service
public class QrTokenService {

    public static final int PERIOD_SECONDS = 30;

    private final byte[] key;

    public QrTokenService(@Value("${jwt.secret}") String secret) {
        this.key = ("qr:" + secret).getBytes(StandardCharsets.UTF_8);
    }

    public String issue(Integer storeId, Instant now) {
        return token(storeId, step(now));
    }

    /** 현재 step 의 만료 시각(다음 step 시작). */
    public Instant expiresAt(Instant now) {
        return Instant.ofEpochSecond((step(now) + 1) * PERIOD_SECONDS);
    }

    /** 현재·직전 step 의 이 매장 토큰이면 true. */
    public boolean verify(Integer storeId, String token, Instant now) {
        if (token == null) {
            return false;
        }
        byte[] given = token.getBytes(StandardCharsets.UTF_8);
        long cur = step(now);
        boolean ok = false;
        for (long s = cur - 1; s <= cur; s++) { // 상수시간 유지를 위해 early return 안 함
            ok |= MessageDigest.isEqual(given, token(storeId, s).getBytes(StandardCharsets.UTF_8));
        }
        return ok;
    }

    private static long step(Instant now) {
        return now.getEpochSecond() / PERIOD_SECONDS;
    }

    private String token(Integer storeId, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sig = mac.doFinal((storeId + ":" + step).getBytes(StandardCharsets.UTF_8));
            return "v1." + storeId + "." + step + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
