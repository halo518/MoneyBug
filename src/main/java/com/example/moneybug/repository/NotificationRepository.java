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
}
