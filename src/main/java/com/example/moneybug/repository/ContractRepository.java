package com.example.moneybug.repository;

import com.example.moneybug.entity.Contract;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface ContractRepository extends JpaRepository<Contract, Integer> {
    // 이력 보관: 가장 최근 건이 현재 계약서
    Optional<Contract> findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(Integer mappingId);

    /** 서명 직렬화용: 현재 계약서를 행 잠금으로 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Contract> findFirstForUpdateByStoreWorkerIdOrderByCreatedAtDescIdDesc(Integer mappingId);
}
