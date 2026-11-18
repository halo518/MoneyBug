package com.example.moneybug.service;

import com.example.moneybug.entity.*;
import com.example.moneybug.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubstituteNoticeGuardTest {

    @Mock SubstituteRequestRepository requests;
    @Mock SubstituteApplicantRepository applicants;
    @Mock ScheduleRepository schedules;
    @Mock AttendanceRepository attendances;
    @Mock StoreAccess access;
    @Mock NotificationPublisher notifier;
    @Mock OverlapChecker overlap;
    @Mock SubstituteAssembler assembler;
    @Mock StoreRepository stores;
    @Mock StoreWorkerRepository storeWorkers;
    @Mock MonthScheduleRepository months;
    @InjectMocks SubstituteService service;

    Store store = new Store();
    SubstituteRequest req = new SubstituteRequest();
    Schedule shift = new Schedule();

    @BeforeEach
    void setUp() {
        store.setId(1);
        User a = new User(); a.setId(10);
        User b = new User(); b.setId(11);
        StoreWorker swA = new StoreWorker(); swA.setId(100); swA.setWorker(a); swA.setStore(store);
        StoreWorker swB = new StoreWorker(); swB.setId(101); swB.setWorker(b); swB.setStore(store);
        shift.setId(5); shift.setStoreWorker(swA); shift.setStartMin(600); shift.setEndMin(900);
        req.setId(7); req.setSchedule(shift); req.setRequester(a); req.setTaker(b);
        req.setStatus(SubstituteRequest.Status.ACCEPTED);
        when(access.requireOwner(anyInt(), anyInt())).thenReturn(store);
        when(requests.findByIdAndStore(7, 1)).thenReturn(Optional.of(req));
        when(requests.findByIdForUpdate(7)).thenReturn(Optional.of(req));
        when(stores.findByIdForUpdate(1)).thenReturn(Optional.of(store));
        when(access.activeStaff(11, 1)).thenReturn(Optional.of(swB));
    }

    @Test
    void approveFutureShiftOk() {
        shift.setWorkDate(LocalDate.now().plusDays(3));
        service.approve(1, 1, 7);
        assertEquals(SubstituteRequest.Status.APPROVED, req.getStatus());
        assertEquals(101, shift.getStoreWorker().getId());
    }

    @Test
    void approvePastShiftBlocked() {
        shift.setWorkDate(LocalDate.now().minusDays(1));
        assertThrows(ApiException.class, () -> service.approve(1, 1, 7));
        assertEquals(SubstituteRequest.Status.ACCEPTED, req.getStatus());
    }

    @Test
    void approveAttendedShiftBlocked() {
        shift.setWorkDate(LocalDate.now().plusDays(3));
        when(attendances.existsByScheduleId(5)).thenReturn(true);
        assertThrows(ApiException.class, () -> service.approve(1, 1, 7));
    }

    @Test
    void approveBlockedWhenWorkerChanged() {
        shift.setWorkDate(LocalDate.now().plusDays(3));
        User c = new User(); c.setId(12);
        StoreWorker swC = new StoreWorker(); swC.setId(102); swC.setWorker(c); swC.setStore(store);
        shift.setStoreWorker(swC);
        assertThrows(ApiException.class, () -> service.approve(1, 1, 7));
    }
}
