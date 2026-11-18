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

    @Test
    void otherStoresWritesDoNotShowInMySeq() {
        StoreChangeHub hub = new StoreChangeHub();
        hub.bump(1);
        long mine = seq(hub.await(Set.of(1), -1));
        for (int i = 0; i < 5; i++) hub.bump(2);
        assertEquals(mine, seq(hub.await(Set.of(1), -1)));
        assertEquals(0L, seq(hub.await(Set.of(3), -1)), "바뀐 적 없는 매장만 있으면 0");
    }

    @Test
    void twoStoresChangedInTheSameMillisecondAreBothSeen() {
        StoreChangeHub hub = new StoreChangeHub();
        hub.bump(1);
        long since = seq(hub.await(Set.of(1, 2), -1));
        hub.bump(2);
        assertTrue(changed(hub.await(Set.of(1, 2), since)));
    }
}
