package com.example.moneybug.service;

import com.example.moneybug.dto.AttendanceViewDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttendanceServiceRulesTest {

    private final AttendanceRepository repo = mock(AttendanceRepository.class);
    private final ScheduleRepository schedules = mock(ScheduleRepository.class);
    private final StoreWorkerRepository workers = mock(StoreWorkerRepository.class);
    private final StoreAccess access = mock(StoreAccess.class);
    private final QrTokenService qr = mock(QrTokenService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AttendanceService svc =
            new AttendanceService(repo, schedules, workers, access, null, mock(NotificationPublisher.class), qr, users);

    private static AttendanceViewDto.EditRequest edit(LocalDateTime in, LocalDateTime out) {
        return new AttendanceViewDto.EditRequest(in, out, "사유");
    }

    private String codeOf(Runnable r) {
        return assertThrows(ApiException.class, r::run).getCode();
    }

    /** userId 1, staffId 10, store "A매장" 의 근무. */
    private static Schedule shift(LocalDate date, int startMin, int endMin) {
        Store store = new Store();
        store.setId(1);
        store.setStoreName("A매장");
        User u = new User();
        u.setId(1);
        StoreWorker sw = new StoreWorker();
        sw.setId(10);
        sw.setStore(store);
        sw.setWorker(u);
        sw.setHourlyWage(10_000);
        Schedule s = new Schedule();
        s.setId(100);
        s.setStoreWorker(sw);
        s.setWorkDate(date);
        s.setStartMin(startMin);
        s.setEndMin(endMin);
        return s;
    }

    private static Attendance open(Schedule s, LocalDateTime start) {
        Attendance a = new Attendance();
        a.setId(5);
        a.setSchedule(s);
        a.setActualStart(start);
        return a;
    }

    /** 지금이 근무 한가운데인 근무(자정 근처에서도 안전하도록 시작을 지금 −1h 분 단위로). */
    private static Schedule runningShift() {
        LocalDateTime start = TimeUtil.now().minusHours(1);
        LocalDateTime end = start.plusHours(3);
        int startMin = start.getHour() * 60 + start.getMinute();
        int endMin = end.getHour() * 60 + end.getMinute();
        return shift(start.toLocalDate(), startMin, endMin);
    }

    @Test
    void clockOutTooLateAfterShiftEndRejected() {
        Schedule s = shift(TimeUtil.now().toLocalDate().minusDays(2), 9 * 60, 18 * 60);
        when(repo.findOpenByUserAndStore(1, 1)).thenReturn(List.of(open(s, s.getWorkDate().atTime(9, 0))));
        assertEquals("CLOCK_OUT_EXPIRED", codeOf(() -> svc.clockOut(1, 1)));
    }

    @Test
    void clockOutLocksUserBeforeReadingOpenRecord() {
        Schedule s = runningShift();
        when(repo.findOpenByUserAndStore(1, 1)).thenReturn(List.of(open(s, TimeUtil.start(s))));
        AttendanceViewDto.View v = svc.clockOut(1, 1);
        InOrder order = inOrder(users, repo);
        order.verify(users).findByIdForUpdate(1);
        order.verify(repo).findOpenByUserAndStore(1, 1);
        assertEquals(0, v.clockOut().getNano() % 1000, "마이크로초로 자른다");
    }

    @Test
    void clockInLocksUserFirstAndNamesStoreOfOpenRecord() {
        Schedule s = runningShift();
        StoreWorker me = s.getStoreWorker();
        when(access.requireActiveStaff(1, 1)).thenReturn(me);
        when(qr.verify(eq(1), eq("t"), any())).thenReturn(true);
        when(schedules.findByIdAndStore(100, 1)).thenReturn(Optional.of(s));
        when(repo.findByScheduleId(100)).thenReturn(Optional.empty());
        Schedule other = shift(s.getWorkDate(), s.getStartMin(), s.getEndMin());
        other.getStoreWorker().getStore().setStoreName("B매장");
        when(repo.findOpenByUser(1)).thenReturn(List.of(open(other, TimeUtil.now())));

        ApiException e = assertThrows(ApiException.class,
                () -> svc.clockIn(1, 1, 100, new AttendanceViewDto.ClockInRequest("t")));
        assertEquals("ALREADY_CLOCKED_IN", e.getCode());
        assertTrue(e.getMessage().contains("B매장"), e.getMessage());
        InOrder order = inOrder(users, access);
        order.verify(users).findByIdForUpdate(1);
        order.verify(access).requireActiveStaff(1, 1);
    }

    @Test
    void clockInReturnsMicrosecondPrecision() {
        Schedule s = runningShift();
        when(access.requireActiveStaff(1, 1)).thenReturn(s.getStoreWorker());
        when(qr.verify(eq(1), eq("t"), any())).thenReturn(true);
        when(schedules.findByIdAndStore(100, 1)).thenReturn(Optional.of(s));
        when(repo.findByScheduleId(100)).thenReturn(Optional.empty());
        when(repo.findOpenByUser(1)).thenReturn(List.of());
        AttendanceViewDto.View v = svc.clockIn(1, 1, 100, new AttendanceViewDto.ClockInRequest("t"));
        assertEquals(0, v.clockIn().getNano() % 1000);
    }

    @Test
    void editRejectsFutureAndOver24h() {
        when(access.requireOwner(any(), any())).thenReturn(new Store());
        Attendance a = new Attendance();
        LocalDateTime base = TimeUtil.now().minusDays(3);
        a.setActualStart(base);
        when(repo.findByIdAndStore(5, 1)).thenReturn(Optional.of(a));
        assertEquals("FUTURE_TIME",
                codeOf(() -> svc.edit(1, 1, 5, edit(TimeUtil.now().minusHours(2), TimeUtil.now().plusHours(3)))));
        assertEquals("INVALID_CLOCK_RANGE",
                codeOf(() -> svc.edit(1, 1, 5, edit(base, base.plusHours(25)))));
    }

    @Test
    void editRejectsTimesFarFromShift() {
        when(access.requireOwner(any(), any())).thenReturn(new Store());
        LocalDate day = TimeUtil.today().minusDays(7);
        Schedule night = shift(day, 22 * 60, 2 * 60); // 22:00–02:00(+1)
        when(repo.findByIdAndStore(5, 1)).thenReturn(Optional.of(open(night, day.atTime(22, 0))));
        // FE 가 자정 이후 출근을 근무 날짜에 붙여 보낸 값 → 400 (전에는 0원 정상 근무로 저장)
        assertEquals("CLOCK_OUT_OF_SHIFT",
                codeOf(() -> svc.edit(1, 1, 5, edit(day.atTime(0, 10), day.atTime(2, 20)))));
        // 다른 날짜
        assertEquals("CLOCK_OUT_OF_SHIFT",
                codeOf(() -> svc.edit(1, 1, 5, edit(day.plusDays(2).atTime(9, 0), null))));
        // 올바른 다음 날 시각은 통과
        AttendanceViewDto.View v = svc.edit(1, 1, 5, edit(day.plusDays(1).atTime(0, 10), day.plusDays(1).atTime(2, 20)));
        assertEquals(130, v.lateMinutes());
    }

    @Test
    void createManualRules() {
        LocalDate past = TimeUtil.today().minusDays(2);
        Schedule ended = shift(past, 9 * 60, 18 * 60);
        Schedule future = shift(TimeUtil.today().plusDays(5), 9 * 60, 18 * 60);
        future.setId(101);
        when(schedules.findByIdAndStore(100, 1)).thenReturn(Optional.of(ended));
        when(schedules.findByIdAndStore(101, 1)).thenReturn(Optional.of(future));

        assertEquals("CLOCK_OUT_REQUIRED",
                codeOf(() -> svc.createManual(9, 1, 100, edit(past.atTime(9, 0), null))));
        assertEquals("SHIFT_NOT_STARTED",
                codeOf(() -> svc.createManual(9, 1, 101, edit(TimeUtil.now().minusHours(1), null))));
        assertEquals("CLOCK_OUT_OF_SHIFT",
                codeOf(() -> svc.createManual(9, 1, 100,
                        edit(past.plusDays(1).atTime(9, 0), past.plusDays(1).atTime(18, 0)))));
        verify(repo, never()).save(any());

        AttendanceViewDto.View ok = svc.createManual(9, 1, 100, edit(past.atTime(9, 0), past.atTime(18, 0)));
        assertEquals(past.atTime(18, 0), ok.clockOut());
    }

    @Test
    void createManualOpenRecordBlockedWhenWorkerAlreadyClockedIn() {
        Schedule s = runningShift();
        when(schedules.findByIdAndStore(100, 1)).thenReturn(Optional.of(s));
        when(repo.findOpenByUser(1)).thenReturn(List.of(open(shift(s.getWorkDate(), 0, 60), TimeUtil.now())));
        assertEquals("ALREADY_CLOCKED_IN",
                codeOf(() -> svc.createManual(9, 1, 100, edit(TimeUtil.start(s), null))));
        verify(users).findByIdForUpdate(1);
    }

    @Test
    void todayDoesNotReportClosedRecordAsOpen() {
        Schedule s = runningShift();
        Attendance done = open(s, TimeUtil.start(s));
        done.setActualEnd(TimeUtil.now().minusMinutes(1));
        when(repo.findOpenByUser(1)).thenReturn(List.of());
        when(workers.findByWorkerIdAndStatusIn(eq(1), any())).thenReturn(List.of(s.getStoreWorker()));
        when(schedules.findByWorkerBetween(anyInt(), any(), any())).thenReturn(List.of(s));
        when(repo.findByScheduleIdIn(any())).thenReturn(List.of(done));
        var today = svc.today(1);
        assertEquals(false, today.working());
        assertNull(today.openAttendance());
    }
}
