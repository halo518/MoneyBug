package com.example.moneybug.repository;

import com.example.moneybug.entity.SubstituteRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubstituteRequestRepository extends JpaRepository<SubstituteRequest, Integer> {

    @Query("select r from SubstituteRequest r join fetch r.schedule s join fetch s.storeWorker w "
            + "where r.id = :id and w.store.id = :storeId")
    Optional<SubstituteRequest> findByIdAndStore(@Param("id") Integer id, @Param("storeId") Integer storeId);

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
