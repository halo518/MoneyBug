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
        when(parts.findByStoreIdOrderByStartMinAsc(1)).thenReturn(List.of(part("마감", false), part("직원오픈", true)));
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
}
