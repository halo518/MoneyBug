package com.example.moneybug.repository;

import com.example.moneybug.entity.SubstituteRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubstituteRequestRepository extends JpaRepository<SubstituteRequest, Integer> {

    @Query("select r from SubstituteRequest r join fetch r.schedule s join fetch s.storeWorker w "
            + "where r.id = :id and w.store.id = :storeId")
    Optional<SubstituteRequest> findByIdAndStore(@Param("id") Integer id, @Param("storeId") Integer storeId);

    /** 요청 행만 쓰기 잠금(조인 없이, 근무·직원 행은 잠그지 않는다). 상태 전이 전에 매장 행 잠금 뒤 부른다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SubstituteRequest r where r.id = :id")
    Optional<SubstituteRequest> findByIdForUpdate(@Param("id") Integer id);

    /** 근무 날짜가 date 이하인 진행 중 요청의 [요청 id, 매장 id] (시작이 지난 요청 마감용 후보). */
    @Query("select r.id, w.store.id from SubstituteRequest r join r.schedule s join s.storeWorker w "
            + "where r.status in :statuses and s.workDate <= :date order by w.store.id, r.id")
    List<Object[]> findPendingIdsUntil(@Param("statuses") Collection<SubstituteRequest.Status> statuses,
                                       @Param("date") LocalDate date);

    /** 매장 전체, 최신순 (createdAt desc). */
    @Query("select r from SubstituteRequest r join fetch r.schedule s join fetch s.storeWorker w "
            + "where w.store.id = :storeId order by r.createdAt desc, r.id desc")
    List<SubstituteRequest> findByStore(@Param("storeId") Integer storeId);

    @Query("select r from SubstituteRequest r join fetch r.schedule s join fetch s.storeWorker w "
            + "where w.store.id = :storeId and r.status in :statuses order by r.createdAt desc, r.id desc")
    List<SubstituteRequest> findByStoreAndStatusIn(@Param("storeId") Integer storeId,
                                                   @Param("statuses") Collection<SubstituteRequest.Status> statuses);

    List<SubstituteRequest> findByScheduleId(Integer scheduleId);

    List<SubstituteRequest> findByScheduleIdAndStatusIn(Integer scheduleId,
                                                        Collection<SubstituteRequest.Status> statuses);
}
