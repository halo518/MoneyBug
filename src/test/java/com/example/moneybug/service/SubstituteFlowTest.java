package com.example.moneybug.service;

import com.example.moneybug.dto.SubstituteRequestDto;
import com.example.moneybug.entity.*;
import com.example.moneybug.repository.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** QA 13·19·22·23·24: 잠금 순서, 상태 전이 경합, 재알림, 원래 근무자, 작성 중 달, 마감, 사유 길이. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubstituteFlowTest {

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
    User owner = user(1, "사장");
    User a = user(10, "요청자");
    User b = user(11, "지원자");
    User c = user(12, "동료");
    StoreWorker swA = staff(100, a);
    StoreWorker swB = staff(101, b);
    StoreWorker swC = staff(102, c);
    Schedule shift = new Schedule();
    SubstituteRequest req = new SubstituteRequest();

    @BeforeEach
    void setUp() {
        store.setId(1);
        store.setEmployer(owner);
        store.setSubstitutePush(true);
        shift.setId(5); shift.setStoreWorker(swA); shift.setStartMin(600); shift.setEndMin(900);
        shift.setKind(Schedule.Kind.WORK);
        shift.setWorkDate(LocalDate.now().plusDays(3));
        req.setId(7); req.setSchedule(shift); req.setRequester(a);
        req.setStatus(SubstituteRequest.Status.OPEN);
        when(stores.findByIdForUpdate(1)).thenReturn(Optional.of(store));
        when(requests.findByIdForUpdate(7)).thenReturn(Optional.of(req));
        when(access.requireOwner(1, 1)).thenReturn(store);
        when(access.store(1)).thenReturn(store);
        when(access.requireOwnerOrStaff(anyInt(), eq(1))).thenReturn(store);
        when(access.isOwner(store, 1)).thenReturn(true);
        when(access.requireActiveStaff(10, 1)).thenReturn(swA);
        when(access.requireActiveStaff(11, 1)).thenReturn(swB);
        when(access.requireActiveStaff(12, 1)).thenReturn(swC);
        when(access.activeStaff(10, 1)).thenReturn(Optional.of(swA));
        when(access.activeStaff(11, 1)).thenReturn(Optional.of(swB));
        when(access.activeStaff(12, 1)).thenReturn(Optional.of(swC));
        when(months.findByStoreIdOrderByMonthAsc(1)).thenReturn(List.of());
        when(storeWorkers.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(swA, swB, swC));
    }

    @Test
    void createLocksStoreBeforeDuplicateCheck() {
        when(schedules.findByIdAndStore(5, 1)).thenReturn(Optional.of(shift));
        when(requests.findByScheduleIdAndStatusIn(eq(5), any())).thenReturn(List.of(req));
        SubstituteRequestDto.Request body = new SubstituteRequestDto.Request();
        body.setReason("병원");
        ApiException e = assertThrows(ApiException.class, () -> service.create(10, 1, 5, body));
        assertEquals("ALREADY_REQUESTED", e.getCode());
        InOrder order = inOrder(stores, requests);
        order.verify(stores).findByIdForUpdate(1);
        order.verify(requests).findByScheduleIdAndStatusIn(eq(5), any());
    }

    @Test
    void draftMonthShiftIsInvisibleToStaff() {
        when(months.findByStoreIdOrderByMonthAsc(1)).thenReturn(List.of(draft(shift.getWorkDate())));
        when(schedules.findByIdAndStore(5, 1)).thenReturn(Optional.of(shift));
        when(requests.findByStore(1)).thenReturn(List.of(req));
        SubstituteRequestDto.Request body = new SubstituteRequestDto.Request();
        body.setReason("x");
        assertEquals(404, assertThrows(ApiException.class, () -> service.create(10, 1, 5, body)).getStatus().value());
        assertEquals(404, assertThrows(ApiException.class, () -> service.apply(11, 1, 7)).getStatus().value());
        assertTrue(service.list(11, 1, null, false, false).items().isEmpty());
        assertEquals(1, service.list(1, 1, null, false, false).items().size()); // 사장님은 본다
    }

    @Test
    void applyNotifiesOwnerWithRequesterName_andLocksStoreThenRequest() {
        service.apply(11, 1, 7);
        assertEquals(SubstituteRequest.Status.ACCEPTED, req.getStatus());
        verify(notifier).sendToOwner(eq(store), eq(Notification.Type.SUB), eq("지원자님이 요청자님 대타를 수락했어요"),
                anyString(), eq(7));
        InOrder order = inOrder(stores, requests, applicants);
        order.verify(stores).findByIdForUpdate(1);
        order.verify(requests).findByIdForUpdate(7);
        order.verify(applicants).existsByRequestIdAndApplicantId(7, 11);
    }

    @Test
    void duplicateApplyIsIdempotent() {
        when(applicants.existsByRequestIdAndApplicantId(7, 11)).thenReturn(true);
        service.apply(11, 1, 7);
        verify(applicants, never()).save(any());
        verifyNoInteractions(notifier);
    }

    @Test
    void approveThenRejectOrCancel_secondGetsInvalidState() {
        req.setTaker(b);
        req.setStatus(SubstituteRequest.Status.ACCEPTED);
        service.approve(1, 1, 7);
        assertEquals(SubstituteRequest.Status.APPROVED, req.getStatus());
        assertEquals("INVALID_STATE", assertThrows(ApiException.class, () -> service.reject(1, 1, 7)).getCode());
        assertEquals("INVALID_STATE", assertThrows(ApiException.class, () -> service.cancel(10, 1, 7)).getCode());
        assertEquals("INVALID_STATE", assertThrows(ApiException.class, () -> service.approve(1, 1, 7)).getCode());
        assertEquals(SubstituteRequest.Status.APPROVED, req.getStatus());
        assertEquals(swB, shift.getStoreWorker());
    }

    @Test
    void cancelThenApprove_shiftUnchanged() {
        req.setTaker(b);
        req.setStatus(SubstituteRequest.Status.ACCEPTED);
        service.cancel(10, 1, 7);
        assertEquals("INVALID_STATE", assertThrows(ApiException.class, () -> service.approve(1, 1, 7)).getCode());
        assertEquals(swA, shift.getStoreWorker());
        assertFalse(shift.isSubstitute());
    }

    @Test
    void secondSubstituteKeepsFirstOriginal_andBackToOriginalClearsFlag() {
        // 근무는 이미 A(원래) → B 로 대타된 상태. B 가 요청하고 C 가 받는다.
        shift.setStoreWorker(swB); shift.setOriginalStoreWorker(swA); shift.setSubstitute(true);
        req.setRequester(b); req.setTaker(c); req.setStatus(SubstituteRequest.Status.ACCEPTED);
        service.approve(1, 1, 7);
        assertEquals(swC, shift.getStoreWorker());
        assertEquals(swA, shift.getOriginalStoreWorker());

        // 다시 C 가 요청하고 원래 근무자 A 가 받으면 정규 근무로 돌아간다.
        SubstituteRequest r2 = new SubstituteRequest();
        r2.setId(8); r2.setSchedule(shift); r2.setRequester(c); r2.setTaker(a);
        r2.setStatus(SubstituteRequest.Status.ACCEPTED);
        when(requests.findByIdForUpdate(8)).thenReturn(Optional.of(r2));
        service.approve(1, 1, 8);
        assertEquals(swA, shift.getStoreWorker());
        assertNull(shift.getOriginalStoreWorker());
        assertFalse(shift.isSubstitute());
    }

    @Test
    void resendSkipsRequesterAndApplicants_andHasCooldown() {
        req.setNotifiedAt(LocalDateTime.now().minusHours(1));
        SubstituteApplicant ap = new SubstituteApplicant(); ap.setApplicant(b);
        when(applicants.findByRequestId(7)).thenReturn(List.of(ap));
        service.resend(10, 1, 7);
        verify(notifier).sendToStaff(eq(store), eq(swC), any(), anyString(), anyString(), eq(7));
        verify(notifier, never()).sendToStaff(eq(store), eq(swA), any(), anyString(), anyString(), any());
        verify(notifier, never()).sendToStaff(eq(store), eq(swB), any(), anyString(), anyString(), any());
        assertEquals("RESEND_TOO_SOON", assertThrows(ApiException.class, () -> service.resend(10, 1, 7)).getCode());
    }

    @Test
    void resendStartedShiftBlocked() {
        req.setNotifiedAt(LocalDateTime.now().minusHours(1));
        shift.setWorkDate(LocalDate.now().minusDays(1));
        assertEquals("SHIFT_STARTED", assertThrows(ApiException.class, () -> service.resend(10, 1, 7)).getCode());
        verifyNoInteractions(notifier);
    }

    @Test
    void availableExcludesOverlappingRequests() {
        when(requests.findByStore(1)).thenReturn(List.of(req));
        when(overlap.hasOverlap(swB, shift, 5)).thenReturn(true);
        assertTrue(service.list(11, 1, null, false, true).items().isEmpty());
        assertEquals(1, service.list(12, 1, null, false, true).items().size());
    }

    @Test
    void expireClosesStartedRequestAndNotifies() {
        req.setTaker(b);
        req.setStatus(SubstituteRequest.Status.ACCEPTED);
        LocalDateTime now = TimeUtil.start(shift).plusMinutes(1);
        assertTrue(service.expireIfStarted(1, 7, now));
        assertEquals(SubstituteRequest.Status.CANCELLED, req.getStatus());
        verify(notifier).send(eq(store), eq(a), any(), eq("대타 요청이 마감됐어요"), anyString(), eq(7));
        verify(notifier).send(eq(store), eq(b), any(), eq("대타 요청이 마감됐어요"), anyString(), eq(7));
        assertFalse(service.expireIfStarted(1, 7, now)); // 이미 마감
    }

    @Test
    void expireIgnoresFutureShift() {
        assertFalse(service.expireIfStarted(1, 7, TimeUtil.start(shift).minusMinutes(1)));
        assertEquals(SubstituteRequest.Status.OPEN, req.getStatus());
    }

    @Test
    void scheduleDeleterNotifiesRequesterAndApplicants() {
        ScheduleDeleter deleter = new ScheduleDeleter(schedules, requests, applicants, notifier);
        SubstituteApplicant ap = new SubstituteApplicant(); ap.setApplicant(b);
        when(requests.findByScheduleId(5)).thenReturn(List.of(req));
        when(applicants.findByRequestId(7)).thenReturn(List.of(ap));
        deleter.delete(shift);
        ArgumentCaptor<User> to = ArgumentCaptor.forClass(User.class);
        verify(notifier, times(2)).send(eq(store), to.capture(), eq(Notification.Type.SUB),
                eq("대타 요청이 취소됐어요"), anyString(), isNull());
        assertEquals(List.of(a, b), to.getAllValues());
        verify(schedules).delete(shift);
    }

    @Test
    void longReasonRejected() {
        Validator v = Validation.buildDefaultValidatorFactory().getValidator();
        SubstituteRequestDto.Request body = new SubstituteRequestDto.Request();
        body.setReason("가".repeat(1001));
        assertFalse(v.validate(body).isEmpty());
        body.setReason("가".repeat(1000));
        assertTrue(v.validate(body).isEmpty());
    }

    private StoreWorker staff(int id, User u) {
        StoreWorker sw = new StoreWorker();
        sw.setId(id); sw.setWorker(u); sw.setStore(store); sw.setStatus(StoreWorker.Status.ACTIVE);
        return sw;
    }

    private static User user(int id, String name) {
        User u = new User(); u.setId(id); u.setName(name);
        return u;
    }

    private MonthSchedule draft(LocalDate d) {
        MonthSchedule m = new MonthSchedule();
        m.setMonth(YearMonth.from(d).toString());
        m.setStatus(MonthSchedule.Status.DRAFT);
        return m;
    }
}
