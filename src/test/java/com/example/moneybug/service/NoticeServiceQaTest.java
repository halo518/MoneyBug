package com.example.moneybug.service;

import com.example.moneybug.dto.NoticeDto;
import com.example.moneybug.dto.NoticeViewDto;
import com.example.moneybug.entity.Notice;
import com.example.moneybug.entity.NoticeRead;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.NoticeReadRepository;
import com.example.moneybug.repository.NoticeRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** QA 14: 작성 검증·길이, 퇴사자 읽음 제외, 재알림 행 잠금. */
class NoticeServiceQaTest {

    final NoticeRepository notices = mock(NoticeRepository.class);
    final NoticeReadRepository reads = mock(NoticeReadRepository.class);
    final StoreWorkerRepository workers = mock(StoreWorkerRepository.class);
    final StoreAccess access = mock(StoreAccess.class);
    final NoticeService svc = new NoticeService(notices, reads, workers, access,
            mock(StaffAssembler.class), mock(NotificationPublisher.class));
    final Store store = new Store();

    NoticeServiceQaTest() {
        store.setId(1);
        when(access.requireOwner(anyInt(), anyInt())).thenReturn(store);
        when(access.requireOwnerOrStaff(anyInt(), anyInt())).thenReturn(store);
        when(access.isOwner(any(), anyInt())).thenReturn(true);
    }

    NoticeDto.Request req(String title, String body) {
        NoticeDto.Request r = new NoticeDto.Request();
        r.setTitle(title);
        r.setContent(body);
        r.setRequired(false);
        return r;
    }

    ApiException publishFails(String title, String body) {
        return assertThrows(ApiException.class, () -> svc.publish(9, 1, req(title, body)));
    }

    @Test
    void requiredFieldsUseDesignedCode() {
        for (String[] tb : new String[][]{{"  ", "x"}, {"t", null}, {null, "x"}, {"t", " \n "}}) {
            ApiException e = publishFails(tb[0], tb[1]);
            assertEquals("NOTICE_REQUIRED_FIELDS", e.getCode());
            assertEquals("제목과 내용을 입력해 주세요", e.getMessage());
        }
        verify(notices, never()).save(any());
    }

    @Test
    void requestBindsFeKeyBody() throws Exception {
        NoticeDto.Request r = JsonMapper.builder().build()
                .readValue("{\"title\":\"t\",\"body\":\"b\"}", NoticeDto.Request.class);
        assertEquals("b", r.getContent());
    }

    @Test
    void lengthCountedAfterTrimAndByCharacter() {
        // trim 하면 199자 → 통과
        NoticeViewDto.View v = svc.publish(9, 1, req(" " + "가".repeat(199) + " ", "x"));
        assertEquals(199, v.title().length());
        // 이모지 2600개(UTF-16 5200) → 5000자 이내로 통과
        svc.publish(9, 1, req("e", "😀".repeat(2600)));
        svc.publish(9, 1, req("😀".repeat(200), "x"));

        assertEquals("NOTICE_TOO_LONG", publishFails("가".repeat(201), "x").getCode());
        assertEquals("NOTICE_TOO_LONG", publishFails("t", "😀".repeat(5001)).getCode());
    }

    StoreWorker worker(int id, LocalDate joined) {
        StoreWorker sw = new StoreWorker();
        sw.setId(id);
        sw.setJoinedAt(joined);
        return sw;
    }

    Notice notice(LocalDateTime createdAt) {
        Notice n = new Notice();
        n.setId(5);
        n.setStore(store);
        n.setTitle("t");
        n.setContent("b");
        n.setRequired(true);
        n.setCreatedAt(createdAt);
        return n;
    }

    NoticeRead read(Notice n, StoreWorker sw) {
        NoticeRead r = new NoticeRead();
        r.setNotice(n);
        r.setStoreWorker(sw);
        r.setReadAt(LocalDateTime.of(2026, 11, 16, 9, 0));
        return r;
    }

    @Test
    void formerStaffReadsAreDroppedAndCountsAddUpToTargets() {
        Notice n = notice(LocalDateTime.of(2026, 11, 10, 10, 0));
        StoreWorker a = worker(1, LocalDate.of(2026, 11, 1));   // 대상, 읽음
        StoreWorker b = worker(2, LocalDate.of(2026, 11, 1));   // 대상, 안 읽음
        StoreWorker late = worker(3, LocalDate.of(2026, 11, 15)); // 가입 전 공지 → 대상 아님
        StoreWorker left = worker(4, LocalDate.of(2026, 11, 1));  // 퇴사자, 읽음
        when(workers.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(a, b, late));
        when(notices.findByIdAndStoreId(5, 1)).thenReturn(Optional.of(n));
        when(notices.findByStoreIdOrderByPinnedDescCreatedAtDescIdDesc(1)).thenReturn(List.of(n));
        when(reads.findByNoticeId(5)).thenReturn(List.of(read(n, a), read(n, left)));
        when(reads.findByNoticeStoreId(1)).thenReturn(List.of(read(n, a), read(n, left)));

        NoticeViewDto.ReadStatusResponse rs = svc.readStatus(9, 1, 5);
        assertEquals(List.of(1), rs.read().stream().map(NoticeViewDto.ReadEntry::staffId).toList());
        assertEquals(1, rs.unread().size());

        for (NoticeViewDto.View v : List.of(svc.get(9, 1, 5), svc.list(9, 1).items().get(0))) {
            assertEquals(java.util.Set.of(1), v.readBy().keySet());
            assertEquals(1, v.unreadCount()); // readBy 1 + unread 1 = 대상 2명
        }
    }

    @Test
    void remindLocksNoticeRowBeforeCooldownCheck() {
        Notice n = notice(LocalDateTime.of(2026, 11, 10, 10, 0));
        n.setRemindedAt(TimeUtil.now());
        when(notices.findByIdAndStoreIdForUpdate(5, 1)).thenReturn(Optional.of(n));

        ApiException e = assertThrows(ApiException.class, () -> svc.remind(9, 1, 5));
        assertEquals("REMIND_TOO_SOON", e.getCode());
        verify(notices).findByIdAndStoreIdForUpdate(5, 1);
        verify(notices, never()).findByIdAndStoreId(anyInt(), anyInt());
    }
}
