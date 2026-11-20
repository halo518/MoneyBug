package com.example.moneybug.service;

import com.example.moneybug.config.FeIdJsonModule;
import com.example.moneybug.dto.AiDto;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.service.FixedShiftPlanner.Planned;
import com.example.moneybug.service.LaborContext.ContractInfo;
import com.example.moneybug.service.LaborContext.Staff;
import com.example.moneybug.service.LaborContext.StoreFacts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaborAuditTest {

    /** 수요일. 이번 주 월요일 = 11/16. */
    static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    static final LocalDate MON = LocalDate.of(2026, 11, 16);
    static final ContractInfo SIGNED = new ContractInfo(true, true, 20);
    static final StoreFacts SMALL = new StoreFacts(true, Store.SizeMode.AUTO, 540, 1320, 540, 1320, 3);
    static final StoreFacts BIG = new StoreFacts(false, Store.SizeMode.AUTO, 540, 1320, 540, 1320, 6);

    /** 문제 없는 직원. */
    static Staff ok(int id) {
        return new Staff(id, "직원" + id, StoreWorker.Kind.PART_TIMER, 11000, StoreWorker.TaxType.NONE, true, true,
                0, SIGNED, TODAY.plusDays(100));
    }

    static Staff with(Staff s, StoreWorker.Kind kind, int wage, boolean auto, boolean premium, ContractInfo c, LocalDate cert) {
        return new Staff(s.id(), s.label(), kind, wage, s.deduction(), auto, premium, 0, c, cert);
    }

    static Staff minor(int id) {
        return with(ok(id), StoreWorker.Kind.MINOR, 11000, true, true, SIGNED, TODAY.plusDays(100));
    }

    static Planned shift(int staff, LocalDate d, int start, int end, int brk) {
        return new Planned(staff, d, start, end, null, brk);
    }

    /** 월요일부터 n일, 하루 start~end. */
    static List<Planned> days(int staff, int n, int start, int end, int brk) {
        List<Planned> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(shift(staff, MON.plusDays(i), start, end, brk));
        return out;
    }

    static List<AiDto.Finding> run(StoreFacts store, List<Staff> staff, List<Planned> shifts) {
        return LaborAudit.run(store, staff, shifts, TODAY);
    }

    static Optional<AiDto.Finding> find(List<AiDto.Finding> fs, String code) {
        return fs.stream().filter(f -> f.code().equals(code)).findFirst();
    }

    static AiDto.Finding hit(List<AiDto.Finding> fs, String code, String severity) {
        AiDto.Finding f = find(fs, code).orElseThrow(() -> new AssertionError(code + " 없음: " + fs));
        assertEquals(severity, f.severity());
        assertFalse(f.title().isBlank());
        assertFalse(f.action().isBlank());
        return f;
    }

    static void miss(List<AiDto.Finding> fs, String code) {
        assertTrue(find(fs, code).isEmpty(), code + " 가 나오면 안 됨: " + fs);
    }

    @Test
    void cleanStaffHasNoFindings() {
        assertEquals(List.of(), run(SMALL, List.of(ok(1), ok(2)), days(1, 5, 540, 840, 30)));
    }

    @Test
    void minWage() {
        AiDto.Finding f = hit(run(SMALL, List.of(with(ok(1), StoreWorker.Kind.PART_TIMER, 10000, true, true, SIGNED, TODAY.plusDays(100)),
                ok(2), with(ok(3), StoreWorker.Kind.PART_TIMER, 9000, true, true, SIGNED, TODAY.plusDays(100))), List.of()),
                "MIN_WAGE", "high");
        assertEquals(List.of(1, 3), f.staffIds());
        assertTrue(f.detail().contains("직원1 10,000원, 직원3 9,000원"), f.detail());
        assertEquals(List.of("최저임금법 제6조"), f.citations());
        miss(run(SMALL, List.of(with(ok(1), StoreWorker.Kind.PART_TIMER, StoreWorkerDto_MIN, true, true, SIGNED, TODAY.plusDays(100))), List.of()), "MIN_WAGE");
    }

    static final int StoreWorkerDto_MIN = com.example.moneybug.dto.StoreWorkerDto.MIN_WAGE_2026;

    @Test
    void contracts() {
        Staff none = with(ok(1), StoreWorker.Kind.PART_TIMER, 11000, true, true, null, TODAY.plusDays(100));
        Staff half = with(ok(2), StoreWorker.Kind.PART_TIMER, 11000, true, true, new ContractInfo(true, false, null), TODAY.plusDays(100));
        List<AiDto.Finding> fs = run(SMALL, List.of(none, half, ok(3)), List.of());
        assertEquals(List.of(1), hit(fs, "CONTRACT_MISSING", "high").staffIds());
        AiDto.Finding u = hit(fs, "CONTRACT_UNSIGNED", "medium");
        assertEquals(List.of(2), u.staffIds());
        assertTrue(u.detail().contains("직원2(직원 서명 전)"), u.detail());
        List<AiDto.Finding> clean = run(SMALL, List.of(ok(3)), List.of());
        miss(clean, "CONTRACT_MISSING");
        miss(clean, "CONTRACT_UNSIGNED");
    }

    @Test
    void certificates() {
        Staff expired = with(ok(1), StoreWorker.Kind.PART_TIMER, 11000, true, true, SIGNED, TODAY.minusDays(1));
        Staff missing = with(ok(2), StoreWorker.Kind.PART_TIMER, 11000, true, true, SIGNED, null);
        Staff soon = with(ok(3), StoreWorker.Kind.PART_TIMER, 11000, true, true, SIGNED, TODAY.plusDays(30));
        Staff later = with(ok(4), StoreWorker.Kind.PART_TIMER, 11000, true, true, SIGNED, TODAY.plusDays(31));
        List<AiDto.Finding> fs = run(SMALL, List.of(expired, missing, soon, later), List.of());
        assertEquals(List.of(1), hit(fs, "CERT_EXPIRED", "high").staffIds());
        assertEquals(List.of(2), hit(fs, "CERT_MISSING", "medium").staffIds());
        AiDto.Finding s = hit(fs, "CERT_SOON", "low");
        assertEquals(List.of(3), s.staffIds());
        assertTrue(s.detail().contains("직원3(D-30)"), s.detail());
        List<AiDto.Finding> clean = run(SMALL, List.of(later), List.of());
        miss(clean, "CERT_EXPIRED");
        miss(clean, "CERT_MISSING");
        miss(clean, "CERT_SOON");
    }

    @Test
    void minorNight() {
        // 11/19 18:00~23:00 (예정) → 걸림. 11/17(지난 날) 근무와 성인은 안 걸림.
        List<Planned> shifts = List.of(shift(1, TODAY.plusDays(1), 1080, 1380, 30), shift(2, TODAY.plusDays(1), 1080, 1380, 30));
        AiDto.Finding f = hit(run(SMALL, List.of(minor(1), ok(2)), shifts), "MINOR_NIGHT", "high");
        assertEquals(List.of(1), f.staffIds());
        assertTrue(f.detail().contains("직원1 11/19 18:00~23:00"), f.detail());
        assertEquals(List.of("근로기준법 제70조"), f.citations());
        miss(run(SMALL, List.of(minor(1)), List.of(shift(1, TODAY.minusDays(1), 1080, 1380, 30))), "MINOR_NIGHT");
        miss(run(SMALL, List.of(minor(1)), List.of(shift(1, TODAY.plusDays(1), 360, 780, 30))), "MINOR_NIGHT");
    }

    @Test
    void minorHours() {
        // 하루 8시간(9~18시, 휴게 1시간) → 7시간 초과
        AiDto.Finding day = hit(run(SMALL, List.of(minor(1)), List.of(shift(1, TODAY, 540, 1080, 60))), "MINOR_HOURS", "high");
        assertTrue(day.detail().contains("직원1 11/18 하루 8시간"), day.detail());
        // 6일 × 6시간 = 36시간 > 35
        AiDto.Finding week = hit(run(SMALL, List.of(minor(1)), days(1, 6, 600, 990, 30)), "MINOR_HOURS", "high");
        assertTrue(week.detail().contains("직원1 11/16주 36시간"), week.detail());
        // 하루 7시간 · 5일 = 35시간 → 괜찮음. 성인은 해당 없음
        miss(run(SMALL, List.of(minor(1)), days(1, 5, 540, 990, 30)), "MINOR_HOURS");
        miss(run(SMALL, List.of(ok(1)), List.of(shift(1, TODAY, 540, 1080, 60))), "MINOR_HOURS");
    }

    @Test
    void weeklyHolidayOff() {
        Staff off = with(ok(1), StoreWorker.Kind.PART_TIMER, 11000, false, true, SIGNED, TODAY.plusDays(100));
        AiDto.Finding f = hit(run(SMALL, List.of(off), days(1, 3, 540, 840, 0)), "WEEKLY_HOLIDAY_OFF", "medium");
        assertTrue(f.detail().contains("직원1 11/16주 15시간"), f.detail());
        miss(run(SMALL, List.of(off), days(1, 2, 540, 960, 30)), "WEEKLY_HOLIDAY_OFF"); // 13시간
        miss(run(SMALL, List.of(ok(1)), days(1, 3, 540, 840, 0)), "WEEKLY_HOLIDAY_OFF"); // 자동 켬
    }

    @Test
    void sevenDays() {
        assertEquals(List.of(1), hit(run(SMALL, List.of(ok(1)), days(1, 7, 540, 720, 0)), "SEVEN_DAYS", "medium").staffIds());
        miss(run(SMALL, List.of(ok(1)), days(1, 6, 540, 720, 0)), "SEVEN_DAYS");
    }

    @Test
    void weeklyHours() {
        // 6일 × 9시간 = 54시간
        List<Planned> long6 = days(1, 6, 480, 1080, 60);
        List<AiDto.Finding> big = run(BIG, List.of(ok(1)), long6);
        AiDto.Finding over = hit(big, "OVER_HOURS", "high");
        assertTrue(over.detail().contains("직원1 11/16주 54시간"), over.detail());
        hit(big, "OVER_40", "low");
        List<AiDto.Finding> small = run(SMALL, List.of(ok(1)), long6);
        miss(small, "OVER_HOURS");
        assertEquals(List.of(), hit(small, "OVER_40", "low").citations());
        // 5일 × 8시간 = 40시간 → 괜찮음
        miss(run(BIG, List.of(ok(1)), days(1, 5, 540, 1080, 60)), "OVER_40");
    }

    @Test
    void breakShort() {
        AiDto.Finding f = hit(run(SMALL, List.of(ok(1)), List.of(shift(1, TODAY, 540, 1080, 30))), "BREAK_SHORT", "medium");
        assertTrue(f.detail().contains("직원1 11/18 8.5시간 근무에 휴게 30분(필요 60분)"), f.detail());
        miss(run(SMALL, List.of(ok(1)), List.of(shift(1, TODAY, 540, 1080, 60))), "BREAK_SHORT");
        miss(run(SMALL, List.of(ok(1)), List.of(shift(1, TODAY, 540, 770, 0))), "BREAK_SHORT"); // 4시간 미만
    }

    @Test
    void premiumOff() {
        Staff off = with(ok(1), StoreWorker.Kind.PART_TIMER, 11000, true, false, SIGNED, TODAY.plusDays(100));
        assertEquals(List.of(1), hit(run(BIG, List.of(off, ok(2)), List.of()), "PREMIUM_OFF", "low").staffIds());
        miss(run(SMALL, List.of(off), List.of()), "PREMIUM_OFF");
    }

    @Test
    void sizeMismatch() {
        StoreFacts under5ButSix = new StoreFacts(true, Store.SizeMode.UNDER5, 540, 1320, 540, 1320, 6);
        AiDto.Finding f = hit(run(under5ButSix, List.of(), List.of()), "SIZE_MISMATCH", "low");
        assertEquals(List.of(), f.staffIds());
        hit(run(new StoreFacts(false, Store.SizeMode.OVER5, 540, 1320, 540, 1320, 3), List.of(), List.of()), "SIZE_MISMATCH", "low");
        miss(run(new StoreFacts(true, Store.SizeMode.UNDER5, 540, 1320, 540, 1320, 4), List.of(), List.of()), "SIZE_MISMATCH");
        miss(run(new StoreFacts(false, Store.SizeMode.AUTO, 540, 1320, 540, 1320, 6), List.of(), List.of()), "SIZE_MISMATCH");
    }

    @Test
    void sortedHighToLowAndIdsAsStrings() throws Exception {
        Staff bad = with(ok(7), StoreWorker.Kind.PART_TIMER, 9000, true, false, null, TODAY.plusDays(3));
        List<AiDto.Finding> fs = run(BIG, List.of(bad), days(7, 7, 540, 1080, 30));
        List<String> order = List.of("high", "medium", "low");
        for (int i = 1; i < fs.size(); i++) {
            assertTrue(order.indexOf(fs.get(i - 1).severity()) <= order.indexOf(fs.get(i).severity()), fs.toString());
        }
        assertEquals("high", fs.get(0).severity());
        assertEquals("low", fs.get(fs.size() - 1).severity());
        String json = JsonMapper.builder().addModule(new FeIdJsonModule()).build().writeValueAsString(
                new AiDto.AuditResponse("s", false, null, fs.subList(0, 1)));
        assertTrue(json.contains("\"staffIds\":[\"7\"]"), json);
        assertTrue(json.contains("\"aiUsed\":false"), json);
    }

    @Test
    void contextLabels() {
        Staff s = new Staff(1, "직원1", StoreWorker.Kind.MINOR, 10320, StoreWorker.TaxType.BUSINESS_TAX, false, false,
                930, new ContractInfo(false, false, null), TODAY.minusDays(1));
        assertEquals("- 직원1: 연소자, 시급 10,320원, 공제 3.3% 원천징수, 주휴수당 자동 끔, 가산수당 끔, "
                + "이번 달 주 평균 근무 15.5시간(휴게 제외), 근로계약서 미서명(사장님·직원 모두 서명 전), 보건증 만료",
                LaborContext.line(s, TODAY));
        String ctx = LaborContext.ownerContext(new StoreFacts(true, Store.SizeMode.UNDER5, 540, 1500, 600, 1320, 2),
                List.of(s), 0, TODAY);
        assertTrue(ctx.contains("5인 미만 사업장: 예 (규모 설정: 사장님이 5인 미만으로 직접 설정)"), ctx);
        assertTrue(ctx.contains("영업시간: 평일 09:00~익일 01:00, 주말 10:00~22:00"), ctx);
        assertTrue(ctx.contains("재직 인원: 2명"), ctx);
    }
}
