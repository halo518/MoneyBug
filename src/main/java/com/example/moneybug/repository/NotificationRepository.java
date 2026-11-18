package com.example.moneybug.repository;

import com.example.moneybug.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Integer> {
    // 최신순, 같은 시각이면 나중에 생긴 것 먼저
    List<Notification> findByToUserIdAndStoreIdOrderByCreatedAtDescIdDesc(Integer toUserId, Integer storeId);

    long countByToUserIdAndStoreIdAndReadFalse(Integer toUserId, Integer storeId);

    // storeId 를 생략한 조회용
    List<Notification> findByToUserIdOrderByCreatedAtDescIdDesc(Integer toUserId);

    long countByToUserIdAndReadFalse(Integer toUserId);

    List<Notification> findByToUserIdAndReadFalse(Integer toUserId);

    List<Notification> findByToUserIdAndStoreIdAndReadFalse(Integer toUserId, Integer storeId);

    /** 참조 대상(공지 등)이 지워질 때 그것을 가리키는 알림을 함께 지운다. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from Notification n where n.type = :type and n.refId = :refId")
    int deleteByTypeAndRefId(@org.springframework.data.repository.query.Param("type") Notification.Type type,
                             @org.springframework.data.repository.query.Param("refId") Integer refId);
}
