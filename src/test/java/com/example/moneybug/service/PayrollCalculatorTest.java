package com.example.moneybug.service;

import com.example.moneybug.dto.PayslipDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.ScheduleRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayrollCalculatorTest {

    private final ScheduleRepository schedules = mock(ScheduleRepository.class);
    private final AttendanceRepository attendances = mock(AttendanceRepository.class);
    private final PayrollCalculator calc = new PayrollCalculator(schedules, attendances);

    private static Schedule shift(int id, LocalDate d, int startH, int endH, int breakMin, Schedule.Kind kind) {
        Schedule s = new Schedule();
        s.setId(id);
        s.setWorkDate(d);
        s.setStartMin(startH * 60);
        s.setEndMin(endH * 60);
        s.setBreakMin(breakMin);
        s.setKind(kind);
        return s;
    }

    private static Attendance att(Schedule s, LocalDateTime in, LocalDateTime out) {
        Attendance a = new Attendance();
        a.setSchedule(s);
        a.setActualStart(in);
        a.setActualEnd(out);
        return a;
    }

    private PayslipDto.Response run(boolean premium, boolean projected, LocalDateTime now,
                                    List<Schedule> shifts, List<Attendance> atts) {
        Store store = new Store();
        store.setUnderFive(!premium);
        StoreWorker sw = new StoreWorker();
        sw.setId(1);
        sw.setHourlyWage(10000);
        sw.setPremium(premium);
        sw.setWeeklyHolidayAuto(false);
        when(schedules.findByWorkerBetween(anyInt(), any(), any())).thenReturn(shifts);
        when(attendances.findByScheduleIdIn(anyCollection())).thenReturn(atts);
        return calc.calculate(store, sw, YearMonth.of(2026, 5), projected, now);
    }

    @Test
    void 퇴근_미기록은_0원이고_건수로_센다() {
        Schedule s = shift(1, LocalDate.of(2026, 5, 11), 10, 16, 0, Schedule.Kind.WORK);
        Attendance a = att(s, LocalDateTime.of(2026, 5, 11, 10, 0), null);
        PayslipDto.Response r = run(false, false, LocalDateTime.of(2026, 5, 20, 12, 0), List.of(s), List.of(a));
        assertEquals(0, r.gross());
        assertEquals(1, r.missingClockOut());
    }

    @Test
    void 근무가_휴게보다_짧으면_0분() {
        Schedule s = shift(1, LocalDate.of(2026, 5, 11), 14, 15, 30, Schedule.Kind.WORK);
        Attendance a = att(s, LocalDateTime.of(2026, 5, 11, 14, 0), LocalDateTime.of(2026, 5, 11, 14, 20));
        assertEquals(0, run(false, false, LocalDateTime.of(2026, 5, 20, 12, 0), List.of(s), List.of(a)).minutes());
    }

    @Test
    void 연차는_가산_제외_야간분은_실근로분_이하() {
        Schedule leave = shift(1, LocalDate.of(2026, 5, 12), 18, 3, 60, Schedule.Kind.LEAVE);
        Schedule work = shift(2, LocalDate.of(2026, 5, 11), 22, 2, 60, Schedule.Kind.WORK);
        Attendance a = att(work, LocalDateTime.of(2026, 5, 11, 22, 0), LocalDateTime.of(2026, 5, 12, 2, 0));
        PayslipDto.Response r = run(true, false, LocalDateTime.of(2026, 5, 20, 12, 0), List.of(leave, work), List.of(a));
        assertEquals(15000, r.premium()); // 근무 4h-휴게 1h=180분, 야간 min(240,180) × 10,000 × 0.5
    }

    @Test
    void 오늘_이미_끝난_연차는_확정() {
        Schedule leave = shift(1, LocalDate.of(2026, 5, 20), 9, 13, 0, Schedule.Kind.LEAVE);
        PayslipDto.Response r = run(false, false, LocalDateTime.of(2026, 5, 20, 15, 0), List.of(leave), List.of());
        assertEquals(40000, r.gross());
    }
}
