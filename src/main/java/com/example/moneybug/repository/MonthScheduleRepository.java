package com.example.moneybug.repository;

import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.entity.Store;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MonthScheduleRepository extends JpaRepository<MonthSchedule, Integer> {
    List<MonthSchedule> findByStoreIdOrderByMonthAsc(Integer storeId);

    Optional<MonthSchedule> findByStoreIdAndMonth(Integer storeId, String month);

    /** 매장 행 쓰기 잠금: 월 근무표 저장·확정·AI 반영을 매장 단위로 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Store s where s.id = :id")
    Store lockStore(@Param("id") Integer id);
}
