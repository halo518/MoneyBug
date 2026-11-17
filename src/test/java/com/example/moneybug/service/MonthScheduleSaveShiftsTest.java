package com.example.moneybug.service;

import com.example.moneybug.dto.MonthScheduleCommandDto;
import com.example.moneybug.dto.MonthScheduleCommandDto.ShiftItem;
import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** 통째 저장: 기존 근무 수정은 그 행에 반영, 빠진 근무는 삭제. DB 없이. */
class MonthScheduleSaveShiftsTest {

    ScheduleRepository scheduleRepo = mock(ScheduleRepository.class);
    StoreWorkerRepository workerRepo = mock(StoreWorkerRepository.class);
    AttendanceRepository attendanceRepo = mock(AttendanceRepository.class);
    MonthScheduleRepository monthRepo = mock(MonthScheduleRepository.class);
    ScheduleDeleter deleter = mock(ScheduleDeleter.class);
    NotificationPublisher notifier = mock(NotificationPublisher.class);
    MonthScheduleService service = new MonthScheduleService(mock(StoreAccess.class), monthRepo,
            mock(ScheduleTemplateRepository.class), scheduleRepo, attendanceRepo, workerRepo,
            mock(StorePartRepository.class), mock(OverlapChecker.class), deleter, mock(ShiftService.class),
            mock(ScheduleAiService.class), mock(StaffWageService.class), mock(ScheduleJobStore.class), mock(StoreChangeHub.class),
            notifier, mock(PlatformTransactionManager.class));

    static Schedule shift(int id, StoreWorker sw, LocalDate d, int start, int end) {
        Schedule s = new Schedule();
        s.setId(id);
        s.setStoreWorker(sw);
        s.setWorkDate(d);
        s.setStartMin(start);
        s.setEndMin(end);
        s.setPart("오픈");
        return s;
    }

    static ShiftItem item(String id, int staffId, LocalDate d, int start, int end) {
        return new ShiftItem(id, staffId, d, start, end, "오픈", 30, Schedule.Kind.WORK, false);
    }

    @Test
    void editsInPlaceAndDeletesMissing() {
        StoreWorker sw = new StoreWorker();
        sw.setId(45);
        LocalDate d3 = LocalDate.of(2026, 12, 3), d5 = LocalDate.of(2026, 12, 5);
        Schedule keep = shift(123, sw, d3, 540, 1080);
        Schedule gone = shift(124, sw, d5, 540, 1080);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(keep, gone));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        service.saveShifts(7, 1, "2026-12", new MonthScheduleCommandDto.SaveShiftsRequest(List.of(
                item("123", 45, d3, 600, 1140),
                item(null, 45, LocalDate.of(2026, 12, 4), 540, 1080))));

        assertThat(keep.getStartMin()).isEqualTo(600);
        assertThat(keep.getEndMin()).isEqualTo(1140);
        verify(deleter).delete(gone);
        verify(deleter, never()).delete(keep);
        verify(scheduleRepo).saveAll(argThat(l -> ((List<?>) l).size() == 1));
    }

    /** 퇴사자(LEFT)의 지난 근무도 그 달 목록에 그대로 실려 온다. 이것 때문에 통째 저장 전체가 400 이 되면 안 된다. */
    @Test
    void keepsShiftsOfStaffWhoLeft() {
        StoreWorker sw = new StoreWorker();
        sw.setId(45);
        StoreWorker left = new StoreWorker();
        left.setId(46);
        left.setStatus(StoreWorker.Status.LEFT);
        LocalDate d3 = LocalDate.of(2026, 11, 3);
        Schedule mine = shift(123, sw, d3, 540, 1080);
        Schedule old = shift(200, left, LocalDate.of(2026, 11, 2), 540, 1080);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(old, mine));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        service.saveShifts(7, 1, "2026-11", new MonthScheduleCommandDto.SaveShiftsRequest(List.of(
                item("200", 46, LocalDate.of(2026, 11, 2), 540, 1080),
                item("123", 45, d3, 600, 1140))));

        assertThat(mine.getStartMin()).isEqualTo(600);
        verify(deleter, never()).delete(any());
    }

    /** 퇴사자에게 새로 근무를 넣는 것은 여전히 막는다. */
    @Test
    void rejectsNewShiftForStaffWhoLeft() {
        StoreWorker left = new StoreWorker();
        left.setId(46);
        Schedule old = shift(200, left, LocalDate.of(2026, 11, 2), 540, 1080);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of());
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(old));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveShifts(7, 1, "2026-11",
                new MonthScheduleCommandDto.SaveShiftsRequest(List.of(
                        item(null, 46, LocalDate.of(2026, 11, 5), 540, 1080)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("재직");
    }

    @Test
    void rejectsBadBreakAndOver24h() {
        StoreWorker sw = new StoreWorker();
        sw.setId(45);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of());
        LocalDate d = LocalDate.of(2026, 12, 4);
        var neg = new ShiftItem(null, 45, d, 540, 1080, "오픈", -30, Schedule.Kind.WORK, false);
        var big = new ShiftItem(null, 45, d, 600, 2800, "오픈", 30, Schedule.Kind.WORK, false);
        for (var it : List.of(neg, big)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveShifts(7, 1, "2026-12",
                    new MonthScheduleCommandDto.SaveShiftsRequest(List.of(it)))).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void attendanceShiftKindChangeIsSkipped() {
        StoreWorker sw = new StoreWorker();
        sw.setId(45);
        LocalDate d = LocalDate.of(2026, 12, 3);
        Schedule cur = shift(123, sw, d, 540, 1080);
        var att = new com.example.moneybug.entity.Attendance();
        att.setSchedule(cur);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(cur));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of(att));

        var res = service.saveShifts(7, 1, "2026-12", new MonthScheduleCommandDto.SaveShiftsRequest(List.of(
                new ShiftItem("123", 45, d, 540, 1080, "오픈", 30, Schedule.Kind.OFF, false))));

        assertThat(res.skipped()).hasSize(1);
        assertThat(cur.getKind()).isEqualTo(Schedule.Kind.WORK);
    }

    @Test
    void jobStoreAllowsOneRunningPerStore() {
        var store = new ScheduleJobStore();
        String id = store.createIfNoneRunning(1);
        assertThat(id).isNotNull();
        assertThat(store.createIfNoneRunning(1)).isNull();
        assertThat(store.createIfNoneRunning(2)).isNotNull();
        store.fail(id, "x");
        assertThat(store.createIfNoneRunning(1)).isNotNull();
    }

    /** 확정된 달: 바뀐 직원에게만 한 건씩 알림, 그대로인 직원은 알림 없음. 자동 채우기는 409. */
    @Test
    void confirmedMonthNotifiesChangedStaffOnly() {
        StoreWorker a = new StoreWorker();
        a.setId(45);
        StoreWorker b = new StoreWorker();
        b.setId(46);
        LocalDate d3 = LocalDate.of(2026, 12, 3), d4 = LocalDate.of(2026, 12, 4);
        Schedule moved = shift(123, a, d3, 540, 1080);
        moved.setBreakMin(30);
        Schedule same = shift(124, b, d4, 540, 1080);
        same.setBreakMin(30);
        MonthSchedule ms = new MonthSchedule();
        ms.setStatus(MonthSchedule.Status.CONFIRMED);
        when(monthRepo.findByStoreIdAndMonth(1, "2026-12")).thenReturn(Optional.of(ms));
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(a, b));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(moved, same));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        // 123 을 a → b 로 넘기고, 124 는 그대로.
        service.saveShifts(7, 1, "2026-12", new MonthScheduleCommandDto.SaveShiftsRequest(List.of(
                item("123", 46, d3, 540, 1080), item("124", 46, d4, 540, 1080))));

        verify(notifier).sendToStaff(any(), eq(a), eq(Notification.Type.SUB), eq("12월 확정 근무표가 바뀌었어요"), eq("12/3"), isNull());
        verify(notifier).sendToStaff(any(), eq(b), eq(Notification.Type.SUB), eq("12월 확정 근무표가 바뀌었어요"), eq("12/3"), isNull());
        verifyNoMoreInteractions(notifier);

        assertThatThrownBy(() -> service.autofill(7, 1, "2026-12"))
                .isInstanceOf(ApiException.class).hasMessageContaining("자동 채우기");
    }

    @Test
    void draftMonthSendsNoNotification() {
        StoreWorker a = new StoreWorker();
        a.setId(45);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(a));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of());
        service.saveShifts(7, 1, "2026-12", new MonthScheduleCommandDto.SaveShiftsRequest(List.of(
                item(null, 45, LocalDate.of(2026, 12, 3), 540, 1080))));
        verifyNoInteractions(notifier);
    }
}
