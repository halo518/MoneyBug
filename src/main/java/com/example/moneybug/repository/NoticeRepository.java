package com.example.moneybug.repository;

import com.example.moneybug.entity.Notice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NoticeRepository extends JpaRepository<Notice, Integer> {

    /** 정렬 규칙: 고정(pinned) 먼저 -> 최신순. */
    List<Notice> findByStoreIdOrderByPinnedDescCreatedAtDescIdDesc(Integer storeId);

    Optional<Notice> findByIdAndStoreId(Integer id, Integer storeId);

    /** 재알림 쿨다운 검사-후-기록을 직렬화하는 행 잠금. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notice n where n.id = :id and n.store.id = :storeId")
    Optional<Notice> findByIdAndStoreIdForUpdate(@Param("id") Integer id, @Param("storeId") Integer storeId);
}
