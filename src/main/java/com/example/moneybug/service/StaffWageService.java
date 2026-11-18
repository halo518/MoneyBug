package com.example.moneybug.service;

import com.example.moneybug.entity.StaffWage;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.StaffWageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 직원 시급 이력 기록·조회. */
@Service
@RequiredArgsConstructor
public class StaffWageService {

    static final LocalDate BASE_DATE = LocalDate.of(2000, 1, 1);

    private final StaffWageRepository repository;

    /** 이력이 비어 있으면 기준 행(현재 시급)을 먼저 넣고 (from, wage) 를 기록. from ≤ 오늘이면 현재 시급도 바꾼다. */
    public void record(StoreWorker sw, LocalDate from, int wage) {
        addBaseIfEmpty(sw, rows(sw.getId()));
        save(sw, from, wage, null);
        if (!from.isAfter(TimeUtil.today())) sw.setHourlyWage(wage);
    }

    /**
     * 계약서 시급 기록. 계약 기간 [start, end] 동안은 이 계약서 시급만 적용되게 한다:
     * 대체된 계약서가 만든 단계 중 start 이후 것과, 서명 전에 대체된 계약서(supersededUnsignedId)의 단계는 모두 지우고,
     * 사장님이 직접 넣은 단계는 계약 기간 안의 것만 지운다(기간 밖·기준 행은 둔다). 현재 시급은 오늘 기준으로 다시 맞춘다.
     */
    public void recordContract(StoreWorker sw, Integer contractId, LocalDate start, LocalDate end, int wage,
                               Integer supersededUnsignedId) {
        List<StaffWage> rows = new ArrayList<>(rows(sw.getId()));
        StaffWage base = addBaseIfEmpty(sw, rows);
        if (base != null) rows.add(base);
        List<StaffWage> stale = rows.stream().filter(r -> r.getId() != null
                && supersededBy(r, start, end, supersededUnsignedId)).toList();
        repository.deleteAll(stale);
        rows.removeAll(stale);
        rows.add(save(sw, start, wage, contractId)); // IDENTITY: 저장 즉시 id 가 생겨 같은 날짜에선 이 행이 이긴다
        sw.setHourlyWage(wageOn(rows, TimeUtil.today(), sw.getHourlyWage()));
    }

    static boolean supersededBy(StaffWage r, LocalDate start, LocalDate end, Integer supersededUnsignedId) {
        LocalDate f = r.getEffectiveFrom();
        if (r.getContractId() == null) {
            return !f.equals(BASE_DATE) && !f.isBefore(start) && !f.isAfter(end);
        }
        return r.getContractId().equals(supersededUnsignedId) || !f.isBefore(start);
    }

    /** 이력이 비었을 때만 기준 행(현재 시급)을 넣고 돌려준다. 동시 호출은 호출자가 직원·매장 행을 잠가 막는다. */
    private StaffWage addBaseIfEmpty(StoreWorker sw, List<StaffWage> rows) {
        return rows.isEmpty() ? save(sw, BASE_DATE, sw.getHourlyWage(), null) : null;
    }

    private StaffWage save(StoreWorker sw, LocalDate from, int wage, Integer contractId) {
        StaffWage w = new StaffWage();
        w.setStoreWorker(sw);
        w.setEffectiveFrom(from);
        w.setWage(wage);
        w.setContractId(contractId);
        return repository.save(w);
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
