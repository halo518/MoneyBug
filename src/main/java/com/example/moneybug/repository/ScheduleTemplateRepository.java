package com.example.moneybug.repository;

import com.example.moneybug.entity.ScheduleTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScheduleTemplateRepository extends JpaRepository<ScheduleTemplate, Integer> {
    List<ScheduleTemplate> findByStoreIdOrderByIdAsc(Integer storeId);

    Optional<ScheduleTemplate> findByIdAndStoreId(Integer id, Integer storeId);
}
