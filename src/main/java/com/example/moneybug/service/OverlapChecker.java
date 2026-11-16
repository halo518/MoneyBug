package com.example.moneybug.service;

import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 근무 겹침 검증 (설계서 §4.5, §7). 겹침 판정 = 같은 직원의 *실제 근무*(isWork)와 시간 구간이 겹치는지.
 * 야간 근무(endMin <= startMin)는 다음 날까지로 계산하므로 전날/다음날 근무와도 비교한다.
 */
@Component
@RequiredArgsConstructor
public class OverlapChecker {

    private final ScheduleRepository scheduleRepository;

    /** probe 와 겹치는 그 직원의 다른 실제 근무가 있으면 true. excludeScheduleId 는 자기 자신 제외용. */
    public boolean hasOverlap(StoreWorker sw, Schedule probe, Integer excludeScheduleId) {
        return scheduleRepository.findByWorkerBetween(sw.getId(),
                        probe.getWorkDate().minusDays(1), probe.getWorkDate().plusDays(1)).stream()
                .filter(Schedule::isWork)
                .filter(s -> excludeScheduleId == null || !s.getId().equals(excludeScheduleId))
                .anyMatch(s -> TimeUtil.overlaps(s, probe));
    }

    /** 겹치면 409 SHIFT_OVERLAP. 실제 근무가 아닌 probe(연차·휴무·시간 미정)는 검사하지 않는다. */
    public void assertNoOverlap(StoreWorker sw, Schedule probe, Integer excludeScheduleId) {
        if (probe.isWork() && hasOverlap(sw, probe, excludeScheduleId)) {
            throw ApiException.conflict("SHIFT_OVERLAP",
                    sw.getWorker().getName() + "님의 다른 근무와 시간이 겹쳐요 (" + TimeUtil.label(probe) + ")");
        }
    }

    /** 저장 전 후보(날짜·시간만 채운 임시 Schedule). */
    public static Schedule probe(StoreWorker sw, java.time.LocalDate date, int startMin, int endMin) {
        Schedule p = new Schedule();
        p.setStoreWorker(sw);
        p.setWorkDate(date);
        p.setStartMin(startMin);
        p.setEndMin(endMin);
        p.setKind(Schedule.Kind.WORK);
        p.setTimeTbd(false);
        return p;
    }
}
