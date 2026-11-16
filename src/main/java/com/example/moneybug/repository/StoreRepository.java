package com.example.moneybug.repository;

import com.example.moneybug.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
