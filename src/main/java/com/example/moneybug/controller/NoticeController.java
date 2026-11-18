package com.example.moneybug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.dto.NoticeDto;
import com.example.moneybug.dto.NoticeViewDto;
import com.example.moneybug.service.NoticeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 공지 (설계서 §4.8). */
@Tag(name = "공지", description = "공지 작성·조회·읽음 현황 (설계서 4.8). 정렬: 고정 먼저 → 최신순")
@RestController
@RequestMapping("/api/v1/stores/{storeId}/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "공지 목록", description = "사장님은 읽음 현황(readBy·unreadCount), 직원은 isRead 와 unreadNoticeCount. 권한: 사장님·직원")
    @GetMapping
    public NoticeViewDto.ListResponse list(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId) {
        return noticeService.list(userId.intValue(), storeId);
    }

    @Operation(summary = "공지 상세", description = "권한: 사장님·직원")
    @GetMapping("/{id}")
    public NoticeViewDto.View get(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                  @PathVariable Integer id) {
        return noticeService.get(userId.intValue(), storeId, id);
    }

    @Operation(summary = "공지 작성", description = "제목·내용 필수. 재직 직원 전원에게 알림(필독이면 새 필독 공지). 권한: 사장님")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoticeViewDto.View publish(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                      @Valid @RequestBody NoticeDto.Request request) {
        return noticeService.publish(userId.intValue(), storeId, request);
    }

    @Operation(summary = "공지 수정", description = "제목·내용 필수. 읽음 기록은 유지하고 알림은 다시 보내지 않아요. 권한: 사장님")
    @PutMapping("/{id}")
    public NoticeViewDto.View update(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                     @PathVariable Integer id, @Valid @RequestBody NoticeDto.Request request) {
        return noticeService.update(userId.intValue(), storeId, id, request);
    }

    @Operation(summary = "공지 삭제", description = "읽음 기록과 공지 알림도 함께 지워요. 권한: 사장님")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                       @PathVariable Integer id) {
        noticeService.delete(userId.intValue(), storeId, id);
    }

    @Operation(summary = "공지 읽음 처리", description = "이미 읽었으면 변경 없음(멱등). 권한: 직원")
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                         @PathVariable Integer id) {
        noticeService.markRead(userId.intValue(), storeId, id);
    }

    @Operation(summary = "공지 전체 읽음 처리", description = "권한: 직원")
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId) {
        noticeService.markAllRead(userId.intValue(), storeId);
    }

    @Operation(summary = "공지 읽음 현황", description = "읽은 직원과 안 읽은 직원 목록. 권한: 사장님")
    @GetMapping("/{id}/read-status")
    public NoticeViewDto.ReadStatusResponse readStatus(@AuthenticationPrincipal Long userId,
                                                       @PathVariable Integer storeId, @PathVariable Integer id) {
        return noticeService.readStatus(userId.intValue(), storeId, id);
    }

    @Operation(summary = "안 읽은 직원에게 재알림", description = "보낸 인원 수(sent)를 돌려줘요. 권한: 사장님")
    @PostMapping("/{id}/remind")
    public NoticeViewDto.RemindResponse remind(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                               @PathVariable Integer id) {
        return noticeService.remind(userId.intValue(), storeId, id);
    }
}
