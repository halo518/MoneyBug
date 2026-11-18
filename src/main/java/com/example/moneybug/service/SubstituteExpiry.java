package com.example.moneybug.service;

import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.repository.SubstituteRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 근무 시작 시각이 지난 진행 중(open·accepted) 대타 요청을 마감한다. 요청마다 따로 트랜잭션. */
@Component
@Slf4j
@RequiredArgsConstructor
public class SubstituteExpiry {

    private final SubstituteRequestRepository substituteRequestRepository;
    private final SubstituteService substituteService;
    private final StoreChangeHub changeHub;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        run(TimeUtil.now());
    }

    void run(LocalDateTime now) {
        List<Object[]> candidates = substituteRequestRepository.findPendingIdsUntil(
                List.of(SubstituteRequest.Status.OPEN, SubstituteRequest.Status.ACCEPTED), now.toLocalDate());
        for (Object[] c : candidates) {
            Integer requestId = (Integer) c[0];
            Integer storeId = (Integer) c[1];
            try {
                if (substituteService.expireIfStarted(storeId, requestId, now)) {
                    changeHub.bump(storeId);
                }
            } catch (RuntimeException e) {
                log.warn("대타 요청 마감 실패 requestId={}: {}", requestId, e.toString());
            }
        }
    }
}
