package com.example.moneybug.repository;

import com.example.moneybug.entity.Contract;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContractRepository extends JpaRepository<Contract, Integer> {
    // 이력 보관: 가장 최근 건이 현재 계약서
    Optional<Contract> findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(Integer mappingId);
}
