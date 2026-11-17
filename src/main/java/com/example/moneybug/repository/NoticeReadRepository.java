package com.example.moneybug.repository;

import com.example.moneybug.entity.NoticeRead;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface NoticeReadRepository extends JpaRepository<NoticeRead, Integer> {

    List<NoticeRead> findByNoticeId(Integer noticeId);

    /** 매장 전체 공지의 읽음 기록 (목록 화면에서 한 번에 조회). */
    List<NoticeRead> findByNoticeStoreId(Integer storeId);

    /** 동시 호출에도 안전한 멱등 insert (uk_notice_read 충돌은 무시). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value =
            "INSERT IGNORE INTO notice_read (notice_id, mapping_id, read_at) VALUES (:noticeId, :mappingId, :readAt)")
    int insertIgnore(@org.springframework.data.repository.query.Param("noticeId") Integer noticeId,
                     @org.springframework.data.repository.query.Param("mappingId") Integer mappingId,
                     @org.springframework.data.repository.query.Param("readAt") LocalDateTime readAt);

    boolean existsByNoticeIdAndStoreWorkerId(Integer noticeId, Integer mappingId);
}
