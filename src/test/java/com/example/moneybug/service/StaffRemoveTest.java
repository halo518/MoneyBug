package com.example.moneybug.service;

import com.example.moneybug.dto.StaffCommandDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.SubstituteApplicant;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class StaffRemoveTest {

    StoreAccess access = mock(StoreAccess.class);
    ScheduleRepository schedules = mock(ScheduleRepository.class);
    SubstituteRequestRepository subs = mock(SubstituteRequestRepository.class);
    SubstituteApplicantRepository applicants = mock(SubstituteApplicantRepository.class);
    StoreWorkerRepository workers = mock(StoreWorkerRepository.class);
    AttendanceRepository attendance = mock(AttendanceRepository.class);
    StoreRepository stores = mock(StoreRepository.class);
    StorePartRepository parts = mock(StorePartRepository.class);
    NotificationPublisher notifier = mock(NotificationPublisher.class);
    StaffService svc = new StaffService(workers, schedules, attendance, subs, applicants, parts,
            mock(StoreService.class), access, mock(StaffAssembler.class), mock(ScheduleDeleter.class), notifier,
            mock(StaffWageService.class), stores);

    Store store = new Store();
    User owner = user(1, "사장");
    User leaver = user(9, "떠남");
    StoreWorker sw;

    @BeforeEach
    void setUp() {
        store.setId(1);
        store.setEmployer(owner);
        sw = worker(3, leaver, StoreWorker.Status.ACTIVE);
        when(stores.findByIdForUpdate(1)).thenReturn(Optional.of(store));
        when(access.requireOwner(1, 1)).thenReturn(store);
        when(workers.findByIdAndStoreIdForUpdate(3, 1)).thenReturn(Optional.of(sw));
        when(subs.findByStoreAndStatusIn(any(), any())).thenReturn(List.of());
        when(schedules.findFromDateByWorker(any(), any())).thenReturn(List.of());
    }

    @Test
    void removeSetsLeftAtToday() {
        svc.remove(1, 1, 3);

        assertEquals(StoreWorker.Status.LEFT, sw.getStatus());
        assertEquals(TimeUtil.today(), sw.getLeftAt());
    }

    @Test
    void locksStoreThenWorkerBeforeOwnerCheck() {
        svc.remove(1, 1, 3);

        InOrder order = inOrder(stores, access, workers);
        order.verify(stores).findByIdForUpdate(1);
        order.verify(access).requireOwner(1, 1);
        order.verify(workers).findByIdAndStoreIdForUpdate(3, 1);
    }

    @Test
    void removePendingOrRejectedIsConflict() {
        for (StoreWorker.Status st : List.of(StoreWorker.Status.PENDING, StoreWorker.Status.REJECTED)) {
            sw.setStatus(st);
            ApiException e = assertThrows(ApiException.class, () -> svc.remove(1, 1, 3));
            assertEquals("NOT_ACTIVE", e.getCode());
            assertEquals(st, sw.getStatus());
        }
        verifyNoInteractions(notifier);
    }

    @Test
    void removeLeftIsIdempotentWithoutNotification() {
        sw.setStatus(StoreWorker.Status.LEFT);
        svc.remove(1, 1, 3);
        verifyNoInteractions(notifier);
    }

    @Test
    void removeClosesOpenAttendance() {
        Schedule s = new Schedule();
        s.setStoreWorker(sw);
        s.setWorkDate(TimeUtil.today());
        s.setStartMin(0);
        s.setEndMin(1439);
        Attendance a = new Attendance();
        a.setSchedule(s);
        a.setActualStart(TimeUtil.now().minusMinutes(30));
        when(attendance.findOpenByUserAndStore(9, 1)).thenReturn(List.of(a));

        svc.remove(1, 1, 3);

        assertNotNull(a.getActualEnd());
        assertFalse(a.getActualEnd().isBefore(a.getActualStart()));
        assertEquals(StaffService.LEAVE_CLOCK_OUT_REASON, a.getEditReason());
    }

    @Test
    void takerLeavesPromotesEarliestApplicantAndNotifies() {
        User requester = user(20, "요청");
        User early = user(21, "먼저");
        User late = user(22, "나중");
        SubstituteRequest r = request(50, requester, leaver);
        when(subs.findByStoreAndStatusIn(any(), any())).thenReturn(List.of(r));
        when(applicants.existsByRequestIdAndApplicantId(50, 9)).thenReturn(true);
        LocalDateTime t = LocalDateTime.of(2026, 11, 1, 9, 0);
        when(applicants.findByRequestId(50)).thenReturn(List.of(
                applicant(2, r, late, t.plusMinutes(5)), applicant(3, r, early, t.plusMinutes(1))));

        svc.remove(1, 1, 3);

        assertSame(early, r.getTaker());
        assertEquals(SubstituteRequest.Status.ACCEPTED, r.getStatus());
        verify(applicants).deleteByRequestIdAndApplicantId(50, 9);
        verify(notifier).send(eq(store), eq(early), eq(Notification.Type.SUB), eq("대타 후보가 됐어요"), any(), eq(50));
        verify(notifier).send(eq(store), eq(requester), eq(Notification.Type.SUB), startsWith("대타 후보가 먼저님"), any(), eq(50));
        verify(notifier).sendToOwner(eq(store), eq(Notification.Type.SUB), startsWith("대타 후보가 먼저님"), any(), eq(50));
    }

    @Test
    void takerLeavesWithoutOthersReopens() {
        User requester = user(20, "요청");
        SubstituteRequest r = request(50, requester, leaver);
        when(subs.findByStoreAndStatusIn(any(), any())).thenReturn(List.of(r));
        when(applicants.existsByRequestIdAndApplicantId(50, 9)).thenReturn(true);
        when(applicants.findByRequestId(50)).thenReturn(List.of());

        svc.remove(1, 1, 3);

        assertNull(r.getTaker());
        assertEquals(SubstituteRequest.Status.OPEN, r.getStatus());
        verify(notifier).send(eq(store), eq(requester), eq(Notification.Type.SUB), startsWith("대타 후보가 퇴사"), any(), eq(50));
    }

    @Test
    void requesterLeavesNotifiesApplicants() {
        User applicantUser = user(21, "지원");
        SubstituteRequest r = request(50, leaver, applicantUser);
        when(subs.findByStoreAndStatusIn(any(), any())).thenReturn(List.of(r));
        when(applicants.findByRequestId(50)).thenReturn(List.of(applicant(2, r, applicantUser, null)));

        svc.remove(1, 1, 3);

        assertEquals(SubstituteRequest.Status.CANCELLED, r.getStatus());
        verify(notifier).send(eq(store), eq(applicantUser), eq(Notification.Type.SUB),
                eq("떠남님의 대타 요청이 취소됐어요"), any(), eq(50));
    }

    @Test
    void approveClosesOtherPendingRowsOfSamePerson() {
        sw.setStatus(StoreWorker.Status.PENDING);
        StoreWorker dup = worker(4, leaver, StoreWorker.Status.PENDING);
        when(workers.existsByStoreIdAndWorkerIdAndStatus(1, 9, StoreWorker.Status.ACTIVE)).thenReturn(false);
        when(workers.findByStoreIdAndWorkerIdAndStatus(1, 9, StoreWorker.Status.PENDING)).thenReturn(List.of(sw, dup));

        svc.approve(1, 1, 3, new StaffCommandDto.ApproveRequest(11000, null));

        assertEquals(StoreWorker.Status.ACTIVE, sw.getStatus());
        assertEquals(StoreWorker.Status.REJECTED, dup.getStatus());
        verify(notifier, times(1)).sendToStaff(any(), any(), any(), any(), any(), anyInt());
        verify(stores).findByIdForUpdate(1);
    }

    @Test
    void approveWhenAlreadyActiveIsConflict() {
        sw.setStatus(StoreWorker.Status.PENDING);
        when(workers.existsByStoreIdAndWorkerIdAndStatus(1, 9, StoreWorker.Status.ACTIVE)).thenReturn(true);
        ApiException e = assertThrows(ApiException.class,
                () -> svc.approve(1, 1, 3, new StaffCommandDto.ApproveRequest(11000, null)));
        assertEquals("ALREADY_ACTIVE", e.getCode());
    }

    @Test
    void rejectAfterApproveIsConflict() {
        // 직렬화된 두 번째 요청은 잠근 행에서 바뀐 상태를 본다.
        sw.setStatus(StoreWorker.Status.ACTIVE);
        ApiException e = assertThrows(ApiException.class, () -> svc.reject(1, 1, 3));
        assertEquals("NOT_PENDING", e.getCode());
        verify(stores).findByIdForUpdate(1);
        verifyNoInteractions(notifier);
    }

    @Test
    void blankPartBecomesEmpty() {
        svc.update(1, 1, 3, new StaffCommandDto.UpdateRequest(null, null, null, null, "   ", null, null, null, null));
        assertEquals("", sw.getPart());
    }

    @Test
    void workHoursMessagesMatchCause() {
        assertEquals("출근 시각은 0시부터 23시 59분 사이여야 해요", workHoursError(-1, 100));
        assertEquals("퇴근 시각은 출근 시각보다 늦어야 해요", workHoursError(600, 600));
        assertEquals("근무 시간은 24시간을 넘을 수 없어요", workHoursError(0, 1441));
    }

    @Test
    void wageAboveCapRejected() {
        ApiException e = assertThrows(ApiException.class, () -> svc.update(1, 1, 3,
                new StaffCommandDto.UpdateRequest(1_000_001, null, null, null, null, null, null, null, null)));
        assertEquals("WAGE_TOO_HIGH", e.getCode());
    }

    private String workHoursError(int start, int end) {
        java.util.ArrayList<StaffPrefs.WorkHours> hours = new java.util.ArrayList<>();
        hours.add(new StaffPrefs.WorkHours(start, end));
        for (int i = 0; i < 6; i++) hours.add(null);
        return assertThrows(ApiException.class, () -> svc.update(1, 1, 3,
                new StaffCommandDto.UpdateRequest(null, null, null, null, null, null, null, hours, null))).getMessage();
    }

    private static User user(int id, String name) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        return u;
    }

    private StoreWorker worker(int id, User u, StoreWorker.Status status) {
        StoreWorker w = new StoreWorker();
        w.setId(id);
        w.setWorker(u);
        w.setStore(store);
        w.setStatus(status);
        return w;
    }

    private SubstituteRequest request(int id, User requester, User taker) {
        Schedule s = new Schedule();
        s.setWorkDate(TimeUtil.today().plusDays(3));
        s.setStartMin(600);
        s.setEndMin(900);
        SubstituteRequest r = new SubstituteRequest();
        r.setId(id);
        r.setSchedule(s);
        r.setRequester(requester);
        r.setTaker(taker);
        r.setStatus(SubstituteRequest.Status.ACCEPTED);
        return r;
    }

    private static SubstituteApplicant applicant(int id, SubstituteRequest r, User u, LocalDateTime at) {
        SubstituteApplicant a = new SubstituteApplicant();
        a.setId(id);
        a.setRequest(r);
        a.setApplicant(u);
        a.setAppliedAt(at);
        return a;
    }
}
