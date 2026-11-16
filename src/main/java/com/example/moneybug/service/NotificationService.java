package com.example.moneybug.service;

import com.example.moneybug.dto.CommonDto;
import com.example.moneybug.dto.NotificationDto;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 알림 (설계서 §4.9). 알림 생성은 각 쓰기 서비스의 부수효과로 처리한다(§5).
 * 조회/읽음 처리는 단순해서 바로 구현했다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /** GET /me/notifications?storeId= — 최신순, 같은 시각이면 나중에 생긴 것 먼저. */
    @Transactional(readOnly = true)
    public CommonDto.ListResponse<NotificationDto.Response> list(Integer userId, Integer storeId) {
        List<Notification> notifications = storeId == null
                ? notificationRepository.findByToUserIdOrderByCreatedAtDescIdDesc(userId)
                : notificationRepository.findByToUserIdAndStoreIdOrderByCreatedAtDescIdDesc(userId, storeId);
        return CommonDto.ListResponse.of(notifications.stream().map(NotificationDto.Response::from).toList());
    }

    /** GET /me/notifications/unread-count?storeId= */
    @Transactional(readOnly = true)
    public CommonDto.CountResponse unreadCount(Integer userId, Integer storeId) {
        long count = storeId == null
                ? notificationRepository.countByToUserIdAndReadFalse(userId)
                : notificationRepository.countByToUserIdAndStoreIdAndReadFalse(userId, storeId);
        return new CommonDto.CountResponse(count);
    }

    /** POST /me/notifications/{id}/read — 본인 알림만. */
    public void markRead(Integer userId, Integer notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> ApiException.notFound("알림을 찾을 수 없어요"));
        if (!notification.getToUser().getId().equals(userId)) {
            throw ApiException.forbidden("본인의 알림만 읽음 처리할 수 있어요");
        }
        notification.setRead(true);
    }

    /** POST /me/notifications/read-all?storeId= */
    public void markAllRead(Integer userId, Integer storeId) {
        List<Notification> unread = storeId == null
                ? notificationRepository.findByToUserIdAndReadFalse(userId)
                : notificationRepository.findByToUserIdAndStoreIdAndReadFalse(userId, storeId);
        unread.forEach(n -> n.setRead(true));
    }
}
