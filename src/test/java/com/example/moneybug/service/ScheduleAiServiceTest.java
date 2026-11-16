package com.example.moneybug.service;

import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.service.FixedShiftPlanner.*;
import com.example.moneybug.service.ScheduleAiService.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleAiServiceTest {

    static final LocalDate MON = LocalDate.of(2026, 11, 2);
    static final JsonMapper M = JsonMapper.builder().build();

    static GenInput input(List<WorkHours> wh) {
        Person flex = new Person(7, false, null, wh);
        Person part = new Person(8, true, FixedShiftPlannerTest.week("오픈"), null);
        return new GenInput("2026-11", new StoreHours(480, 1320, 480, 1320),
                List.of(new PartDef("오픈", 480, 720, 30)),
                List.of(new Member(flex, false), new Member(part, false)),
                java.util.stream.IntStream.range(0, 7).mapToObj(MON::plusDays).toList(),
                MonthSchedule.Priority.FAIRNESS);
    }

    @Test
    void promptHasNoNamesAndOnlyStaff() {
        String p = ScheduleAiService.buildPrompt(input(null));
        assertTrue(p.contains("#7"));
        assertFalse(p.contains("#8"));
        assertTrue(p.contains("2026-11-02 (월) 08:00–22:00"));
    }

    @Test
    void toShiftsFilters() throws Exception {
        var out = M.readTree("""
                {"shifts":[
                 {"staffId":7,"date":"2026-11-02","start":"09:07","end":"18:00"},
                 {"staffId":7,"date":"2026-11-02","start":"10:00","end":"12:00"},
                 {"staffId":7,"date":"2026-11-03","start":"06:00","end":"12:00"},
                 {"staffId":7,"date":"2026-12-03","start":"09:00","end":"12:00"},
                 {"staffId":8,"date":"2026-11-04","start":"09:00","end":"12:00"},
                 {"staffId":99,"date":"2026-11-04","start":"09:00","end":"12:00"},
                 {"staffId":7,"date":"2026-11-05","start":"9시","end":"12:00"},
                 {"staffId":7,"date":"2026-11-06","start":"09:00","end":"18:00"}]}""");
        List<Planned> r = ScheduleAiService.toShifts(out, input(null));
        assertEquals(2, r.size());
        assertEquals(540, r.get(0).startMin()); // 09:07 -> 15분 스냅 09:00
        assertEquals(60, r.get(0).breakMin());
        assertEquals(MON.plusDays(4), r.get(1).date());
    }

    @Test
    void workHoursOverrideAiTimes() throws Exception {
        List<WorkHours> wh = new ArrayList<>();
        wh.add(new WorkHours(600, 960));
        for (int i = 1; i < 7; i++) wh.add(null);
        var out = M.readTree("""
                {"shifts":[{"staffId":7,"date":"2026-11-02","start":"09:00","end":"20:00"},
                           {"staffId":7,"date":"2026-11-03","start":"09:00","end":"20:00"}]}""");
        List<Planned> r = ScheduleAiService.toShifts(out, input(wh));
        assertEquals(1, r.size());
        assertEquals(600, r.get(0).startMin());
        assertEquals(960, r.get(0).endMin());
    }

    @Test
    void nameBackReplacesIds() {
        assertEquals("김알바님이 7 아님 #9", ScheduleAiService.nameBack("#7님이 7 아님 #9", Map.of(7, "김알바")));
    }
}
