package com.example.moneybug.service;

import com.example.moneybug.dto.AppStateDto;
import com.example.moneybug.dto.AttendanceViewDto;
import com.example.moneybug.dto.NoticeViewDto;
import com.example.moneybug.dto.NotificationDto;
import com.example.moneybug.dto.ScheduleDto;
import com.example.moneybug.dto.StaffDto;
import com.example.moneybug.dto.StoreDetailDto;
import com.example.moneybug.dto.SubstituteRequestDto;
import com.example.moneybug.dto.UserDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.MonthScheduleRepository;
import com.example.moneybug.repository.NotificationRepository;
import com.example.moneybug.repository.ScheduleTemplateRepository;
import com.example.moneybug.dto.MonthScheduleDto;
import com.example.moneybug.dto.ScheduleTemplateDto;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.SubstituteRequestRepository;
import com.example.moneybug.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GET /me/state — FE AppState 한 덩어리. 기존 조회 로직·DTO 를 조합만 한다.
 * 범위: 내가 소유한 매장 + 소속 매장(ACTIVE 는 전체 데이터, PENDING 은 매장 정보와 내 직원 행만).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StateService {

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final ScheduleRepository scheduleRepository;
    private final AttendanceRepository attendanceRepository;
    private final SubstituteRequestRepository substituteRequestRepository;
    private final NotificationRepository notificationRepository;
    private final MonthScheduleRepository monthScheduleRepository;
    private final ScheduleTemplateRepository scheduleTemplateRepository;
    private final StoreService storeService;
    private final NoticeService noticeService;
    private final StaffAssembler staffAssembler;
    private final SubstituteAssembler substituteAssembler;

    /**
     * 작성 중(DRAFT) 달의 앞으로 올 근무는 사장님만 본다. 월 메타가 없는 달은 그대로 보인다.
     * 오늘·지난 근무와 출근 기록이 있는 근무는 DRAFT 달이어도 보인다
     * (FE myTodayShift·myOpenAttendance 가 state 의 근무·출근으로 출퇴근 버튼을 정한다).
     */
    static List<Schedule> visibleShifts(List<Schedule> shifts, Set<String> draftMonths,
                                        Set<Integer> attendedShiftIds, LocalDate today) {
        if (draftMonths.isEmpty()) return shifts;
        return shifts.stream()
                .filter(s -> !s.getWorkDate().isAfter(today) || attendedShiftIds.contains(s.getId())
                        || !draftMonths.contains(YearMonth.from(s.getWorkDate()).toString())).toList();
    }

    /** 보건증 필드를 비운 Person (본인·재직 직원의 사장님이 아닌 사람에게). */
    static AppStateDto.Person withoutCert(AppStateDto.Person p) {
        return new AppStateDto.Person(p.id(), p.name(), p.fullName(), null, List.of(), false);
    }

    /** 보건증 필드를 비운 직원 행. FE 는 staff[].person 을 읽지 않지만 응답에 남기지 않는다. */
    static StaffDto withoutCert(StaffDto d) {
        UserDto.Response p = d.person();
        if (p == null) return d;
        UserDto.Response q = UserDto.Response.builder()
                .id(p.getId()).kakaoId(p.getKakaoId()).name(p.getName()).fullName(p.getFullName())
                .email(p.getEmail()).profileImage(p.getProfileImage()).contact(p.getContact())
                .phoneConsent(p.isPhoneConsent()).certAlertDays(List.of()).build();
        return new StaffDto(d.id(), d.storeId(), d.personId(), q, d.part(), d.kind(), d.hourlyWage(), d.taxType(),
                d.weeklyHolidayAuto(), d.premium(), d.tone(), d.status(), d.joinedAt(), d.requestedAt(),
                d.contract(), d.fixedParts(), d.workHours(), d.availHours(), d.wages(), d.leftAt());
    }

    /** withCert 면 보건증까지 덮어쓰고, 아니면 아직 없을 때만 보건증 없이 넣는다. */
    private static void putPerson(Map<Integer, AppStateDto.Person> persons, User u, boolean withCert) {
        AppStateDto.Person p = AppStateDto.Person.from(u);
        if (withCert) persons.put(u.getId(), p);
        else persons.putIfAbsent(u.getId(), withoutCert(p));
    }

    public AppStateDto.Response getState(Integer userId, Integer storeId) {
        User me = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요"));
        List<Store> owned = storeRepository.findByEmployerId(userId).stream()
                .sorted(Comparator.comparing(Store::getId)).toList();
        List<StoreWorker> memberships = storeWorkerRepository
                .findByWorkerIdAndStatusIn(userId, List.of(StoreWorker.Status.ACTIVE, StoreWorker.Status.PENDING))
                .stream().sorted(Comparator.comparing(StoreWorker::getId)).toList();

        // 지금 보는 역할·매장 (설계서 §1.2: 역할은 관계로 정해진다)
        StoreWorker activeMembership = memberships.stream()
                .filter(m -> m.getStatus() == StoreWorker.Status.ACTIVE).findFirst()
                .orElse(memberships.isEmpty() ? null : memberships.get(0));
        String role;
        Integer ownerStoreId = owned.isEmpty() ? null : owned.get(0).getId();
        Integer employeeStoreId = activeMembership == null ? null : activeMembership.getStore().getId();
        // 거절된 매장을 지정하면(가입 대기 화면 새로고침) 내 거절 행만 더 준다. 세션은 기본 그대로.
        StoreWorker rejected = null;
        if (storeId != null) {
            if (owned.stream().anyMatch(s -> s.getId().equals(storeId))) {
                role = "employer";
                ownerStoreId = storeId;
            } else if (memberships.stream().anyMatch(m -> m.getStore().getId().equals(storeId))) {
                role = "employee";
                employeeStoreId = storeId;
            } else {
                // 그 매장의 내 마지막 행이 거절일 때만 (퇴사·예전 거절 뒤 재가입은 아님)
                rejected = storeWorkerRepository
                        .findByWorkerIdAndStatusIn(userId, Arrays.asList(StoreWorker.Status.values())).stream()
                        .filter(m -> m.getStore().getId().equals(storeId))
                        .max(Comparator.comparing(StoreWorker::getId))
                        .filter(m -> m.getStatus() == StoreWorker.Status.REJECTED).orElse(null);
                if (rejected == null) throw ApiException.forbidden("이 매장에 접근할 수 없어요");
                role = ownerStoreId != null ? "employer" : "employee";
            }
        } else {
            role = ownerStoreId != null ? "employer" : "employee";
        }

        // 매장별로 모은다. 같은 매장을 소유·소속 둘 다 해도 한 번만.
        Map<Integer, Store> fullStores = new LinkedHashMap<>();
        owned.forEach(s -> fullStores.put(s.getId(), s));
        memberships.stream().filter(m -> m.getStatus() == StoreWorker.Status.ACTIVE)
                .forEach(m -> fullStores.putIfAbsent(m.getStore().getId(), m.getStore()));

        List<StoreDetailDto.Response> stores = new ArrayList<>();
        List<StaffDto> staff = new ArrayList<>();
        List<ScheduleDto.Response> shifts = new ArrayList<>();
        List<AttendanceViewDto.View> attendance = new ArrayList<>();
        List<SubstituteRequestDto.Response> subs = new ArrayList<>();
        List<NoticeViewDto.View> notices = new ArrayList<>();
        List<MonthScheduleDto.Response> schedules = new ArrayList<>();
        List<ScheduleTemplateDto.Response> templates = new ArrayList<>();
        Map<Integer, AppStateDto.Person> persons = new LinkedHashMap<>();
        persons.put(me.getId(), AppStateDto.Person.from(me));

        LocalDate today = TimeUtil.today();
        LocalDateTime now = LocalDateTime.now();
        // FE 시드와 같은 범위: 지난달 1일이 든 주의 월요일 ~ 3개월 뒤 말일
        LocalDate from = today.minusMonths(1).withDayOfMonth(1).with(DayOfWeek.MONDAY);
        LocalDate to = YearMonth.from(today).plusMonths(3).atEndOfMonth();

        for (Store store : fullStores.values()) {
            boolean isOwner = store.getEmployer().getId().equals(userId);
            stores.add(storeService.detail(store, isOwner));
            putPerson(persons, store.getEmployer(), false);

            List<MonthSchedule> months = monthScheduleRepository.findByStoreIdOrderByMonthAsc(store.getId());
            List<Schedule> storeShifts = scheduleRepository.findByStoreBetween(store.getId(), from, to);
            List<Attendance> storeAttendance = storeShifts.isEmpty() ? List.of()
                    : attendanceRepository.findByScheduleIdIn(storeShifts.stream().map(Schedule::getId).toList());
            if (!isOwner) {
                storeShifts = visibleShifts(storeShifts, months.stream()
                                .filter(m -> m.getStatus() == MonthSchedule.Status.DRAFT)
                                .map(MonthSchedule::getMonth).collect(Collectors.toSet()),
                        storeAttendance.stream().map(a -> a.getSchedule().getId()).collect(Collectors.toSet()),
                        today);
            }
            storeShifts.forEach(s -> shifts.add(ScheduleDto.Response.from(s)));
            for (Attendance a : storeAttendance) {
                // 직원은 본인 출근 기록만 (동료의 일급·수정 사유 제외)
                if (!isOwner && !a.getSchedule().getStoreWorker().getWorker().getId().equals(userId)) continue;
                attendance.add(AttendanceCalc.view(a, now));
            }

            // 범위 밖 근무의 대타는 뺀다 (FE 는 대타의 shiftId 로 근무를 찾는다)
            Set<Integer> shiftIds = new HashSet<>(storeShifts.stream().map(Schedule::getId).toList());
            List<SubstituteRequestDto.Response> storeSubs = substituteRequestRepository.findByStore(store.getId()).stream()
                    .filter(r -> shiftIds.contains(r.getSchedule().getId()))
                    .map(substituteAssembler::toDto)
                    .filter(d -> d.getRequesterId() != null).toList();
            subs.addAll(storeSubs);

            // 보내는 근무·대타가 가리키는 직원 행 (퇴사한 동료도 FE 가 staffById(...)! 로 찾는다)
            Set<Integer> referenced = new HashSet<>();
            for (Schedule s : storeShifts) {
                referenced.add(s.getStoreWorker().getId());
                if (s.getOriginalStoreWorker() != null) referenced.add(s.getOriginalStoreWorker().getId());
            }
            for (SubstituteRequestDto.Response d : storeSubs) {
                referenced.add(d.getRequesterId());
                if (d.getAcceptedId() != null) referenced.add(d.getAcceptedId());
                if (d.getApplicantIds() != null) referenced.addAll(d.getApplicantIds());
            }
            for (StoreWorker sw : storeWorkerRepository.findByStoreIdAndStatusIn(store.getId(),
                    Arrays.asList(StoreWorker.Status.values()))) {
                boolean isMe = sw.getWorker().getId().equals(userId);
                if (!isOwner && sw.getStatus() != StoreWorker.Status.ACTIVE && !isMe && !referenced.contains(sw.getId())) {
                    continue; // 직원에게는 다른 사람의 가입 신청·퇴사 기록을 주지 않는다
                }
                // 보건증은 본인과, 재직 중인 직원에 한해 사장님만 본다
                boolean withCert = isMe || (isOwner && sw.getStatus() == StoreWorker.Status.ACTIVE);
                // 직원에게는 다른 직원의 연락처·시급·계약서를 주지 않는다
                StaffDto dto = staffAssembler.toDto(sw).forViewer(isOwner, userId);
                staff.add(withCert ? dto : withoutCert(dto));
                putPerson(persons, sw.getWorker(), withCert);
            }

            notices.addAll(noticeService.list(userId, store.getId()).items());
            months.forEach(m -> schedules.add(MonthScheduleDto.Response.from(m)));
            // 템플릿은 사장님 전용 (직원에게 작성 중 근무 패턴을 주지 않는다)
            if (isOwner) {
                scheduleTemplateRepository.findByStoreIdOrderByIdAsc(store.getId())
                        .forEach(t -> templates.add(ScheduleTemplateDto.Response.from(t)));
            }
        }

        // 가입 대기 매장: 매장 정보와 내 직원 행만
        for (StoreWorker m : memberships) {
            if (fullStores.containsKey(m.getStore().getId())) continue;
            stores.add(storeService.detail(m.getStore(), false));
            staff.add(staffAssembler.toDto(m));
            putPerson(persons, m.getStore().getEmployer(), false);
        }
        // 거절된 매장(지정했을 때만): 매장 정보와 내 거절 행만. FE 가입 대기 화면이 거절 안내를 띄운다.
        if (rejected != null && !fullStores.containsKey(storeId)) {
            stores.add(storeService.detail(rejected.getStore(), false));
            staff.add(staffAssembler.toDto(rejected));
            putPerson(persons, rejected.getStore().getEmployer(), false);
        }

        List<NotificationDto.Response> notifications = notificationRepository
                .findByToUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(NotificationDto.Response::from).toList();

        AppStateDto.Session session = new AppStateDto.Session(true, role,
                String.valueOf(userId), String.valueOf(userId),
                employeeStoreId == null ? "" : String.valueOf(employeeStoreId),
                ownerStoreId == null ? "" : String.valueOf(ownerStoreId));

        return new AppStateDto.Response(AppStateDto.FE_STATE_VERSION, 1, session,
                new ArrayList<>(persons.values()), stores, staff, shifts, attendance, subs, notices, notifications, schedules, templates);
    }
}
