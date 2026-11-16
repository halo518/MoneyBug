package com.example.moneybug.repository;

import com.example.moneybug.entity.Notice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoticeRepository extends JpaRepository<Notice, Integer> {

    /** 정렬 규칙: 고정(pinned) 먼저 -> 최신순. */
    List<Notice> findByStoreIdOrderByPinnedDescCreatedAtDescIdDesc(Integer storeId);

    Optional<Notice> findByIdAndStoreId(Integer id, Integer storeId);
}
