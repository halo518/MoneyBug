package com.example.moneybug.repository;

import com.example.moneybug.entity.StoreWorker;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StoreWorkerRepository extends JpaRepository<StoreWorker, Integer> {

    List<StoreWorker> findByStoreIdAndStatusIn(Integer storeId, Collection<StoreWorker.Status> statuses);

    List<StoreWorker> findByStoreId(Integer storeId);

    List<StoreWorker> findByStoreIdAndStatus(Integer storeId, StoreWorker.Status status);

    Optional<StoreWorker> findByIdAndStoreId(Integer id, Integer storeId);

    /** 상태 전이(승인·거절·퇴사·시급 변경)용 행 잠금. 매장 행을 잠근 뒤에 부른다(Store → StoreWorker 순서). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from StoreWorker w where w.id = :id and w.store.id = :storeId")
    Optional<StoreWorker> findByIdAndStoreIdForUpdate(@Param("id") Integer id, @Param("storeId") Integer storeId);

    /** 재가입을 허용하므로 (store, worker) 행이 여러 개일 수 있다. 상태까지 지정해 조회한다. */
    Optional<StoreWorker> findFirstByStoreIdAndWorkerIdAndStatus(Integer storeId, Integer workerId,
                                                                 StoreWorker.Status status);

    boolean existsByStoreIdAndWorkerIdAndStatus(Integer storeId, Integer workerId, StoreWorker.Status status);

    List<StoreWorker> findByStoreIdAndWorkerIdAndStatus(Integer storeId, Integer workerId, StoreWorker.Status status);

    List<StoreWorker> findByWorkerIdAndStatusIn(Integer workerId, Collection<StoreWorker.Status> statuses);

    long countByStoreIdAndStatus(Integer storeId, StoreWorker.Status status);
}
