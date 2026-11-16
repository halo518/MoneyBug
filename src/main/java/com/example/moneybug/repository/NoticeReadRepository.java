package com.example.moneybug.repository;

import com.example.moneybug.entity.NoticeRead;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoticeReadRepository extends JpaRepository<NoticeRead, Integer> {

    List<NoticeRead> findByNoticeId(Integer noticeId);

    /** 매장 전체 공지의 읽음 기록 (목록 화면에서 한 번에 조회). */
    List<NoticeRead> findByNoticeStoreId(Integer storeId);

    boolean existsByNoticeIdAndStoreWorkerId(Integer noticeId, Integer mappingId);
}
