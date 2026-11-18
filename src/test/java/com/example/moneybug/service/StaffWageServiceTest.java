package com.example.moneybug.service;

import com.example.moneybug.entity.StaffWage;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.StaffWageRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaffWageServiceTest {

    private static StaffWage row(int id, String from, int wage) {
        StaffWage w = new StaffWage();
        w.setId(id);
        w.setEffectiveFrom(LocalDate.parse(from));
        w.setWage(wage);
        return w;
    }

    @Test
    void wageOnPicksLatestAndLaterIdOnSameDay() {
        List<StaffWage> rows = List.of(row(1, "2000-01-01", 10000), row(2, "2026-05-01", 11000),
                row(3, "2026-05-01", 12000));
        assertEquals(10000, StaffWageService.wageOn(rows, LocalDate.parse("2026-04-30"), 9));
        assertEquals(12000, StaffWageService.wageOn(rows, LocalDate.parse("2026-05-01"), 9));
        assertEquals(9, StaffWageService.wageOn(List.of(), LocalDate.parse("2026-05-01"), 9));
    }

    @Test
    void recordAddsBaseRowOnceAndFutureContractKeepsCurrentWage() {
        StaffWageRepository repo = mock(StaffWageRepository.class);
        List<StaffWage> saved = new ArrayList<>();
        when(repo.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
        when(repo.findByStoreWorkerIdOrderByEffectiveFromAscIdAsc(5)).thenReturn(List.of());
        StoreWorker sw = new StoreWorker();
        sw.setId(5);
        sw.setHourlyWage(10000);
        StaffWageService svc = new StaffWageService(repo);

        svc.record(sw, TimeUtil.today().plusDays(10), 12000);
        assertEquals(2, saved.size());
        assertEquals(LocalDate.of(2000, 1, 1), saved.get(0).getEffectiveFrom());
        assertEquals(10000, saved.get(0).getWage());
        assertEquals(10000, sw.getHourlyWage()); // 미래 계약: 아직 안 바뀜

        svc.record(sw, TimeUtil.today(), 11000);
        assertEquals(11000, sw.getHourlyWage());
    }

    /** 저장·삭제를 리스트로 흉내 내는 저장소 (IDENTITY 처럼 저장 즉시 id). */
    private static StaffWageRepository memRepo(List<StaffWage> db) {
        StaffWageRepository repo = mock(StaffWageRepository.class);
        int[] seq = {100};
        when(repo.save(any())).thenAnswer(i -> {
            StaffWage w = i.getArgument(0);
            w.setId(++seq[0]);
            db.add(w);
            return w;
        });
        when(repo.findByStoreWorkerIdOrderByEffectiveFromAscIdAsc(any())).thenAnswer(i -> db.stream()
                .sorted(java.util.Comparator.comparing(StaffWage::getEffectiveFrom).thenComparing(StaffWage::getId))
                .toList());
        org.mockito.Mockito.doAnswer(i -> { db.removeAll((java.util.Collection<?>) i.getArgument(0)); return null; })
                .when(repo).deleteAll(any());
        return repo;
    }

    private static StoreWorker worker(int wage) {
        StoreWorker sw = new StoreWorker();
        sw.setId(5);
        sw.setHourlyWage(wage);
        return sw;
    }

    @Test
    void recordTwiceKeepsOneBaseRow() {
        List<StaffWage> db = new ArrayList<>();
        StaffWageService svc = new StaffWageService(memRepo(db));
        StoreWorker sw = worker(10320);
        svc.record(sw, TimeUtil.today(), 11000);
        svc.record(sw, TimeUtil.today(), 12000);
        assertEquals(1, db.stream().filter(w -> w.getEffectiveFrom().equals(StaffWageService.BASE_DATE)).count());
        assertEquals(3, db.size());
    }

    @Test
    void contractRewrittenWithEarlierStartDropsOldStep() {
        List<StaffWage> db = new ArrayList<>();
        StaffWageService svc = new StaffWageService(memRepo(db));
        StoreWorker sw = worker(10320);
        LocalDate dec1 = TimeUtil.today().plusDays(20), nov20 = TimeUtil.today().plusDays(3);
        svc.recordContract(sw, 1, dec1, dec1.plusYears(1), 11000, null);
        // 직원이 서명한 뒤라도 시작일을 앞당긴 새 계약서는 그 뒤 단계를 모두 대체한다
        svc.recordContract(sw, 2, nov20, nov20.plusYears(1), 10320, null);
        assertEquals(10320, StaffWageService.wageOn(db, dec1.plusDays(5), 0));
        assertEquals(List.of(StaffWageService.BASE_DATE, nov20),
                svc.rows(5).stream().map(StaffWage::getEffectiveFrom).toList());
    }

    @Test
    void unsignedSupersededContractStepIsRemovedEvenBeforeNewStart() {
        List<StaffWage> db = new ArrayList<>();
        StaffWageService svc = new StaffWageService(memRepo(db));
        StoreWorker sw = worker(10320);
        LocalDate past = TimeUtil.today().minusDays(10), later = TimeUtil.today().plusDays(10);
        svc.recordContract(sw, 1, past, past.plusYears(1), 10500, null);
        assertEquals(10500, sw.getHourlyWage());
        svc.recordContract(sw, 2, later, later.plusYears(1), 11000, 1);
        assertEquals(10320, StaffWageService.wageOn(db, TimeUtil.today(), 0));
        assertEquals(10320, sw.getHourlyWage()); // 오늘 시급도 기준 행으로 돌아간다
        assertEquals(11000, StaffWageService.wageOn(db, later, 0));
    }

    @Test
    void manualStepsOutsideContractPeriodSurvive() {
        List<StaffWage> db = new ArrayList<>();
        StaffWageService svc = new StaffWageService(memRepo(db));
        StoreWorker sw = worker(10320);
        LocalDate start = TimeUtil.today().plusDays(1), end = start.plusMonths(6);
        svc.record(sw, start.plusDays(10), 15000);        // 기간 안: 계약서가 대체
        svc.record(sw, end.plusDays(10), 16000);          // 기간 밖: 유지
        svc.recordContract(sw, 3, start, end, 12000, null);
        assertEquals(12000, StaffWageService.wageOn(db, start.plusDays(20), 0));
        assertEquals(16000, StaffWageService.wageOn(db, end.plusDays(10), 0));
        assertEquals(10320, StaffWageService.wageOn(db, TimeUtil.today(), 0));
    }
}
