package com.example.moneybug.service;

import com.example.moneybug.dto.SchedulePlanDto;
import com.example.moneybug.dto.ShiftCommandDto;
import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StorePart;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.MonthScheduleRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.service.FixedShiftPlanner.StoreHours;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 레거시 자동 배정·근무표 만들기·템플릿·확정 (QA 2026-11-17 05/08/15/23/25). DB 없이. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShiftLegacyScheduleTest {

    @Mock ScheduleRepository scheduleRepository;
    @Mock StoreWorkerRepository storeWorkerRepository;
    @Mock MonthScheduleRepository monthScheduleRepository;
    @Mock StoreAccess access;
    @Mock NotificationPublisher notifier;
    @Mock StoreService storeService;
    @InjectMocks ShiftService service;

    static final StoreHours HOURS = new StoreHours(480, 1320, 600, 1200); // 평일 08–22, 주말·공휴일 10–20
    static final LocalDate MON = LocalDate.of(2027, 3, 8); // 평일 월요일

    static StorePart part(String name, int start, int end) {
        StorePart p = new StorePart();
        p.setName(name);
        p.setStartMin(start);
        p.setEndMin(end);
        p.setBreakMin(0);
        return p;
    }

    /** 매일 [start,end] 추가 근무 가능한 알바. */
    static StoreWorker worker(int id, StoreWorker.Kind kind, int start, int end) {
        StoreWorker sw = new StoreWorker();
        sw.setId(id);
        sw.setKind(kind);
        StaffPrefs.WorkHours[] week = new StaffPrefs.WorkHours[7];
        Arrays.fill(week, new StaffPrefs.WorkHours(start, end));
        StaffPrefs.setAvailHours(sw, Arrays.asList(week));
        return sw;
    }

    static Schedule shift(StoreWorker sw, LocalDate d, int start, int end, String part) {
        Schedule s = new Schedule();
        s.setStoreWorker(sw);
        s.setWorkDate(d);
        s.setStartMin(start);
        s.setEndMin(end);
        s.setPart(part);
        s.setBreakMin(0);
        s.setKind(Schedule.Kind.WORK);
        return s;
    }

    // ---------------------------------------------------------------- fill

    @Test
    @Timeout(2)
    void hugeNeedStopsAtOnceWhenNobodyIsLeft() {
        var fill = ShiftService.fill(HOURS, List.of(), List.of(part("오픈", 480, 720)), List.of(1_000_000_000),
                List.of(MON, MON.plusDays(1)), Set.of(), null, true, new ArrayList<>());
        assertThat(fill.added()).isEmpty();
        assertThat(fill.unfilled()).isEqualTo(2_000_000_000);
    }

    @Test
    void skipsOnlyTheEmptySlotNotTheRestOfTheWeek() {
        StoreWorker a = worker(1, StoreWorker.Kind.PART_TIMER, 720, 1020); // 미들만 가능
        var fill = ShiftService.fill(HOURS, List.of(a), List.of(part("오픈", 480, 720), part("미들", 720, 1020)),
                List.of(1, 1), List.of(MON, MON.plusDays(1)), Set.of(), null, true, new ArrayList<>());
        assertThat(fill.unfilled()).isEqualTo(2);
        assertThat(fill.added()).extracting(Schedule::getPart).containsExactly("미들", "미들");
    }

    @Test
    void clipsToWeekendAndHolidayHours() {
        StoreWorker a = worker(1, StoreWorker.Kind.PART_TIMER, 0, 1440);
        LocalDate samiljeol = LocalDate.of(2027, 3, 1); // 공휴일(월)
        var fill = ShiftService.fill(HOURS, List.of(a), List.of(part("오픈", 480, 720)), List.of(1),
                List.of(samiljeol), Set.of(), null, true, new ArrayList<>());
        assertThat(fill.added()).singleElement().satisfies(s -> {
            assertThat(s.getStartMin()).isEqualTo(600);
            assertThat(s.getEndMin()).isEqualTo(720);
        });
    }

    @Test
    void needsFixedPartOrAvailableHours() {
        StoreWorker none = new StoreWorker(); // 고정 파트·가능 시간 없음
        none.setId(1);
        none.setKind(StoreWorker.Kind.PART_TIMER);
        StoreWorker fixed = new StoreWorker();
        fixed.setId(2);
        fixed.setKind(StoreWorker.Kind.PART_TIMER);
        StaffPrefs.setFixedParts(fixed, List.of(List.of("오픈"), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of()));
        var fill = ShiftService.fill(HOURS, List.of(none, fixed), List.of(part("오픈", 480, 720)), List.of(2),
                List.of(MON, MON.plusDays(1)), Set.of(), null, true, new ArrayList<>());
        assertThat(fill.added()).singleElement().satisfies(s -> {
            assertThat(s.getStoreWorker()).isSameAs(fixed);
            assertThat(s.getWorkDate()).isEqualTo(MON);
        });
        assertThat(fill.unfilled()).isEqualTo(3);
    }

    @Test
    void minorGetsNoNightOrLongShift() {
        StoreWorker minor = worker(1, StoreWorker.Kind.MINOR, 0, 1440);
        List<StorePart> parts = List.of(part("종일", 540, 1320), part("마감", 1200, 1320), part("야간", 1320, 1560));
        StoreHours late = new StoreHours(480, 1560, 480, 1560);
        var fill = ShiftService.fill(late, List.of(minor), parts, List.of(1, 1, 1), List.of(MON), Set.of(), null,
                true, new ArrayList<>());
        assertThat(fill.added()).singleElement().extracting(Schedule::getPart).isEqualTo("마감");
    }

    @Test
    void skipsDayWithLeaveOrOff() {
        StoreWorker a = worker(1, StoreWorker.Kind.PART_TIMER, 0, 1440);
        Schedule off = shift(a, MON, 480, 720, null);
        off.setKind(Schedule.Kind.OFF);
        var fill = ShiftService.fill(HOURS, List.of(a), List.of(part("미들", 720, 1020)), List.of(1), List.of(MON),
                Set.of(), null, true, new ArrayList<>(List.of(off)));
        assertThat(fill.added()).isEmpty();
    }

    // ---------------------------------------------------------------- 검증

    @Test
    void needAbove99IsRejected() {
        assertThatThrownBy(() -> ShiftService.requireNeeds(List.of(1, 100)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_NEED"));
        ShiftService.requireNeeds(List.of(0, 99));
    }

    @Test
    void hugeYearIsInvalidMonth() {
        assertThatThrownBy(() -> ShiftService.yearMonth(1_000_000_000, 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_MONTH"));
        assertThatThrownBy(() -> ShiftService.yearMonth(1999, 1)).isInstanceOf(ApiException.class);
    }

    @Test
    void planBeyondThreeMonthsIsTooFar() {
        when(access.requireOwner(7, 1)).thenReturn(new Store());
        LocalDate far = TimeUtil.today().plusMonths(5);
        assertThatThrownBy(() -> service.planSchedule(7, 1, new SchedulePlanDto.Request(far, far, List.of(1),
                null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("MONTH_TOO_FAR"));
    }

    // ---------------------------------------------------------------- 확정·알림

    @Test
    void legacyConfirmReallyConfirmsOnce() {
        Store store = new Store();
        store.setId(1);
        when(access.requireOwner(7, 1)).thenReturn(store);
        MonthSchedule meta = new MonthSchedule();
        meta.setMonth("2026-12");
        when(monthScheduleRepository.findByStoreIdAndMonth(1, "2026-12")).thenReturn(Optional.of(meta));

        service.confirm(7, 1, new ShiftCommandDto.ConfirmRequest(2026, 12));
        service.confirm(7, 1, new ShiftCommandDto.ConfirmRequest(2026, 12));

        assertThat(meta.getStatus()).isEqualTo(MonthSchedule.Status.CONFIRMED);
        verify(notifier, times(1)).sendToActiveStaff(eq(store), any(), eq(Notification.Type.SUB),
                eq("12월 근무표가 확정됐어요"), any(), any());
        verify(monthScheduleRepository, times(2)).lockStore(1);
    }

    @Test
    void legacyConfirmCreatesConfirmedMetaWhenMissing() {
        Store store = new Store();
        store.setId(1);
        when(access.requireOwner(7, 1)).thenReturn(store);
        YearMonth ym = YearMonth.from(TimeUtil.today());
        when(monthScheduleRepository.findByStoreIdAndMonth(1, ym.toString())).thenReturn(Optional.empty());

        service.confirm(7, 1, new ShiftCommandDto.ConfirmRequest(ym.getYear(), ym.getMonthValue()));

        verify(monthScheduleRepository).save(argThat(m -> m.getStatus() == MonthSchedule.Status.CONFIRMED
                && m.getMonth().equals(ym.toString())));
    }

    @Test
    void templateCopyIntoConfirmedMonthNotifiesOnlyThere() {
        Store store = new Store();
        store.setId(1);
        when(access.requireOwner(7, 1)).thenReturn(store);
        LocalDate today = TimeUtil.today();
        StoreWorker sw = worker(5, StoreWorker.Kind.PART_TIMER, 0, 1440);
        sw.setStatus(StoreWorker.Status.ACTIVE);
        when(storeWorkerRepository.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        // 지난주 같은 요일 근무 → 오늘로 복사
        when(scheduleRepository.findByStoreBetween(eq(1), any(), any()))
                .thenReturn(List.of(shift(sw, today.minusDays(7), 540, 780, "오픈")));
        MonthSchedule confirmed = new MonthSchedule();
        confirmed.setStatus(MonthSchedule.Status.CONFIRMED);
        when(monthScheduleRepository.findByStoreIdAndMonth(eq(1), anyString())).thenReturn(Optional.of(confirmed));

        var res = service.applyTemplate(7, 1, new ShiftCommandDto.TemplateRequest(List.of(1), null));
        assertThat(res.copied()).isZero(); // copyLastWeek 생략 = false
        verify(notifier, never()).sendToStaff(any(), any(), any(), anyString(), anyString(), any());

        res = service.applyTemplate(7, 1, new ShiftCommandDto.TemplateRequest(List.of(1), true));
        assertThat(res.copied()).isEqualTo(1);
        verify(notifier).sendToStaff(eq(store), eq(sw), eq(Notification.Type.SUB),
                eq(today.getMonthValue() + "월 확정 근무표가 바뀌었어요"), anyString(), any());

        confirmed.setStatus(MonthSchedule.Status.DRAFT);
        clearInvocations(notifier);
        when(scheduleRepository.findByStoreBetween(eq(1), any(), any()))
                .thenReturn(List.of(shift(sw, today.minusDays(7), 540, 780, "오픈")));
        service.applyTemplate(7, 1, new ShiftCommandDto.TemplateRequest(List.of(1), true));
        verifyNoInteractions(notifier);
    }
}
