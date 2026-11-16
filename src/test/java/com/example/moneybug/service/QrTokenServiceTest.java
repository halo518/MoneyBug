package com.example.moneybug.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrTokenServiceTest {

    private final QrTokenService svc = new QrTokenService("test-secret-of-at-least-32-bytes-long!!");
    private final Instant t0 = Instant.ofEpochSecond(30_000);

    @Test
    void issuedTokenVerifies() {
        assertTrue(svc.verify(1, svc.issue(1, t0), t0));
    }

    @Test
    void previousStepAcceptedThenExpired() {
        String token = svc.issue(1, t0);
        assertTrue(svc.verify(1, token, t0.plusSeconds(30)));
        assertFalse(svc.verify(1, token, t0.plusSeconds(60)));
    }

    @Test
    void otherStoreRejected() {
        assertFalse(svc.verify(2, svc.issue(1, t0), t0));
    }

    @Test
    void forgedOrMissingRejected() {
        String token = svc.issue(1, t0);
        assertFalse(svc.verify(1, token.substring(0, token.length() - 2) + "xx", t0));
        assertFalse(svc.verify(1, "v1.1." + 1000 + ".AAAA", t0));
        assertFalse(svc.verify(1, null, t0));
        assertFalse(svc.verify(1, "", t0));
        assertFalse(new QrTokenService("other-secret-of-at-least-32-bytes!!!!!").verify(1, token, t0));
    }
}
