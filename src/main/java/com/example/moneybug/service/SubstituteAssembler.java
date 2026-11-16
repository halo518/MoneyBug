package com.example.moneybug.service;

import com.example.moneybug.dto.SubstituteRequestDto;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.SubstituteApplicantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** SubstituteRequest -> 응답. User 를 그 매장의 직원 id 로 바꾼다 (FE 는 대타 관련 id 를 직원 id 로 씀). */
@Component
@RequiredArgsConstructor
public class SubstituteAssembler {

    private final SubstituteApplicantRepository substituteApplicantRepository;
    private final StoreWorkerRepository storeWorkerRepository;

    public SubstituteRequestDto.Response toDto(SubstituteRequest r) {
        Integer storeId = r.getSchedule().getStoreWorker().getStore().getId();
        // 재가입으로 같은 사람의 행이 여러 개일 수 있다. 재직(ACTIVE) 행을 우선한다.
        Map<Integer, Integer> staffIdByUser = new HashMap<>();
        for (StoreWorker sw : storeWorkerRepository.findByStoreIdAndStatusIn(storeId,
                Arrays.asList(StoreWorker.Status.values()))) {
            Integer userId = sw.getWorker().getId();
            if (sw.getStatus() == StoreWorker.Status.ACTIVE || !staffIdByUser.containsKey(userId)) {
                staffIdByUser.put(userId, sw.getId());
            }
        }
        return SubstituteRequestDto.Response.from(r,
                substituteApplicantRepository.findByRequestId(r.getId()).stream().map(a -> a.getApplicant()).toList(),
                u -> staffIdByUser.get(u.getId()));
    }
}
