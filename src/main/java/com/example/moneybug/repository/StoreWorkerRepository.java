package com.example.moneybug.repository;

import com.example.moneybug.entity.StoreWorker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StoreWorkerRepository extends JpaRepository<StoreWorker, Integer> {

    List<StoreWorker> findByStoreIdAndStatusIn(Integer storeId, Collection<StoreWorker.Status> statuses);

    List<StoreWorker> findByStoreIdAndStatus(Integer storeId, StoreWorker.Status status);

    Optional<StoreWorker> findByIdAndStoreId(Integer id, Integer storeId);

    /** 재가입을 허용하므로 (store, worker) 행이 여러 개일 수 있다. 상태까지 지정해 조회한다. */
    Optional<StoreWorker> findFirstByStoreIdAndWorkerIdAndStatus(Integer storeId, Integer workerId,
                                                                 StoreWorker.Status status);

    boolean existsByStoreIdAndWorkerIdAndStatus(Integer storeId, Integer workerId, StoreWorker.Status status);

    List<StoreWorker> findByWorkerIdAndStatusIn(Integer workerId, Collection<StoreWorker.Status> statuses);

    long countByStoreIdAndStatus(Integer storeId, StoreWorker.Status status);
}
