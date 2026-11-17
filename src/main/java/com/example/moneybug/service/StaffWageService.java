package com.example.moneybug.service;

import com.example.moneybug.entity.StaffWage;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.StaffWageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/** 직원 시급 이력 기록·조회. */
@Service
@RequiredArgsConstructor
public class StaffWageService {

    static final LocalDate BASE_DATE = LocalDate.of(2000, 1, 1);

    private final StaffWageRepository repository;

    /** 이력이 비어 있으면 기준 행(현재 시급)을 먼저 넣고 (from, wage) 를 기록. from ≤ 오늘이면 현재 시급도 바꾼다. */
    public void record(StoreWorker sw, LocalDate from, int wage) {
        if (sw.getId() == null || repository.findByStoreWorkerIdOrderByEffectiveFromAscIdAsc(sw.getId()).isEmpty()) {
            save(sw, BASE_DATE, sw.getHourlyWage());
        }
        save(sw, from, wage);
        if (!from.isAfter(TimeUtil.today())) sw.setHourlyWage(wage);
    }

    private void save(StoreWorker sw, LocalDate from, int wage) {
        StaffWage w = new StaffWage();
        w.setStoreWorker(sw);
        w.setEffectiveFrom(from);
        w.setWage(wage);
        repository.save(w);
    }

    public List<StaffWage> rows(Integer storeWorkerId) {
        return repository.findByStoreWorkerIdOrderByEffectiveFromAscIdAsc(storeWorkerId);
    }

    /** effective_from ≤ d 중 가장 늦은 날짜(같은 날이면 id 큰 것). 없으면 fallback. */
    public static int wageOn(List<StaffWage> rows, LocalDate d, int fallback) {
        StaffWage best = null;
        for (StaffWage r : rows) {
            if (r.getEffectiveFrom().isAfter(d)) continue;
            if (best == null || r.getEffectiveFrom().isAfter(best.getEffectiveFrom())
                    || (r.getEffectiveFrom().equals(best.getEffectiveFrom())
                    && r.getId() != null && best.getId() != null && r.getId() > best.getId())) {
                best = r;
            }
        }
        return best == null ? fallback : best.getWage();
    }
}
