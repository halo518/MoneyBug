package com.example.moneybug.repository;

import com.example.moneybug.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Integer> {

    List<Store> findByEmployerId(Integer employerId);

    boolean existsByCode(String code);

    /** 가입 신청: 코드(XXXX-XXXX, 컬럼 비교는 대소문자 무시)로 매장 행을 찾으면서 잠근다(중복 PENDING 방지). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Store s where s.code = :code")
    Optional<Store> findByCodeForUpdate(@Param("code") String code);

    /** 매장 행 잠금: 같은 매장의 동시 변경을 줄 세운다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Store s where s.id = :id")
    Optional<Store> findByIdForUpdate(@Param("id") Integer id);
}
