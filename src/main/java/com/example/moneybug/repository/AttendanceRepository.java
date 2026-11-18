package com.example.moneybug.repository;

import com.example.moneybug.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, Integer> {

    Optional<Attendance> findByScheduleId(Integer scheduleId);

    boolean existsByScheduleId(Integer scheduleId);

    List<Attendance> findByScheduleIdIn(Collection<Integer> scheduleIds);

    @Query("select a from Attendance a join fetch a.schedule s join fetch s.storeWorker w "
            + "where a.id = :id and w.store.id = :storeId")
    Optional<Attendance> findByIdAndStore(@Param("id") Integer id, @Param("storeId") Integer storeId);

    /** 내 열린 출근 기록(clockOut == null), 매장 기준. */
    @Query("select a from Attendance a join fetch a.schedule s join fetch s.storeWorker w "
            + "where w.worker.id = :userId and w.store.id = :storeId and a.actualEnd is null "
            + "order by a.actualStart desc")
    List<Attendance> findOpenByUserAndStore(@Param("userId") Integer userId, @Param("storeId") Integer storeId);

    /** 퇴근 미기록(actualEnd == null) 전체. 자동 퇴근 처리용. */
    @Query("select a from Attendance a join fetch a.schedule s join fetch s.storeWorker w "
            + "join fetch w.worker join fetch w.store st join fetch st.employer where a.actualEnd is null")
    List<Attendance> findAllOpen();

    /** 내 열린 출근 기록, 재직(ACTIVE) 중인 전체 매장 기준. 내보낸 매장의 기록은 다른 매장 출근을 막지 않는다. */
    @Query("select a from Attendance a join fetch a.schedule s join fetch s.storeWorker w join fetch w.store "
            + "where w.worker.id = :userId and w.status = com.example.moneybug.entity.StoreWorker.Status.ACTIVE "
            + "and a.actualEnd is null order by a.actualStart desc")
    List<Attendance> findOpenByUser(@Param("userId") Integer userId);
}
