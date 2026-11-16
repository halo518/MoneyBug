package com.example.moneybug.repository;

import com.example.moneybug.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepository extends JpaRepository<Schedule, Integer> {

    @Query("select s from Schedule s join fetch s.storeWorker w "
            + "where s.id = :id and w.store.id = :storeId")
    Optional<Schedule> findByIdAndStore(@Param("id") Integer id, @Param("storeId") Integer storeId);

    /** 매장 전체 근무 (시작 시각순). */
    @Query("select s from Schedule s join fetch s.storeWorker w "
            + "where w.store.id = :storeId and s.workDate between :from and :to "
            + "order by s.workDate, s.startMin, s.id")
    List<Schedule> findByStoreBetween(@Param("storeId") Integer storeId,
                                      @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select s from Schedule s join fetch s.storeWorker w "
            + "where w.store.id = :storeId and s.workDate = :date order by s.startMin, s.id")
    List<Schedule> findByStoreAndDate(@Param("storeId") Integer storeId, @Param("date") LocalDate date);

    /** 한 직원(StoreWorker)의 근무 (시작 시각순). */
    @Query("select s from Schedule s where s.storeWorker.id = :mappingId "
            + "and s.workDate between :from and :to order by s.workDate, s.startMin, s.id")
    List<Schedule> findByWorkerBetween(@Param("mappingId") Integer mappingId,
                                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 파트 이름 변경 전파용: 매장의 해당 파트 근무 전부. */
    @Query("select s from Schedule s where s.storeWorker.store.id = :storeId and s.part in :names")
    List<Schedule> findByStoreAndPartIn(@Param("storeId") Integer storeId,
                                        @Param("names") java.util.Collection<String> names);

    /** 오늘 이후 근무 (직원 내보내기 시 정리 대상). */
    @Query("select s from Schedule s where s.storeWorker.id = :mappingId and s.workDate >= :from")
    List<Schedule> findFromDateByWorker(@Param("mappingId") Integer mappingId, @Param("from") LocalDate from);

    /** 시간 미정(timeTbd) 근무 수 — 근무표 확정 전 검증. */
    @Query("select count(s) from Schedule s where s.storeWorker.store.id = :storeId "
            + "and s.timeTbd = true and s.workDate between :from and :to")
    long countTbd(@Param("storeId") Integer storeId,
                  @Param("from") LocalDate from, @Param("to") LocalDate to);
}
