package com.example.moneybug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.dto.CommonDto;
import com.example.moneybug.dto.NotificationDto;
import com.example.moneybug.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 알림 (설계서 §4.9). 알림은 서버가 생성하므로 생성 API 는 없다. storeId 생략 시 전체 매장. */
@Tag(name = "알림", description = "내 알림 조회·읽음 처리 (설계서 4.9). 알림은 서버가 생성해요. storeId 생략 시 전체 매장")
@RestController
@RequestMapping("/api/v1/me/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "내 알림 목록", description = "최신순. 권한: 본인")
    @GetMapping
    public CommonDto.ListResponse<NotificationDto.Response> list(@AuthenticationPrincipal Long userId,
                                                                 @RequestParam(required = false) Integer storeId) {
        return notificationService.list(userId.intValue(), storeId);
    }

    @Operation(summary = "미읽음 알림 수", description = "탭 배지용. 권한: 본인")
    @GetMapping("/unread-count")
    public CommonDto.CountResponse unreadCount(@AuthenticationPrincipal Long userId,
                                               @RequestParam(required = false) Integer storeId) {
        return notificationService.unreadCount(userId.intValue(), storeId);
    }

    @Operation(summary = "알림 읽음 처리", description = "권한: 본인")
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Long userId, @PathVariable Integer id) {
        notificationService.markRead(userId.intValue(), id);
    }

    @Operation(summary = "알림 전체 읽음 처리", description = "권한: 본인")
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Long userId, @RequestParam(required = false) Integer storeId) {
        notificationService.markAllRead(userId.intValue(), storeId);
    }
}
