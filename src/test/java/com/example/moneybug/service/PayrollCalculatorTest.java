package com.example.moneybug.service;

import com.example.moneybug.dto.PayslipDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.Contract;
import com.example.moneybug.entity.StaffWage;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.ContractRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StaffWageRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayrollCalculatorTest {

    private final ScheduleRepository schedules = mock(ScheduleRepository.class);
    private final AttendanceRepository attendances = mock(AttendanceRepository.class);
    private final StaffWageRepository wageRepo = mock(StaffWageRepository.class);
    private final ContractRepository contractRepo = mock(ContractRepository.class);
    private final PayrollCalculator calc = new PayrollCalculator(schedules, attendances, wageRepo, contractRepo);

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

    // ───── QA 2026-11-17 보고서 04·18·25 숫자 ─────

    private static final LocalDateTime NOV17_23 = LocalDateTime.of(2026, 11, 17, 23, 0);

    private static Schedule shiftMin(int id, LocalDate d, int startMin, int endMin, int breakMin) {
        Schedule s = shift(id, d, 0, 0, breakMin, Schedule.Kind.WORK);
        s.setStartMin(startMin);
        s.setEndMin(endMin);
        return s;
    }

    private static Attendance done(Schedule s) {
        return att(s, TimeUtil.start(s), TimeUtil.end(s));
    }

    private static StaffWage wage(int id, LocalDate from, int w) {
        StaffWage r = new StaffWage();
        r.setId(id);
        r.setEffectiveFrom(from);
        r.setWage(w);
        return r;
    }

    private StoreWorker worker(int wage, boolean holiday, boolean premium) {
        StoreWorker sw = new StoreWorker();
        sw.setId(7);
        sw.setHourlyWage(wage);
        sw.setWeeklyHolidayAuto(holiday);
        sw.setPremium(premium);
        return sw;
    }

    private PayslipDto.Response run(StoreWorker sw, boolean over5, YearMonth ym, boolean projected,
                                    LocalDateTime now, List<Schedule> shifts, List<Attendance> atts) {
        Store store = new Store();
        store.setUnderFive(!over5);
        when(schedules.findByWorkerBetween(anyInt(), any(), any())).thenReturn(shifts);
        when(attendances.findByScheduleIdIn(anyCollection())).thenReturn(atts);
        return calc.calculate(store, sw, ym, projected, now);
    }

    @Test
    void 지난_근무는_그날_시급으로_계산한다_보고서04() {
        StoreWorker sw = worker(20000, false, false); // 11/17 에 10320 → 20000
        when(wageRepo.findByStoreWorkerIdOrderByEffectiveFromAscIdAsc(7)).thenReturn(List.of(
                wage(1, LocalDate.of(2000, 1, 1), 10320), wage(2, LocalDate.of(2026, 11, 17), 20000)));
        Schedule s = shiftMin(1, LocalDate.of(2026, 11, 10), 540, 1080, 60);
        PayslipDto.Response r = run(sw, false, YearMonth.of(2026, 11), false, NOV17_23, List.of(s), List.of(done(s)));
        assertEquals(82560, r.base()); // 480분 × 10,320 (새 시급이면 160,000)
    }

    @Test
    void 주휴는_일요일_시급() {
        StoreWorker sw = worker(13000, true, false);
        when(wageRepo.findByStoreWorkerIdOrderByEffectiveFromAscIdAsc(7)).thenReturn(List.of(
                wage(1, LocalDate.of(2000, 1, 1), 12000), wage(2, LocalDate.of(2026, 10, 18), 13000)));
        List<Schedule> shifts = new ArrayList<>();
        for (int d = 12; d <= 16; d++) shifts.add(shiftMin(d, LocalDate.of(2026, 10, d), 600, 840, 0)); // 4h × 5일 = 20h
        PayslipDto.Response r = run(sw, false, YearMonth.of(2026, 10), false, NOV17_23, shifts,
                shifts.stream().map(PayrollCalculatorTest::done).toList());
        assertEquals(5 * 48000, r.base()); // 근무일(10/12~16)은 12,000
        assertEquals(52000, r.weeklyHoliday()); // 20/40 × 8 × 13,000 (일요일 10/18 시급)
    }

    @Test
    void 결근한_주는_주휴_없음_보고서18() {
        StoreWorker sw = worker(12000, true, false);
        List<Schedule> shifts = new ArrayList<>();
        for (int d = 12; d <= 17; d++) shifts.add(shiftMin(d, LocalDate.of(2026, 10, d), 600, 840, 0));
        List<Attendance> atts = new ArrayList<>();
        atts.add(att(shifts.get(0), LocalDateTime.of(2026, 10, 12, 10, 0), null)); // 10/12 출근만
        for (int i = 2; i < 6; i++) atts.add(done(shifts.get(i)));                 // 10/13 결근
        PayslipDto.Response r = run(sw, false, YearMonth.of(2026, 10), false, NOV17_23, shifts, atts);
        assertEquals(0, r.weeklyHoliday());
        assertEquals(1, r.missingClockOut());

        atts.add(done(shifts.get(1))); // 10/13 출근하면 개근: 소정 6 × 4h = 24h → 24/40 × 8 × 12,000
        assertEquals(57600, run(sw, false, YearMonth.of(2026, 10), false, NOV17_23, shifts, atts).weeklyHoliday());
    }

    @Test
    void 안_끝난_주는_확정_급여에_주휴_없음_보고서18() {
        StoreWorker sw = worker(12000, true, false);
        Schedule a = shiftMin(1, LocalDate.of(2026, 11, 16), 600, 840, 0);
        Schedule b = shiftMin(2, LocalDate.of(2026, 11, 16), 900, 1380, 0);
        Schedule c = shiftMin(3, LocalDate.of(2026, 11, 17), 480, 780, 0); // 소정 1020분
        List<Schedule> shifts = List.of(a, b, c);
        List<Attendance> atts = List.of(done(a), done(b), done(c));
        assertEquals(0, run(sw, false, YearMonth.of(2026, 11), false, NOV17_23, shifts, atts).weeklyHoliday());
        assertEquals(40800, run(sw, false, YearMonth.of(2026, 11), true, NOV17_23, shifts, atts).weeklyHoliday());
    }

    @Test
    void 퇴사_뒤_끝나는_주는_주휴_없음_보고서18() {
        StoreWorker sw = worker(12000, true, false);
        Schedule a = shiftMin(1, LocalDate.of(2026, 11, 16), 480, 960, 0);
        Schedule b = shiftMin(2, LocalDate.of(2026, 11, 17), 360, 840, 0);
        List<Attendance> atts = List.of(done(a), done(b));
        LocalDateTime after = LocalDateTime.of(2026, 11, 24, 9, 0);
        assertEquals(38400, run(sw, false, YearMonth.of(2026, 11), false, after, List.of(a, b), atts).weeklyHoliday());
        sw.setLeftAt(LocalDate.of(2026, 11, 17));
        assertEquals(0, run(sw, false, YearMonth.of(2026, 11), false, after, List.of(a, b), atts).weeklyHoliday());
    }

    @Test
    void 계약서_소정시간으로_주휴_보고서25() {
        StoreWorker sw = worker(10320, true, false);
        Contract ct = new Contract();
        ct.setWeeklyHours(25);
        when(contractRepo.findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(7)).thenReturn(Optional.of(ct));
        List<Schedule> shifts = new ArrayList<>();
        for (int d : new int[]{2, 4, 6}) shifts.add(shiftMin(d, LocalDate.of(2026, 11, d), 600, 840, 0)); // 실근무 12h
        PayslipDto.Response r = run(sw, false, YearMonth.of(2026, 11), false, NOV17_23, shifts,
                shifts.stream().map(PayrollCalculatorTest::done).toList());
        assertEquals(51600, r.weeklyHoliday()); // 25/40 × 8 × 10,320
    }

    @Test
    void 주_40시간_초과_연장가산_보고서18() {
        StoreWorker sw = worker(12000, false, true);
        List<Schedule> shifts = new ArrayList<>();
        for (int d = 2; d <= 7; d++) shifts.add(shiftMin(d, LocalDate.of(2026, 11, d), 540, 1080, 60)); // 480분 × 6일
        PayslipDto.Response r = run(sw, true, YearMonth.of(2026, 11), false, NOV17_23, shifts,
                shifts.stream().map(PayrollCalculatorTest::done).toList());
        assertEquals(48000, r.premium()); // (2880 − 2400 − 0)분 × 12,000 × 0.5
    }

    @Test
    void 시급이_커도_음수_급여가_없다_보고서04_23() {
        StoreWorker sw = worker(Integer.MAX_VALUE, true, true);
        sw.setTaxType(StoreWorker.TaxType.BUSINESS_TAX);
        List<Schedule> shifts = new ArrayList<>();
        for (int d = 23; d <= 27; d++) shifts.add(shiftMin(d, LocalDate.of(2026, 11, d), 540, 1080, 0));
        PayslipDto.Response r = run(sw, true, YearMonth.of(2026, 11), true, NOV17_23, shifts, List.of());
        assertTrue(r.base() > 0 && r.gross() > 0 && r.deduction() >= 0 && r.net() > 0, r.toString());
        assertEquals(Integer.MAX_VALUE, r.gross());
    }
}
