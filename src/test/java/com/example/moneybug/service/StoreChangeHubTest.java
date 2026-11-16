package com.example.moneybug.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreChangeHubTest {

    private static long seq(DeferredResult<Map<String, Object>> r) {
        return (long) ((Map<?, ?>) r.getResult()).get("seq");
    }

    private static boolean changed(DeferredResult<Map<String, Object>> r) {
        return (boolean) ((Map<?, ?>) r.getResult()).get("changed");
    }

    @Test
    void baselineThenWaitsUntilMyStoreChanges() {
        StoreChangeHub hub = new StoreChangeHub();
        DeferredResult<Map<String, Object>> base = hub.await(Set.of(1), -1);
        assertFalse(changed(base));

        DeferredResult<Map<String, Object>> wait = hub.await(Set.of(1), seq(base));
        assertFalse(wait.hasResult());
        hub.bump(2); // 다른 매장
        assertFalse(wait.hasResult());
        hub.bump(1);
        assertTrue(wait.hasResult());
        assertTrue(changed(wait));
    }

    @Test
    void changeBeforeWaitingIsNotMissed() {
        StoreChangeHub hub = new StoreChangeHub();
        long since = seq(hub.await(Set.of(1), -1));
        hub.bump(1);
        DeferredResult<Map<String, Object>> r = hub.await(Set.of(1), since);
        assertTrue(changed(r));
        assertEquals(seq(r), seq(hub.await(Set.of(1), -1)));
    }
}
