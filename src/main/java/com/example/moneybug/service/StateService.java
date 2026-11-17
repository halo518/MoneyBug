package com.example.moneybug.service;

import com.example.moneybug.dto.AppStateDto;
import com.example.moneybug.dto.AttendanceViewDto;
import com.example.moneybug.dto.NoticeViewDto;
import com.example.moneybug.dto.NotificationDto;
import com.example.moneybug.dto.ScheduleDto;
import com.example.moneybug.dto.StaffDto;
import com.example.moneybug.dto.StoreDetailDto;
import com.example.moneybug.dto.SubstituteRequestDto;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** 작성 중(DRAFT) 달의 근무는 사장님만 본다. 월 메타가 없는 달은 그대로 보인다. */
    static List<Schedule> visibleShifts(List<Schedule> shifts, java.util.Set<String> draftMonths) {
        if (draftMonths.isEmpty()) return shifts;
        return shifts.stream()
                .filter(s -> !draftMonths.contains(YearMonth.from(s.getWorkDate()).toString())).toList();
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
        if (storeId != null) {
            if (owned.stream().anyMatch(s -> s.getId().equals(storeId))) {
                role = "employer";
                ownerStoreId = storeId;
            } else if (memberships.stream().anyMatch(m -> m.getStore().getId().equals(storeId))) {
                role = "employee";
                employeeStoreId = storeId;
            } else {
                throw ApiException.forbidden("이 매장에 접근할 수 없어요");
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
        // FE 시드와 같은 범위: 지난달 1일이 든 주의 월요일 ~ 다음 달 말일
        LocalDate from = today.minusMonths(1).withDayOfMonth(1).with(DayOfWeek.MONDAY);
        LocalDate to = YearMonth.from(today).plusMonths(1).atEndOfMonth();

        for (Store store : fullStores.values()) {
            boolean isOwner = store.getEmployer().getId().equals(userId);
            stores.add(storeService.detail(store, isOwner));
            persons.putIfAbsent(store.getEmployer().getId(), AppStateDto.Person.from(store.getEmployer()));

            for (StoreWorker sw : storeWorkerRepository.findByStoreIdAndStatusIn(store.getId(),
                    Arrays.asList(StoreWorker.Status.values()))) {
                if (!isOwner && sw.getStatus() != StoreWorker.Status.ACTIVE && !sw.getWorker().getId().equals(userId)) {
                    continue; // 직원에게는 다른 사람의 가입 신청·퇴사 기록을 주지 않는다
                }
                StaffDto dto = staffAssembler.toDto(sw);
                // 직원에게는 다른 직원의 연락처·시급·계약서를 주지 않는다
                staff.add(dto.forViewer(isOwner, userId));
                persons.putIfAbsent(sw.getWorker().getId(), AppStateDto.Person.from(sw.getWorker()));
            }

            List<MonthSchedule> months = monthScheduleRepository.findByStoreIdOrderByMonthAsc(store.getId());
            List<Schedule> storeShifts = scheduleRepository.findByStoreBetween(store.getId(), from, to);
            if (!isOwner) {
                storeShifts = visibleShifts(storeShifts, months.stream()
                        .filter(m -> m.getStatus() == MonthSchedule.Status.DRAFT)
                        .map(MonthSchedule::getMonth).collect(Collectors.toSet()));
            }
            storeShifts.forEach(s -> shifts.add(ScheduleDto.Response.from(s)));
            if (!storeShifts.isEmpty()) {
                for (Attendance a : attendanceRepository.findByScheduleIdIn(
                        storeShifts.stream().map(Schedule::getId).toList())) {
                    // 직원은 본인 출근 기록만 (동료의 일급·수정 사유 제외)
                    if (!isOwner && !a.getSchedule().getStoreWorker().getWorker().getId().equals(userId)) continue;
                    attendance.add(AttendanceCalc.view(a, now));
                }
            }

            // 범위 밖 근무의 대타는 뺀다 (FE 는 대타의 shiftId 로 근무를 찾는다)
            java.util.Set<Integer> shiftIds = new java.util.HashSet<>(storeShifts.stream().map(Schedule::getId).toList());
            substituteRequestRepository.findByStore(store.getId()).stream()
                    .filter(r -> shiftIds.contains(r.getSchedule().getId()))
                    .forEach(r -> subs.add(substituteAssembler.toDto(r)));
            notices.addAll(noticeService.list(userId, store.getId()).items());
            months.forEach(m -> schedules.add(MonthScheduleDto.Response.from(m)));
            scheduleTemplateRepository.findByStoreIdOrderByIdAsc(store.getId())
                    .forEach(t -> templates.add(ScheduleTemplateDto.Response.from(t)));
        }

        // 가입 대기 매장: 매장 정보와 내 직원 행만
        for (StoreWorker m : memberships) {
            if (fullStores.containsKey(m.getStore().getId())) continue;
            stores.add(storeService.detail(m.getStore(), false));
            staff.add(staffAssembler.toDto(m));
            persons.putIfAbsent(m.getStore().getEmployer().getId(),
                    AppStateDto.Person.from(m.getStore().getEmployer()));
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
