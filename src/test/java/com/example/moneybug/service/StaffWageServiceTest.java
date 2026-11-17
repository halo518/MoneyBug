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
}
