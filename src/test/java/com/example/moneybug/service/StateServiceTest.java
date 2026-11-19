package com.example.moneybug.service;

import com.example.moneybug.dto.AppStateDto;
import com.example.moneybug.dto.NoticeViewDto;
import com.example.moneybug.dto.StaffDto;
import com.example.moneybug.dto.StoreDetailDto;
import com.example.moneybug.dto.SubstituteRequestDto;
import com.example.moneybug.dto.UserDto;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.ScheduleTemplate;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.MonthScheduleRepository;
import com.example.moneybug.repository.NotificationRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.ScheduleTemplateRepository;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.SubstituteRequestRepository;
import com.example.moneybug.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** /me/state 노출 범위: 보건증·퇴사 동료 참조·거절 행·템플릿·작성 중 달. */
class StateServiceTest {

    UserRepository users = mock(UserRepository.class);
    StoreRepository storeRepo = mock(StoreRepository.class);
    StoreWorkerRepository workers = mock(StoreWorkerRepository.class);
    ScheduleRepository schedules = mock(ScheduleRepository.class);
    SubstituteRequestRepository subRepo = mock(SubstituteRequestRepository.class);
    ScheduleTemplateRepository templates = mock(ScheduleTemplateRepository.class);
    StoreService storeService = mock(StoreService.class);
    NoticeService noticeService = mock(NoticeService.class);
    StaffAssembler staffAssembler = mock(StaffAssembler.class);
    SubstituteAssembler subAssembler = mock(SubstituteAssembler.class);
    StateService svc = new StateService(users, storeRepo, workers, schedules, mock(AttendanceRepository.class),
            subRepo, mock(NotificationRepository.class), mock(MonthScheduleRepository.class), templates,
            storeService, noticeService, staffAssembler, subAssembler);

    User owner = user(1), alba = user(2), gone = user(3), applicant = user(4);
    Store store = new Store();
    StoreWorker swAlba, swGone, swApplicant;
    List<StoreWorker> storeRows = new ArrayList<>();

    static User user(int id) {
        User u = new User();
        u.setId(id);
        u.setName("u" + id);
        u.setCertExpiry(LocalDate.of(2027, 1, 1));
        u.setCertPhotoUrl("p.png");
        return u;
    }

    StoreWorker row(int id, User u, StoreWorker.Status st) {
        StoreWorker sw = new StoreWorker();
        sw.setId(id);
        sw.setStore(store);
        sw.setWorker(u);
        sw.setStatus(st);
        return sw;
    }

    static StaffDto dto(StoreWorker sw) {
        return new StaffDto(sw.getId(), sw.getStore().getId(), sw.getWorker().getId(),
                UserDto.Response.from(sw.getWorker()), "", null, 10000, null, true, false, null,
                sw.getStatus(), null, null, null, List.of(), List.of(), List.of(), List.of(), null);
    }

    @BeforeEach
    void setUp() {
        store.setId(10);
        store.setEmployer(owner);
        swAlba = row(100, alba, StoreWorker.Status.ACTIVE);
        swGone = row(101, gone, StoreWorker.Status.LEFT);
        swApplicant = row(102, applicant, StoreWorker.Status.PENDING);
        storeRows.addAll(List.of(swAlba, swGone, swApplicant));
        for (User u : List.of(owner, alba, gone, applicant)) when(users.findById(u.getId())).thenReturn(Optional.of(u));
        when(workers.findByStoreIdAndStatusIn(eq(10), any())).thenReturn(storeRows);
        when(staffAssembler.toDto(any())).thenAnswer(i -> dto(i.getArgument(0)));
        when(storeService.detail(any(), anyBoolean())).thenReturn(mock(StoreDetailDto.Response.class));
        when(noticeService.list(any(), any())).thenReturn(new NoticeViewDto.ListResponse(List.of(), null, 0));
        ScheduleTemplate t = new ScheduleTemplate();
        t.setStore(store);
        when(templates.findByStoreIdOrderByIdAsc(10)).thenReturn(List.of(t));

        // 퇴사한 동료와 같은 날 지난 근무 + 그 동료가 올린 대타
        Schedule mine = shift(500, swAlba), his = shift(501, swGone);
        when(schedules.findByStoreBetween(eq(10), any(), any())).thenReturn(List.of(mine, his));
        SubstituteRequest r = new SubstituteRequest();
        r.setSchedule(his);
        when(subRepo.findByStore(10)).thenReturn(List.of(r));
        when(subAssembler.toDto(r)).thenReturn(SubstituteRequestDto.Response.builder().id(9).storeId(10)
                .shiftId(501).requesterId(101).applicantIds(List.of()).build());
    }

    Schedule shift(int id, StoreWorker sw) {
        Schedule s = new Schedule();
        s.setId(id);
        s.setStoreWorker(sw);
        s.setWorkDate(LocalDate.now().minusDays(1));
        s.setStartMin(600);
        s.setEndMin(900);
        return s;
    }

    AppStateDto.Person person(AppStateDto.Response r, int id) {
        return r.persons().stream().filter(p -> p.id() == id).findFirst().orElse(null);
    }

    @Test
    void ownerSeesCertOnlyOfActiveStaff() {
        when(storeRepo.findByEmployerId(1)).thenReturn(List.of(store));
        AppStateDto.Response r = svc.getState(1, null);
        assertNotNull(person(r, 2).certExpiry());
        assertNull(person(r, 3).certExpiry());
        assertNull(person(r, 4).certExpiry());
        StaffDto goneRow = r.staff().stream().filter(s -> s.id() == 101).findFirst().orElseThrow();
        assertNull(goneRow.person().getCertExpiry());
        assertEquals(1, r.templates().size());
    }

    @Test
    void staffGetsOwnCertOnlyLeftCoworkerReferencedAndNoTemplates() {
        when(workers.findByWorkerIdAndStatusIn(eq(2), any())).thenReturn(List.of(swAlba));
        // 다른 매장 동료(활성) 하나 더: 보건증이 빠져야 한다
        StoreWorker peer = row(103, user(5), StoreWorker.Status.ACTIVE);
        storeRows.add(peer);
        AppStateDto.Response r = svc.getState(2, null);

        assertNotNull(person(r, 2).certExpiry());
        assertNull(person(r, 5).certExpiry());
        assertFalse(person(r, 5).certHasPhoto());
        // 근무·대타가 가리키는 퇴사 동료 행은 있고, 가입 신청자는 없다
        Set<Integer> ids = new java.util.HashSet<>(r.staff().stream().map(StaffDto::id).toList());
        assertTrue(ids.contains(101));
        assertFalse(ids.contains(102));
        StaffDto goneRow = r.staff().stream().filter(s -> s.id() == 101).findFirst().orElseThrow();
        assertEquals(0, goneRow.hourlyWage());
        assertNull(goneRow.person().getCertExpiry());
        for (var s : r.shifts()) assertTrue(ids.contains(s.getMappingId()));
        for (var s : r.subs()) assertTrue(ids.contains(s.getRequesterId()));
        assertTrue(r.templates().isEmpty());
    }

    @Test
    void contactOnlyForSelfAndOwnerPhotoForAll() {
        for (User u : List.of(owner, alba)) {
            u.setContact("010-0000-000" + u.getId());
            u.setPhotoUrl("profile-photo/" + u.getId() + "/v" + u.getId() + ".png");
        }
        StoreWorker peer = row(103, user(5), StoreWorker.Status.ACTIVE);
        peer.getWorker().setContact("010-5555-5555");
        storeRows.add(peer);

        when(storeRepo.findByEmployerId(1)).thenReturn(List.of(store));
        AppStateDto.Response asOwner = svc.getState(1, null);
        assertEquals("010-0000-0001", person(asOwner, 1).contact());
        assertEquals("010-0000-0002", person(asOwner, 2).contact());

        when(workers.findByWorkerIdAndStatusIn(eq(2), any())).thenReturn(List.of(swAlba));
        AppStateDto.Response asAlba = svc.getState(2, null);
        assertEquals("010-0000-0002", person(asAlba, 2).contact()); // 본인
        assertNull(person(asAlba, 5).contact());                    // 동료
        assertNull(person(asAlba, 1).contact());                    // 사장님 연락처도 직원에게는 안 감
        assertTrue(person(asAlba, 1).hasPhoto());
        assertEquals("v1.png", person(asAlba, 1).photoVer());
        assertFalse(person(asAlba, 5).hasPhoto());
        assertNull(person(asAlba, 5).photoVer());
    }

    @Test
    void rejectedStoreGivesOwnRowWithoutSwitchingSession() {
        StoreWorker rej = row(104, alba, StoreWorker.Status.REJECTED);
        Store other = new Store();
        other.setId(20);
        other.setEmployer(owner);
        rej.setStore(other);
        when(workers.findByWorkerIdAndStatusIn(eq(2), any())).thenAnswer(i -> {
            List<?> st = i.getArgument(1);
            return st.contains(StoreWorker.Status.REJECTED) ? List.of(swAlba, rej) : List.of(swAlba);
        });
        AppStateDto.Response r = svc.getState(2, 20);
        assertEquals("10", r.session().employeeStoreId());
        assertTrue(r.staff().stream().anyMatch(s -> s.id() == 104 && s.status() == StoreWorker.Status.REJECTED));
        verify(storeService).detail(other, false);
        // 지정하지 않으면 거절 행은 없다
        assertFalse(svc.getState(2, null).staff().stream().anyMatch(s -> s.id() == 104));
    }

    @Test
    void reappliedAfterRejectionIsNotRejectedOrLeftStoreForbidden() {
        StoreWorker rej = row(104, alba, StoreWorker.Status.REJECTED), left = row(105, alba, StoreWorker.Status.LEFT);
        Store other = new Store();
        other.setId(20);
        other.setEmployer(owner);
        rej.setStore(other);
        left.setStore(other);
        when(workers.findByWorkerIdAndStatusIn(eq(2), any())).thenAnswer(i -> {
            List<?> st = i.getArgument(1);
            return st.contains(StoreWorker.Status.LEFT) ? List.of(swAlba, rej, left) : List.of(swAlba);
        });
        ApiException e = assertThrows(ApiException.class, () -> svc.getState(2, 20));
        assertEquals("FORBIDDEN", e.getCode());
    }

    @Test
    void draftMonthHidesOnlyFutureShiftsWithoutAttendance() {
        LocalDate today = LocalDate.of(2026, 11, 17);
        Schedule past = at(1, today.minusDays(1)), now = at(2, today), future = at(3, today.plusDays(1)),
                futureAttended = at(4, today.plusDays(2)), otherMonth = at(5, LocalDate.of(2026, 12, 1));
        assertEquals(List.of(past, now, futureAttended, otherMonth), StateService.visibleShifts(
                List.of(past, now, future, futureAttended, otherMonth), Set.of("2026-11"), Set.of(4), today));
    }

    static Schedule at(int id, LocalDate d) {
        Schedule s = new Schedule();
        s.setId(id);
        s.setWorkDate(d);
        return s;
    }
}
