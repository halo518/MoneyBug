package com.example.moneybug.service;

import com.example.moneybug.dto.StaffCommandDto;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StorePart;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 알바생 고정 파트·기본 파트에 직원 파트(flex)를 못 쓴다. */
class StaffPartsValidationTest {

    StoreWorkerRepository workers = mock(StoreWorkerRepository.class);
    StorePartRepository parts = mock(StorePartRepository.class);
    StoreAccess access = mock(StoreAccess.class);
    StaffService svc = new StaffService(workers, mock(ScheduleRepository.class), mock(AttendanceRepository.class),
            mock(SubstituteRequestRepository.class), mock(SubstituteApplicantRepository.class), parts,
            mock(StoreService.class), access, mock(StaffAssembler.class), mock(ScheduleDeleter.class),
            mock(NotificationPublisher.class), mock(StaffWageService.class), mock(StoreRepository.class));

    StoreWorker sw = new StoreWorker();

    @BeforeEach
    void setUp() {
        Store store = new Store();
        store.setId(1);
        sw.setId(3);
        sw.setStore(store);
        sw.setStatus(StoreWorker.Status.ACTIVE);
        sw.setKind(StoreWorker.Kind.PART_TIMER);
        when(workers.findByIdAndStoreIdForUpdate(3, 1)).thenReturn(Optional.of(sw));
        when(access.requireOwner(1, 1)).thenReturn(store);
        when(parts.findByStoreIdOrderByStartMinAsc(1)).thenReturn(List.of(part("오픈", false), part("마감", false),
                part("직원오픈", true), part("직원마감", true)));
    }

    private static StorePart part(String name, boolean flex) {
        StorePart p = new StorePart();
        p.setName(name);
        p.setStartMin(420);
        p.setEndMin(960);
        p.setFlex(flex);
        return p;
    }

    private static StaffCommandDto.UpdateRequest req(String part, StoreWorker.Kind kind, List<List<String>> fixed) {
        return new StaffCommandDto.UpdateRequest(null, null, null, null, part, kind, fixed, null, null);
    }

    private static List<List<String>> week(String name) {
        return List.of(List.of(name), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void fixedPartsNamingEmployeePartIsRejected() {
        var ex = assertThrows(ApiException.class, () -> svc.update(1, 1, 3, req(null, null, week("직원오픈"))));
        assertEquals("INVALID_FIXED_PARTS", ex.getCode());
        svc.update(1, 1, 3, req(null, null, week("마감")));
        assertEquals(List.of("마감"), StaffPrefs.fixedParts(sw).get(0));
    }

    @Test
    void partTimerDefaultPartCannotBeEmployeePart() {
        var ex = assertThrows(ApiException.class, () -> svc.update(1, 1, 3, req("직원오픈", null, null)));
        assertEquals("INVALID_PART", ex.getCode());
        // 같은 요청에서 직원으로 바꾸면 허용, 직원은 "직원" 도 그대로.
        svc.update(1, 1, 3, req("직원오픈", StoreWorker.Kind.FULLTIME, null));
        assertEquals("직원오픈", sw.getPart());
        svc.update(1, 1, 3, req("직원", null, null));
        assertEquals("직원", sw.getPart());
    }

    private static StaffCommandDto.UpdateRequest req(StoreWorker.Kind kind, List<List<String>> fixed,
                                                     List<StaffPrefs.WorkHours> hours) {
        return new StaffCommandDto.UpdateRequest(null, null, null, null, null, kind, fixed, hours, null);
    }

    /** 월요일만 시간을 둔 7칸. */
    private static List<StaffPrefs.WorkHours> mondayHours() {
        return java.util.Arrays.asList(new StaffPrefs.WorkHours(540, 1080), null, null, null, null, null, null);
    }

    private static List<List<String>> tuesday(String... names) {
        return List.of(List.of(), List.of(names), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static void rejected(Runnable call) {
        var ex = assertThrows(ApiException.class, call::run);
        assertEquals("INVALID_FIXED_PARTS", ex.getCode());
    }

    @Test
    void employeeFixedPartsMustBeOneEmployeePartPerDay() {
        sw.setKind(StoreWorker.Kind.FULLTIME);
        rejected(() -> svc.update(1, 1, 3, req(null, null, week("마감"))));            // 알바 파트
        rejected(() -> svc.update(1, 1, 3, req(null, null, week("없는파트"))));
        rejected(() -> svc.update(1, 1, 3, req(null, tuesday("직원오픈", "직원마감"), null))); // 하루 두 파트
        assertNull(sw.getFixedParts());

        svc.update(1, 1, 3, req(null, tuesday("직원오픈", "직원오픈"), null)); // 같은 이름은 하나로
        assertEquals(List.of("직원오픈"), StaffPrefs.fixedParts(sw).get(1));
    }

    @Test
    void kindSentInSamePatchDecidesWhichRule() {
        // 저장된 kind 는 알바생이지만 같은 요청에서 직원으로 바꾸면 직원 규칙.
        svc.update(1, 1, 3, req(null, StoreWorker.Kind.MANAGER, week("직원오픈")));
        assertEquals(List.of("직원오픈"), StaffPrefs.fixedParts(sw).get(0));
        // 반대로 알바생으로 바꾸면 알바 규칙(직원 파트 금지).
        rejected(() -> svc.update(1, 1, 3, req(null, StoreWorker.Kind.PART_TIMER, week("직원오픈"))));
    }

    @Test
    void employeePartAndHoursOnSameDayRejected() {
        sw.setKind(StoreWorker.Kind.FULLTIME);
        // 같이 보냄
        rejected(() -> svc.update(1, 1, 3, req(null, week("직원오픈"), mondayHours())));
        assertNull(sw.getFixedParts());
        assertNull(sw.getWorkHours());
        // 다른 요일이면 괜찮다
        svc.update(1, 1, 3, req(null, tuesday("직원오픈"), mondayHours()));

        // 저장된 시간(월)에 고정 파트(월)만 보냄
        rejected(() -> svc.update(1, 1, 3, req(null, week("직원오픈"), null)));
        assertEquals(List.of("직원오픈"), StaffPrefs.fixedParts(sw).get(1));

        // 저장된 고정 파트(화)에 시간(화)만 보냄
        List<StaffPrefs.WorkHours> tue = java.util.Arrays.asList(null, new StaffPrefs.WorkHours(540, 1080),
                null, null, null, null, null);
        rejected(() -> svc.update(1, 1, 3, req(null, null, tue)));
        assertEquals(new StaffPrefs.WorkHours(540, 1080), StaffPrefs.workHours(sw).get(0));

        // 고정 파트를 비우면서 시간을 옮기면 된다
        svc.update(1, 1, 3, req(null, tuesday(), tue));
        assertEquals(List.of(), StaffPrefs.fixedParts(sw).get(1));
    }

    @Test
    void partTimerRulesUnchanged() {
        // 알바생: 알바 파트 여러 개 가능, 시간과 같은 요일이어도 검사 안 함, 직원 파트는 금지.
        svc.update(1, 1, 3, req(null, week("오픈"), mondayHours()));
        svc.update(1, 1, 3, req(null, List.of(List.of("오픈", "마감"), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of()), null));
        assertEquals(List.of("오픈", "마감"), StaffPrefs.fixedParts(sw).get(0));
        rejected(() -> svc.update(1, 1, 3, req(null, week("직원오픈"), null)));
    }
}
