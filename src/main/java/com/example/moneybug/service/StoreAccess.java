package com.example.moneybug.service;

import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 권한 검사 (설계서 §1.6). 사장님 = 해당 매장 소유자(Store.employer), 직원 = 해당 매장 재직자(ACTIVE),
 * 본인 = 리소스의 당사자. 매장이 없으면 404, 권한이 없으면 403.
 */
@Component
@RequiredArgsConstructor
public class StoreAccess {

    private final StoreRepository storeRepository;
    private final StoreWorkerRepository storeWorkerRepository;

    public Store store(Integer storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> ApiException.notFound("매장을 찾을 수 없어요"));
    }

    public boolean isOwner(Store store, Integer userId) {
        return store.getEmployer().getId().equals(userId);
    }

    public Store requireOwner(Integer userId, Integer storeId) {
        Store store = store(storeId);
        if (!isOwner(store, userId)) {
            throw ApiException.forbidden("사장님만 사용할 수 있어요");
        }
        return store;
    }

    public Store requireOwnerOrStaff(Integer userId, Integer storeId) {
        Store store = store(storeId);
        if (isOwner(store, userId) || activeStaff(userId, storeId).isPresent()) {
            return store;
        }
        throw ApiException.forbidden("이 매장의 사장님 또는 재직 직원만 사용할 수 있어요");
    }

    public Optional<StoreWorker> activeStaff(Integer userId, Integer storeId) {
        return storeWorkerRepository.findFirstByStoreIdAndWorkerIdAndStatus(storeId, userId,
                StoreWorker.Status.ACTIVE);
    }

    public StoreWorker requireActiveStaff(Integer userId, Integer storeId) {
        store(storeId);
        return activeStaff(userId, storeId)
                .orElseThrow(() -> ApiException.forbidden("이 매장의 재직 직원만 사용할 수 있어요"));
    }

    /** 매장 소속 직원(StoreWorker) 조회. 다른 매장 직원이면 404. */
    public StoreWorker staff(Integer storeId, Integer staffId) {
        return storeWorkerRepository.findByIdAndStoreId(staffId, storeId)
                .orElseThrow(() -> ApiException.notFound("직원을 찾을 수 없어요"));
    }
}
