package com.example.moneybug.repository;

import com.example.moneybug.entity.StoreWorker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StoreWorkerRepository extends JpaRepository<StoreWorker, Integer> {

    List<StoreWorker> findByStoreIdAndStatusIn(Integer storeId, Collection<StoreWorker.Status> statuses);

    List<StoreWorker> findByStoreId(Integer storeId);

    List<StoreWorker> findByStoreIdAndStatus(Integer storeId, StoreWorker.Status status);

    Optional<StoreWorker> findByIdAndStoreId(Integer id, Integer storeId);

    /** 재가입을 허용하므로 (store, worker) 행이 여러 개일 수 있다. 상태까지 지정해 조회한다. */
    Optional<StoreWorker> findFirstByStoreIdAndWorkerIdAndStatus(Integer storeId, Integer workerId,
                                                                 StoreWorker.Status status);

    boolean existsByStoreIdAndWorkerIdAndStatus(Integer storeId, Integer workerId, StoreWorker.Status status);

    List<StoreWorker> findByWorkerIdAndStatusIn(Integer workerId, Collection<StoreWorker.Status> statuses);

    /** 보건증 만료 알림 대상: 상태가 status 이고 만료일이 [from, to] 인 직원 행. */
    @org.springframework.data.jpa.repository.Query("select sw from StoreWorker sw join fetch sw.worker w join fetch sw.store"
            + " where sw.status = :status and w.certExpiry between :from and :to")
    List<StoreWorker> findByStatusWithCertExpiryBetween(
            @org.springframework.data.repository.query.Param("status") StoreWorker.Status status,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDate to);

    long countByStoreIdAndStatus(Integer storeId, StoreWorker.Status status);
}
