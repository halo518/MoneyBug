package com.example.moneybug.service;

import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 롱 폴링(/me/changes)이 기다릴 매장 id(소유·재직·가입 대기).
 * 짧은 트랜잭션 안에서 읽어야 한다: hibernate.connection.handling_mode 가 트랜잭션이 끝나면 커넥션을 돌려주므로,
 * 25초 대기 동안 OSIV 가 커넥션을 붙잡지 않는다.
 */
@Service
@RequiredArgsConstructor
public class WatchedStores {

    private final StoreRepository storeRepository;
    private final StoreWorkerRepository storeWorkerRepository;

    @Transactional(readOnly = true)
    public Set<Integer> of(Integer userId) {
        Set<Integer> storeIds = new HashSet<>();
        storeRepository.findByEmployerId(userId).forEach(s -> storeIds.add(s.getId()));
        storeWorkerRepository.findByWorkerIdAndStatusIn(userId,
                        List.of(StoreWorker.Status.ACTIVE, StoreWorker.Status.PENDING))
                .forEach(m -> storeIds.add(m.getStore().getId()));
        return storeIds;
    }
}
