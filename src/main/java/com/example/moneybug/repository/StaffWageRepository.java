package com.example.moneybug.repository;

import com.example.moneybug.entity.StaffWage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StaffWageRepository extends JpaRepository<StaffWage, Integer> {

    List<StaffWage> findByStoreWorkerIdOrderByEffectiveFromAscIdAsc(Integer storeWorkerId);
}
