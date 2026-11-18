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
    ScheduleAiService ai = mock(ScheduleAiService.class);
    StoreAccess access = mock(StoreAccess.class);
    SubstituteRequestRepository subRepo = mock(SubstituteRequestRepository.class);
    SubstituteApplicantRepository applicantRepo = mock(SubstituteApplicantRepository.class);
    StoreChangeHub changeHub = mock(StoreChangeHub.class);
    MonthScheduleService service = new MonthScheduleService(access, monthRepo,
            mock(ScheduleTemplateRepository.class), scheduleRepo, attendanceRepo, workerRepo,
            mock(StorePartRepository.class), mock(OverlapChecker.class), deleter, mock(ShiftService.class),
            ai, mock(StaffWageService.class), changeHub,
            notifier, mock(PlatformTransactionManager.class));

    {
        service.subRepo = subRepo;
        service.applicantRepo = applicantRepo;
    }

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
        old.setBreakMin(30); // FE 는 받은 값 그대로 보낸다
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
    void applyUnknownPlanIsGone() {
        assertThatThrownBy(() -> service.applyPlan(7, 1, "nope"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.GONE);
                    assertThat(e.getCode()).isEqualTo("PLAN_EXPIRED");
                });
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

    @Test
    void briefLocksStoreBeforeCheckingComment() {
        var prep = MonthScheduleComposeTest.prep(true, java.util.Set.of());
        var plan = MonthScheduleComposeTest.plan(prep, MonthScheduleComposeTest.compose(prep));
        MonthSchedule ms = new MonthSchedule();
        ms.setAiComment(plan.comment());
        when(ai.generate(any())).thenReturn(tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree("{\"comment\":\"브리핑\"}"));
        when(monthRepo.findByStoreIdAndMonth(1, "2026-11")).thenReturn(Optional.of(ms));

        service.attachBrief(1, plan);

        var order = inOrder(monthRepo);
        order.verify(monthRepo).lockStore(1);
        order.verify(monthRepo).findByStoreIdAndMonth(1, "2026-11");
        assertThat(ms.getAiComment()).isEqualTo("브리핑\n\n" + plan.comment());
    }

    static StoreWorker worker(int id, StoreWorker.Status status) {
        StoreWorker sw = new StoreWorker();
        sw.setId(id);
        sw.setStatus(status);
        com.example.moneybug.entity.User u = new com.example.moneybug.entity.User();
        u.setName("직원" + id);
        sw.setWorker(u);
        return sw;
    }

    static com.example.moneybug.entity.User user(int id) {
        com.example.moneybug.entity.User u = new com.example.moneybug.entity.User();
        u.setId(id);
        return u;
    }

    /** /me/state 범위: 2026-11-17 기준 지난달 1일(10/1)이 든 주의 월요일 9/28 ~ 2027-02-28. */
    @Test
    void stateWindowMatchesStateService() {
        LocalDate today = LocalDate.of(2026, 11, 17);
        assertThat(StateWindow.shiftsFrom(today)).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(StateWindow.shiftsTo(today)).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(StateWindow.contains(today, LocalDate.of(2026, 9, 27))).isFalse();
        assertThat(StateWindow.contains(today, LocalDate.of(2026, 9, 29))).isTrue();
    }

    /**
     * 보고서 07 [치명]: 두 달 전(오늘 2026-11 이면 9월)을 저장할 때 FE 가 받은 적 없는 앞부분 근무(9/5 연차, 9/10 휴무,
     * 9/15 근무)는 목록에 없어도 지우지 않는다. 받은 근무(9/29)만 통째 저장 대상.
     */
    @Test
    void doesNotDeleteShiftsOutsideStateWindow() {
        java.time.YearMonth ym = java.time.YearMonth.from(TimeUtil.today()).minusMonths(2);
        LocalDate from = StateWindow.shiftsFrom(TimeUtil.today());
        StoreWorker sw = worker(21, StoreWorker.Status.ACTIVE);
        Schedule leave = shift(1, sw, ym.atDay(5), 540, 1080);
        leave.setKind(Schedule.Kind.LEAVE);
        Schedule off = shift(2, sw, ym.atDay(10), 540, 1080);
        off.setKind(Schedule.Kind.OFF);
        Schedule work = shift(3, sw, ym.atDay(15), 540, 1080);
        Schedule last = shift(4, sw, ym.atEndOfMonth(), 540, 1080);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(leave, off, work, last));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        service.saveShifts(7, 1, ym.toString(), new MonthScheduleCommandDto.SaveShiftsRequest(List.of()));

        verify(deleter, never()).delete(leave);
        verify(deleter, never()).delete(off);
        verify(deleter, never()).delete(work);
        // 말일이 범위 안이면(FE 가 받았으면) 목록에서 빠진 것은 지운다.
        verify(deleter, times(ym.atEndOfMonth().isBefore(from) ? 0 : 1)).delete(last);
    }

    /** 보고서 07 [높음]: 연차·휴무·미정도 24시간·휴게 범위를 본다. */
    @Test
    void validatesTimeAndBreakForEveryKind() {
        StoreWorker sw = worker(45, StoreWorker.Status.ACTIVE);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of());
        LocalDate d = LocalDate.of(2026, 12, 2);
        for (var it : List.of(
                new ShiftItem(null, 45, d, 0, 2880, "", 0, Schedule.Kind.LEAVE, false),
                new ShiftItem(null, 45, d, 540, 1080, "", -10000, Schedule.Kind.LEAVE, false),
                new ShiftItem(null, 45, d, 540, 1080, "", 9999, Schedule.Kind.OFF, false),
                new ShiftItem(null, 45, d, 0, 2880, "", 0, Schedule.Kind.WORK, true))) {
            assertThatThrownBy(() -> service.saveShifts(7, 1, "2026-12",
                    new MonthScheduleCommandDto.SaveShiftsRequest(List.of(it)))).isInstanceOf(ApiException.class);
        }
        verify(scheduleRepo, never()).saveAll(any());
    }

    /** 보고서 07 [중간]: 퇴사자 근무는 그대로일 때만 받는다. 날짜를 옮기면 400, 재직자에게 넘기면 받는다. */
    @Test
    void leftWorkerShiftOnlyUnchangedOrHandedOver() {
        StoreWorker left = worker(20, StoreWorker.Status.LEFT);
        StoreWorker sw = worker(21, StoreWorker.Status.ACTIVE);
        Schedule old = shift(446, left, LocalDate.of(2026, 11, 15), 540, 1080);
        old.setBreakMin(30);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(old));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> service.saveShifts(7, 1, "2026-11", new MonthScheduleCommandDto.SaveShiftsRequest(
                List.of(item("446", 20, LocalDate.of(2026, 11, 28), 540, 1080)))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("STAFF_NOT_FOUND"));
        assertThat(old.getWorkDate()).isEqualTo(LocalDate.of(2026, 11, 15));

        service.saveShifts(7, 1, "2026-11", new MonthScheduleCommandDto.SaveShiftsRequest(
                List.of(item("446", 21, LocalDate.of(2026, 11, 28), 540, 1080))));
        assertThat(old.getStoreWorker()).isSameAs(sw);
    }

    /** 보고서 07 [낮음]: DB part null 과 FE "" 는 같은 것 — 확정 달에 바뀐 것 없이 저장하면 알림 없음. */
    @Test
    void nullPartEqualsEmptyPart() {
        StoreWorker sw = worker(21, StoreWorker.Status.ACTIVE);
        Schedule cur = shift(692, sw, LocalDate.of(2026, 12, 9), 540, 600);
        cur.setPart(null);
        cur.setBreakMin(0);
        MonthSchedule ms = new MonthSchedule();
        ms.setStatus(MonthSchedule.Status.CONFIRMED);
        when(monthRepo.findByStoreIdAndMonth(1, "2026-12")).thenReturn(Optional.of(ms));
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(cur));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        service.saveShifts(7, 1, "2026-12", new MonthScheduleCommandDto.SaveShiftsRequest(List.of(
                new ShiftItem("692", 21, LocalDate.of(2026, 12, 9), 540, 600, "", 0, Schedule.Kind.WORK, false))));

        verifyNoInteractions(notifier);
    }

    /** 보고서 07 [낮음]: 확정된 달에 새 시간 미정 근무는 409. 3개월 뒤를 넘는 달은 400. */
    @Test
    void rejectsTbdInConfirmedMonthAndTooFarMonth() {
        StoreWorker sw = worker(21, StoreWorker.Status.ACTIVE);
        MonthSchedule ms = new MonthSchedule();
        ms.setStatus(MonthSchedule.Status.CONFIRMED);
        when(monthRepo.findByStoreIdAndMonth(1, "2026-12")).thenReturn(Optional.of(ms));
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.saveShifts(7, 1, "2026-12", new MonthScheduleCommandDto.SaveShiftsRequest(
                List.of(new ShiftItem(null, 21, LocalDate.of(2026, 12, 5), 540, 1080, "", 60, Schedule.Kind.WORK, true)))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("TBD_IN_CONFIRMED"));

        String far = java.time.YearMonth.from(TimeUtil.today()).plusMonths(4).toString();
        assertThatThrownBy(() -> service.saveShifts(7, 1, far, new MonthScheduleCommandDto.SaveShiftsRequest(List.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("MONTH_TOO_FAR"));
        assertThatThrownBy(() -> service.autofill(7, 1, far))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("MONTH_TOO_FAR"));
    }

    /** 보고서 13 [높음]: 월 저장으로 담당자·시간이 바뀌면 진행 중 대타 요청을 취소하고 요청자·지원자에게 알린다. */
    @Test
    void changedShiftCancelsPendingSubstitute() {
        StoreWorker a = worker(36, StoreWorker.Status.ACTIVE);
        StoreWorker b = worker(35, StoreWorker.Status.ACTIVE);
        Schedule cur = shift(48, a, LocalDate.of(2026, 12, 20), 720, 960);
        cur.setBreakMin(30);
        var r = new com.example.moneybug.entity.SubstituteRequest();
        r.setId(21);
        r.setRequester(user(100));
        r.setTaker(user(101));
        r.setStatus(com.example.moneybug.entity.SubstituteRequest.Status.ACCEPTED);
        var ap = new com.example.moneybug.entity.SubstituteApplicant();
        ap.setApplicant(user(101));
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(a, b));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(cur));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());
        when(subRepo.findByScheduleIdAndStatusIn(eq(48), anyCollection())).thenReturn(List.of(r));
        when(applicantRepo.findByRequestId(21)).thenReturn(List.of(ap));

        service.saveShifts(7, 1, "2026-12", new MonthScheduleCommandDto.SaveShiftsRequest(List.of(
                item("48", 35, LocalDate.of(2026, 12, 20), 720, 960))));

        assertThat(r.getStatus()).isEqualTo(com.example.moneybug.entity.SubstituteRequest.Status.CANCELLED);
        verify(notifier).send(any(), eq(r.getRequester()), eq(Notification.Type.SUB), any(), eq("12/20 12:00–16:00"), eq(21));
        verify(notifier).send(any(), eq(ap.getApplicant()), eq(Notification.Type.SUB), any(), any(), eq(21));
        verify(notifier, times(2)).send(any(), any(), any(), any(), any(), any());
    }

    /** 바뀐 것 없는 근무의 대타 요청은 그대로 둔다. */
    @Test
    void unchangedShiftKeepsSubstitute() {
        StoreWorker a = worker(36, StoreWorker.Status.ACTIVE);
        Schedule cur = shift(48, a, LocalDate.of(2026, 12, 20), 720, 960);
        cur.setBreakMin(30);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(a));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(cur));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        service.saveShifts(7, 1, "2026-12", new MonthScheduleCommandDto.SaveShiftsRequest(List.of(
                item("48", 36, LocalDate.of(2026, 12, 20), 720, 960))));

        verifyNoInteractions(subRepo);
    }

    /** 보고서 07·15 [중간]: 확정된 달 삭제는 근무가 지워진 재직 직원에게 알린다. 작성 중이면 알림 없음. */
    @Test
    void deletingConfirmedMonthNotifiesStaff() {
        StoreWorker sw = worker(21, StoreWorker.Status.ACTIVE);
        StoreWorker left = worker(20, StoreWorker.Status.LEFT);
        Schedule s1 = shift(1, sw, LocalDate.of(2026, 12, 3), 540, 1080);
        Schedule s2 = shift(2, sw, LocalDate.of(2026, 12, 4), 540, 1080);
        Schedule s3 = shift(3, left, LocalDate.of(2026, 12, 5), 540, 1080);
        MonthSchedule ms = new MonthSchedule();
        ms.setStatus(MonthSchedule.Status.CONFIRMED);
        when(monthRepo.findByStoreIdAndMonth(1, "2026-12")).thenReturn(Optional.of(ms));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(s1, s2, s3));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        service.delete(7, 1, "2026-12");

        verify(deleter).delete(s1);
        verify(monthRepo).delete(ms);
        verify(notifier).sendToStaff(any(), eq(sw), eq(Notification.Type.SUB), eq("12월 확정 근무표가 바뀌었어요"),
                eq("12/3, 12/4"), isNull());
        verifyNoMoreInteractions(notifier);
    }

    /** 보고서 08·24: 만들기·자동 채우기·저장·확정·삭제는 어떤 읽기보다 먼저 매장 잠금을 잡는다. */
    @Test
    void storeLockComesFirst() {
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of());
        String m = java.time.YearMonth.from(TimeUtil.today()).plusMonths(1).toString();
        List<Runnable> calls = List.of(
                () -> service.create(7, 1, new MonthScheduleCommandDto.CreateRequest(m, false)),
                () -> service.autofill(7, 1, m),
                () -> service.saveShifts(7, 1, m, new MonthScheduleCommandDto.SaveShiftsRequest(List.of())),
                () -> service.confirm(7, 1, m),
                () -> service.delete(7, 1, m));
        for (Runnable call : calls) {
            clearInvocations(monthRepo, access, scheduleRepo, workerRepo);
            try {
                call.run();
            } catch (RuntimeException ignored) {
                // confirm 은 메타가 없어 404 — 순서만 본다
            }
            var order = inOrder(monthRepo, access);
            order.verify(monthRepo).lockStore(1);
            order.verify(access).requireOwner(7, 1);
        }

    @Test
    void briefFailureIsShownAndSignalled() {
        var prep = MonthScheduleComposeTest.prep(true, java.util.Set.of());
        var plan = MonthScheduleComposeTest.plan(prep, MonthScheduleComposeTest.compose(prep));
        MonthSchedule ms = new MonthSchedule();
        ms.setAiComment(plan.comment());
        when(ai.generate(any())).thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "AI_FAILED", "AI 서버에 연결하지 못했어요."));
        when(monthRepo.findByStoreIdAndMonth(1, "2026-11")).thenReturn(Optional.of(ms));

        service.attachBrief(1, plan);

        assertThat(ms.getAiComment()).isEqualTo(MonthScheduleService.BRIEF_FAILED + "\n\n" + plan.comment());
        verify(changeHub).bump(1);
    }

    @Test
    void confirmedConflictKeepsPlanAndStaffChangeIsStale() {
        var prep = MonthScheduleComposeTest.prep(true, java.util.Set.of());
        var base = MonthScheduleComposeTest.plan(prep, MonthScheduleComposeTest.compose(prep));
        var plan = new MonthScheduleService.Plan("p1", java.time.YearMonth.from(LocalDate.now().plusMonths(1)),
                base.policy(), base.priority(), List.of(new com.example.moneybug.service.FixedShiftPlanner.Planned(
                99, LocalDate.now().plusMonths(1).withDayOfMonth(1), 480, 720, "오픈", 30)),
                List.of(), List.of(), base.staff(), base.names(), base.comment(), java.time.Instant.now());
        String month = plan.ym().toString();

        MonthSchedule confirmed = new MonthSchedule();
        confirmed.setStatus(MonthSchedule.Status.CONFIRMED);
        when(monthRepo.findByStoreIdAndMonth(1, month)).thenReturn(Optional.of(confirmed));
        service.plans.put(1, plan);
        for (int i = 0; i < 2; i++) { // 두 번째도 410 이 아니라 같은 409
            assertThatThrownBy(() -> service.applyPlan(7, 1, "p1"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ALREADY_CONFIRMED"));
        }

        // 확정이 풀렸는데 계획 속 직원(99)이 퇴사 → 410 PLAN_STALE, 아무것도 지우지 않음.
        when(monthRepo.findByStoreIdAndMonth(1, month)).thenReturn(Optional.empty());
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of());
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.applyPlan(7, 1, "p1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PLAN_STALE"));
        verifyNoInteractions(deleter);
    }
}
