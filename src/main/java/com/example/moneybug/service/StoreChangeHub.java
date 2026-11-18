package com.example.moneybug.service;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 매장 데이터 변경 신호 (롱 폴링). 앱·웹이 GET /me/changes?since= 로 기다리다가
 * 자기 매장이 바뀌면 바로 응답을 받고 /me/state 를 다시 읽는다.
 * seq 는 내 매장들의 마지막 변경 시각(ms, 서버 전체에서 단조 증가)이라 서버를 다시 띄워도 줄지 않는다.
 * 응답 seq 는 내 매장 값의 최댓값이라 남의 매장 쓰기 횟수가 드러나지 않는다. 단일 인스턴스 인메모리.
 */
@Component
public class StoreChangeHub {

    static final long TIMEOUT_MS = 25_000L;

    /** 마지막으로 준 값. 같은 ms 에 두 매장이 바뀌어도 값이 겹치지 않게 한다(겹치면 since 비교에서 하나를 놓친다). */
    private final AtomicLong clock = new AtomicLong();
    private final Map<Integer, Long> lastChange = new ConcurrentHashMap<>();
    private final Set<Waiter> waiters = ConcurrentHashMap.newKeySet();

    private record Waiter(Set<Integer> storeIds, DeferredResult<Map<String, Object>> result) {}

    public void bump(Integer storeId) {
        if (storeId == null) return;
        long now = System.currentTimeMillis();
        lastChange.put(storeId, clock.updateAndGet(prev -> Math.max(prev + 1, now)));
        for (Waiter w : waiters) {
            if (w.storeIds().contains(storeId)) w.result().setResult(response(w.storeIds(), true));
        }
    }

    public void bumpAll(Collection<Integer> storeIds) {
        storeIds.forEach(this::bump);
    }

    /** since 이후 storeIds 중 하나라도 바뀌었으면 바로, 아니면 바뀔 때까지(최대 25초) 기다린다. since < 0 이면 기준값만 준다. */
    public DeferredResult<Map<String, Object>> await(Set<Integer> storeIds, long since) {
        DeferredResult<Map<String, Object>> r = new DeferredResult<>(TIMEOUT_MS);
        if (since < 0) {
            r.setResult(response(storeIds, false));
            return r;
        }
        Waiter w = new Waiter(storeIds, r);
        r.onTimeout(() -> r.setResult(response(storeIds, false)));
        r.onCompletion(() -> waiters.remove(w));
        waiters.add(w);
        // 등록 뒤에 확인해야 사이에 온 변경을 놓치지 않는다.
        if (storeIds.stream().anyMatch(id -> lastChange.getOrDefault(id, 0L) > since)) {
            r.setResult(response(storeIds, true));
        }
        return r;
    }

    private Map<String, Object> response(Set<Integer> storeIds, boolean changed) {
        long seq = storeIds.stream().mapToLong(id -> lastChange.getOrDefault(id, 0L)).max().orElse(0L);
        return Map.of("seq", seq, "changed", changed);
    }
}
