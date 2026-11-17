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

    /** 매장 코드 비교는 대소문자·하이픈을 무시한다 (입력은 대문자+영숫자만 남긴 값). */
    @Query("select s from Store s where upper(replace(s.code, '-', '')) = :code")
    Optional<Store> findByNormalizedCode(@Param("code") String code);

    /** 가입 신청 직렬화용 행 잠금(중복 PENDING 방지). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Store s where s.id = :id")
    Optional<Store> findByIdForUpdate(@Param("id") Integer id);
}
