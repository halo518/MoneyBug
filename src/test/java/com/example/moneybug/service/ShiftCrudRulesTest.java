package com.example.moneybug.service;

import com.example.moneybug.dto.ShiftCommandDto;
import com.example.moneybug.entity.*;
import com.example.moneybug.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 단건 근무 생성·수정·조회 규칙 (QA 2026-11-17: 06·13·20·22·23·25). DB 없이. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShiftCrudRulesTest {

    @Mock ScheduleRepository schedules;
    @Mock AttendanceRepository attendances;
    @Mock StoreWorkerRepository workers;
    @Mock StoreAccess access;
    @Mock NotificationPublisher notifier;
    @Mock OverlapChecker overlap;
    @Mock ScheduleDeleter deleter;
    @Mock SubstituteRequestRepository requests;
    @Mock SubstituteApplicantRepository applicants;
    @Mock MonthScheduleRepository months;
    @InjectMocks ShiftService service;

    Store store = new Store();
    StoreWorker sw = new StoreWorker();
    LocalDate future = LocalDate.now().plusDays(10);

    @BeforeEach
    void setUp() {
        store.setId(1);
        User u = new User(); u.setId(10); u.setName("알바");
        sw.setId(100); sw.setStore(store); sw.setWorker(u); sw.setStatus(StoreWorker.Status.ACTIVE);
        when(access.requireOwner(7, 1)).thenReturn(store);
        when(access.requireOwnerOrStaff(anyInt(), eq(1))).thenReturn(store);
        when(access.isOwner(store, 7)).thenReturn(true);
        when(access.staff(1, 100)).thenReturn(sw);
        when(months.findByStoreIdAndMonth(anyInt(), anyString())).thenReturn(Optional.empty());
        when(months.findByStoreIdOrderByMonthAsc(1)).thenReturn(List.of());
    }

    ShiftCommandDto.CreateRequest create(LocalDate d, int start, int end, Integer breakMin, Integer weeks) {
        return new ShiftCommandDto.CreateRequest(d, start, end, "마감", List.of(100), breakMin, weeks, null, null);
    }

    Schedule shift(LocalDate d, int start, int end, int breakMin) {
        Schedule s = new Schedule();
        s.setId(5); s.setStoreWorker(sw); s.setWorkDate(d); s.setStartMin(start); s.setEndMin(end);
        s.setBreakMin(breakMin); s.setKind(Schedule.Kind.WORK);
        when(schedules.findByIdAndStore(5, 1)).thenReturn(Optional.of(s));
        return s;
    }

    ShiftCommandDto.UpdateRequest patch(LocalDate d, Integer start, Integer end, Integer breakMin) {
        return new ShiftCommandDto.UpdateRequest(null, d, start, end, null, breakMin, null, null);
    }

    void draft(LocalDate d) {
        MonthSchedule m = new MonthSchedule();
        m.setMonth(YearMonth.from(d).toString());
        m.setStatus(MonthSchedule.Status.DRAFT);
        when(months.findByStoreIdAndMonth(1, m.getMonth())).thenReturn(Optional.of(m));
        when(months.findByStoreIdOrderByMonthAsc(1)).thenReturn(List.of(m));
    }

    static String code(Runnable r) {
        return assertThrows(ApiException.class, r::run).getCode();
    }

    @Test
    void createLocksStoreBeforeOwnerCheckAndAccepts2400() {
        var res = service.create(7, 1, create(future, 1200, 1440, null, null));
        assertEquals(1440, res.created().get(0).getEndMin());
        assertEquals(30, res.created().get(0).getBreakMin());
        InOrder order = inOrder(months, access);
        order.verify(months).lockStore(1);
        order.verify(access).requireOwner(7, 1);
        // refId 는 비운다 (FE 는 sub 알림 refId 를 대타 요청 id 로 읽는다)
        verify(notifier).sendToStaff(eq(store), eq(sw), eq(Notification.Type.SUB), eq("새 근무가 배정됐어요"),
                anyString(), isNull());
    }

    @Test
    void createRejectsBreakNotShorterThanShift() {
        assertEquals("INVALID_BREAK", code(() -> service.create(7, 1, create(future, 540, 600, 60, null))));
        verify(schedules, never()).save(any());
    }

    @Test
    void createRejectsDatesOutsideRange() {
        assertEquals("DATE_OUT_OF_RANGE",
                code(() -> service.create(7, 1, create(LocalDate.of(9999, 12, 25), 540, 600, null, 2))));
        assertEquals("DATE_OUT_OF_RANGE",
                code(() -> service.create(7, 1, create(LocalDate.of(2099, 12, 25), 540, 600, null, 2))));
        assertEquals("DATE_OUT_OF_RANGE",
                code(() -> service.create(7, 1, create(LocalDate.of(1, 1, 1), 540, 600, null, null))));
        verify(schedules, never()).save(any());
    }

    @Test
    void noAssignNoticeForPastOrDraftMonth() {
        service.create(7, 1, create(LocalDate.now().minusDays(3), 540, 600, null, null));
        draft(future);
        service.create(7, 1, create(future, 540, 600, null, null));
        verify(schedules, times(2)).save(any());
        verifyNoInteractions(notifier);
    }

    @Test
    void leftWorkerShiftCannotMoveDateButCanBeCorrected() {
        sw.setStatus(StoreWorker.Status.LEFT);
        LocalDate past = LocalDate.now().minusDays(5);
        Schedule s = shift(past, 540, 600, 0);
        assertEquals("NOT_ACTIVE", code(() -> service.update(7, 1, 5, patch(future, null, null, null))));
        assertEquals(past, s.getWorkDate());

        service.update(7, 1, 5, patch(past, 540, 660, null)); // 같은 날짜 안의 정정
        assertEquals(660, s.getEndMin());
        verifyNoInteractions(notifier); // 퇴사자에게 알림 없음
    }

    @Test
    void pastMoveAllowedLikeCreateWithoutNotice() {
        Schedule s = shift(future, 540, 600, 0);
        LocalDate past = LocalDate.now().minusDays(2);
        service.update(7, 1, 5, patch(past, null, null, null));
        assertEquals(past, s.getWorkDate());
        // 보이던 근무가 지난 날짜로 가면 "삭제됐어요" 만, "배정" 은 없다
        verify(notifier).sendToStaff(eq(store), eq(sw), eq(Notification.Type.SUB), eq("근무가 삭제됐어요"),
                anyString(), isNull());
        verify(notifier, never()).sendToStaff(any(), any(), any(), eq("새 근무가 배정됐어요"), any(), any());
    }

    @Test
    void updateBreakRules() {
        Schedule s = shift(future, 540, 1020, 60);
        assertEquals("INVALID_BREAK", code(() -> service.update(7, 1, 5, patch(null, 540, 600, 60))));
        service.update(7, 1, 5, patch(null, 540, 1200, null)); // 길이만 바꾸면 휴게 유지 (FE updateShift 와 같음)
        assertEquals(60, s.getBreakMin());
        service.update(7, 1, 5, patch(null, 540, 570, null)); // 휴게가 안 맞으면 법정 최소로
        assertEquals(0, s.getBreakMin());
        service.update(7, 1, 5, patch(null, 1200, 1440, null)); // 24:00 종료
        assertEquals(1440, s.getEndMin());
    }

    @Test
    void cancelledSubNotifiesApplicants() {
        shift(future, 540, 600, 0);
        SubstituteRequest r = new SubstituteRequest();
        r.setId(9);
        r.setStatus(SubstituteRequest.Status.ACCEPTED);
        User applicant = new User(); applicant.setId(11);
        SubstituteApplicant a = new SubstituteApplicant();
        a.setApplicant(applicant);
        when(requests.findByScheduleId(5)).thenReturn(List.of(r));
        when(applicants.findByRequestId(9)).thenReturn(List.of(a));

        service.update(7, 1, 5, patch(null, 600, 660, null));

        assertEquals(SubstituteRequest.Status.CANCELLED, r.getStatus());
        verify(notifier).send(eq(store), eq(applicant), eq(Notification.Type.SUB), anyString(), anyString(), eq(9));
        verify(notifier).sendToStaff(eq(store), eq(sw), eq(Notification.Type.SUB), eq("근무가 수정됐어요"),
                anyString(), isNull());
    }

    @Test
    void staffCannotSeeDraftMonthShifts() {
        Schedule s = shift(future, 540, 600, 0);
        when(schedules.findByStoreBetween(eq(1), any(), any())).thenReturn(List.of(s));
        draft(future);

        assertTrue(service.list(8, 1, future, future, null, false).items().isEmpty());
        assertThrows(ApiException.class, () -> service.get(8, 1, 5));
        assertEquals(1, service.list(7, 1, future, future, null, false).items().size());
        assertEquals("5", String.valueOf(service.get(7, 1, 5).getId()));
    }

    @Test
    void dtoAccepts2400AndRejectsNullStaffId() {
        try (var f = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var v = f.getValidator();
            assertTrue(v.validate(create(future, 1200, 1440, null, null)).isEmpty());
            assertFalse(v.validate(create(future, 1200, 1441, null, null)).isEmpty());
            assertTrue(v.validate(patch(null, 1200, 1440, null)).isEmpty());
            assertFalse(v.validate(patch(null, 1440, 60, null)).isEmpty());
            var nullId = new java.util.ArrayList<Integer>(); nullId.add(null);
            assertFalse(v.validate(new ShiftCommandDto.CreateRequest(future, 540, 600, null, nullId,
                    null, null, null, null)).isEmpty());
        }
    }
}
