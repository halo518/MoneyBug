package com.example.moneybug.repository;

import com.example.moneybug.entity.MonthSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonthScheduleRepository extends JpaRepository<MonthSchedule, Integer> {
    List<MonthSchedule> findByStoreIdOrderByMonthAsc(Integer storeId);

    Optional<MonthSchedule> findByStoreIdAndMonth(Integer storeId, String month);
}
