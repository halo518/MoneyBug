package com.example.moneybug.service;

import com.example.moneybug.dto.CommonDto;
import com.example.moneybug.dto.SchedulePlanDto;
import com.example.moneybug.dto.ScheduleDto;
import com.example.moneybug.dto.ShiftCommandDto;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StorePart;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StorePartRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 근무표 (설계서 §4.5). shiftId = Schedule.id, staffId = StoreWorker.id. 시각은 하루 기준 분. */
@Service
@Transactional
@RequiredArgsConstructor
public class ShiftService {

    private static final int MAX_REPEAT_WEEKS = 52;
    private static final int MAX_RANGE_DAYS = 366;
    private static final int DEFAULT_WEEKLY_MAX_MIN = 40 * 60;

    private final ScheduleRepository scheduleRepository;
    private final AttendanceRepository attendanceRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final StorePartRepository storePartRepository;
    private final StoreAccess access;
    private final NotificationPublisher notifier;
    private final OverlapChecker overlapChecker;
    private final ScheduleDeleter scheduleDeleter;
    private final SchedulePlanStore planStore;
    private final StoreService storeService;

    // ---------------------------------------------------------------- 조회

    /** GET /shifts — 시작 시각순. workOnly=true 면 isWork 만(출퇴근·근태·급여·대타 화면용). */
    @Transactional(readOnly = true)
    public CommonDto.ListResponse<ScheduleDto.Response> list(Integer userId, Integer storeId, LocalDate from,
                                                             LocalDate to, Integer staffId, boolean workOnly) {
        access.requireOwnerOrStaff(userId, storeId);
        LocalDate start = from != null ? from : TimeUtil.today().withDayOfMonth(1);
        LocalDate end = to != null ? to : start.with(TemporalAdjusters.lastDayOfMonth());
        if (end.isBefore(start)) {
            throw ApiException.badRequest("INVALID_RANGE", "종료일이 시작일보다 빠를 수 없어요");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw ApiException.badRequest("RANGE_TOO_LONG", "조회 기간은 최대 366일이에요");
        }
        return CommonDto.ListResponse.of(scheduleRepository.findByStoreBetween(storeId, start, end).stream()
                .filter(s -> staffId == null || s.getStoreWorker().getId().equals(staffId))
                .filter(s -> !workOnly || s.isWork())
                .map(ScheduleDto.Response::from)
                .toList());
    }

    @Transactional(readOnly = true)
    public ScheduleDto.Response get(Integer userId, Integer storeId, Integer shiftId) {
        access.requireOwnerOrStaff(userId, storeId);
        return ScheduleDto.Response.from(findShift(storeId, shiftId));
    }

    // ---------------------------------------------------------------- 생성 · 수정 · 삭제

    /**
     * POST /shifts — 직원 수 x repeatWeeks 만큼 생성(여러 명이면 같은 근무, 반복이면 매주 같은 요일).
     * 휴게는 생략 시 자동. 실제 근무는 같은 직원의 다른 실제 근무와 겹치면 409.
     * 알림(첫 주만): 실제 근무를 받은 각 직원에게 SUB "새 근무가 배정됐어요".
     */
    public ShiftCommandDto.CreateResponse create(Integer userId, Integer storeId,
                                                 ShiftCommandDto.CreateRequest request) {
        Store store = access.requireOwner(userId, storeId);
        requireValidTime(request.startMin(), request.endMin());

        Schedule.Kind kind = request.kind() != null ? request.kind() : Schedule.Kind.WORK;
        boolean timeTbd = Boolean.TRUE.equals(request.timeTbd());
        int weeks = request.repeatWeeks() != null ? request.repeatWeeks() : 1;
        if (weeks > MAX_REPEAT_WEEKS) {
            throw ApiException.badRequest("REPEAT_TOO_LONG", "반복은 최대 " + MAX_REPEAT_WEEKS + "주까지예요");
        }
        int breakMin = request.breakMin() != null ? request.breakMin()
                : ScheduleDto.defaultBreakMin(request.startMin(), request.endMin());

        List<StoreWorker> staff = request.staffIds().stream().distinct()
                .map(id -> activeStaff(storeId, id)).toList();

        List<ScheduleDto.Response> created = new ArrayList<>();
        for (StoreWorker sw : staff) {
            for (int w = 0; w < weeks; w++) {
                Schedule s = new Schedule();
                s.setStoreWorker(sw);
                s.setWorkDate(request.date().plusWeeks(w));
                s.setStartMin(request.startMin());
                s.setEndMin(request.endMin());
                s.setPart(request.part());
                s.setBreakMin(breakMin);
                s.setKind(kind);
                s.setTimeTbd(timeTbd);

                overlapChecker.assertNoOverlap(sw, s, null);
                scheduleRepository.save(s);
                created.add(ScheduleDto.Response.from(s));

                if (w == 0 && s.isWork()) {
                    notifier.sendToStaff(store, sw, Notification.Type.SUB, "새 근무가 배정됐어요",
                            TimeUtil.label(s), s.getId());
                }
            }
        }
        return new ShiftCommandDto.CreateResponse(created);
    }

    /**
     * PATCH /shifts/{id} — 보낸 것만 반영. 지난 날짜로 이동 금지, 겹침 검사.
     * 알림 규칙: 근무였는데 담당자가 바뀌었거나 근무가 아니게 됨 -> 이전 담당자 "삭제됐어요" /
     * 근무인데 담당자가 바뀌었거나 새로 근무가 됨 -> 새 담당자 "새 근무가 배정됐어요" /
     * 같은 담당자인데 날짜·시간 라벨이 바뀜 -> "수정됐어요"
     */
    public ScheduleDto.Response update(Integer userId, Integer storeId, Integer shiftId,
                                       ShiftCommandDto.UpdateRequest request) {
        Store store = access.requireOwner(userId, storeId);
        Schedule s = findShift(storeId, shiftId);

        StoreWorker oldStaff = s.getStoreWorker();
        boolean wasWork = s.isWork();
        String oldLabel = TimeUtil.label(s);

        StoreWorker newStaff = oldStaff;
        if (request.staffId() != null && !request.staffId().equals(oldStaff.getId())) {
            newStaff = activeStaff(storeId, request.staffId());
        }
        LocalDate date = request.date() != null ? request.date() : s.getWorkDate();
        if (!date.equals(s.getWorkDate()) && date.isBefore(TimeUtil.today())) {
            throw ApiException.badRequest("PAST_DATE", "지난 날짜로는 옮길 수 없어요");
        }
        int startMin = request.startMin() != null ? request.startMin() : s.getStartMin();
        int endMin = request.endMin() != null ? request.endMin() : s.getEndMin();
        requireValidTime(startMin, endMin);

        Schedule.Kind kind = request.kind() != null ? request.kind() : s.getKind();
        boolean tbd = request.timeTbd() != null ? request.timeTbd() : s.isTimeTbd();

        // 겹침은 저장 전 후보로 검사한다 (자기 자신 제외).
        Schedule probe = OverlapChecker.probe(newStaff, date, startMin, endMin);
        probe.setKind(kind);
        probe.setTimeTbd(tbd);
        overlapChecker.assertNoOverlap(newStaff, probe, s.getId());

        s.setStoreWorker(newStaff);
        s.setWorkDate(date);
        s.setStartMin(startMin);
        s.setEndMin(endMin);
        s.setKind(kind);
        s.setTimeTbd(tbd);
        if (request.part() != null) s.setPart(request.part());
        if (request.breakMin() != null) s.setBreakMin(request.breakMin());

        boolean isWork = s.isWork();
        boolean staffChanged = !newStaff.getId().equals(oldStaff.getId());
        String newLabel = TimeUtil.label(s);

        if (wasWork && (staffChanged || !isWork)) {
            notifier.sendToStaff(store, oldStaff, Notification.Type.SUB, "근무가 삭제됐어요", oldLabel, s.getId());
        }
        if (isWork && (staffChanged || !wasWork)) {
            notifier.sendToStaff(store, newStaff, Notification.Type.SUB, "새 근무가 배정됐어요", newLabel, s.getId());
        } else if (isWork && wasWork && !oldLabel.equals(newLabel)) {
            notifier.sendToStaff(store, newStaff, Notification.Type.SUB, "근무가 수정됐어요", newLabel, s.getId());
        }
        return ScheduleDto.Response.from(s);
    }

    /**
     * DELETE /shifts/{id} — 존재하지 않으면 성공(멱등). 출근 기록이 있으면 409.
     * 부수효과: 진행 중 대타 요청 CANCELLED, 실제 근무였다면 담당자에게 SUB "근무가 삭제됐어요".
     */
    public void delete(Integer userId, Integer storeId, Integer shiftId) {
        Store store = access.requireOwner(userId, storeId);
        Optional<Schedule> found = scheduleRepository.findByIdAndStore(shiftId, storeId);
        if (found.isEmpty()) {
            return;
        }
        Schedule s = found.get();
        if (attendanceRepository.existsByScheduleId(s.getId())) {
            throw ApiException.conflict("ATTENDANCE_EXISTS", "출근 기록이 있는 근무는 삭제할 수 없어요");
        }
        if (s.isWork()) {
            notifier.sendToStaff(store, s.getStoreWorker(), Notification.Type.SUB, "근무가 삭제됐어요",
                    TimeUtil.label(s), null);
        }
        scheduleDeleter.delete(s);
    }

    // ---------------------------------------------------------------- 자동 배정 · 근무표 만들기

    /**
     * POST /schedule/auto-fill — 그 주 7일 중 오늘 이후만, 파트별 need 가 모자란 칸을 채운다.
     * 후보 = 유동 근무자 제외 재직자 중 그날 근무 없음 + 시간 안 겹침, 그 주 근무 분이 적은 사람부터.
     * 채울 사람이 없으면 중단(그때까지 채운 것은 저장). added=0 이면 변경 없음.
     */
    public ShiftCommandDto.AddedResponse autoFill(Integer userId, Integer storeId,
                                                  ShiftCommandDto.AutoFillRequest request) {
        access.requireOwner(userId, storeId);
        LocalDate monday = request.weekMonday();
        if (monday.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw ApiException.badRequest("INVALID_WEEK_MONDAY", "weekMonday 는 월요일이어야 해요");
        }
        LocalDate today = TimeUtil.today();
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            if (!monday.plusDays(i).isBefore(today)) dates.add(monday.plusDays(i));
        }
        if (dates.isEmpty()) {
            return new ShiftCommandDto.AddedResponse(0);
        }

        List<StorePart> parts = storePartRepository.findByStoreIdOrderByStartMinAsc(storeId);
        List<Integer> needs = parts.stream().map(StorePart::getNeed).toList();
        List<StoreWorker> staff = storeWorkerRepository.findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE);
        List<Schedule> all = new ArrayList<>(scheduleRepository.findByStoreBetween(storeId,
                monday.minusDays(1), monday.plusDays(7)));

        Fill fill = fill(staff, parts, needs, dates, Set.of(), null, false, true, all);
        fill.added().forEach(scheduleRepository::save);
        return new ShiftCommandDto.AddedResponse(fill.added().size());
    }

    /**
     * POST /schedule-plans — 미리보기(저장 안 함). 시작은 max(from, 오늘). 이미 있는 근무·겹침은 건드리지 않는다.
     * copyLastWeek 면 지난주 근무(실제 근무·대타 아님)를 요일마다 반복한 뒤 빈칸을 채운다.
     * 빈칸은 자기 파트 직원 먼저, 다음으로 그 주 근무 적은 사람. 주 최대 초과자는 제외. 못 채우면 unfilled 누적.
     */
    public SchedulePlanDto.Response planSchedule(Integer userId, Integer storeId, SchedulePlanDto.Request request) {
        access.requireOwner(userId, storeId);
        if (request.to().isBefore(request.from())) {
            throw ApiException.badRequest("INVALID_RANGE", "종료일이 시작일보다 빠를 수 없어요");
        }
        if (request.from().plusDays(MAX_RANGE_DAYS).isBefore(request.to())) {
            throw ApiException.badRequest("RANGE_TOO_LONG", "근무표 만들기는 최대 366일까지예요");
        }
        if (request.needs().stream().anyMatch(n -> n == null || n < 0)) {
            throw ApiException.badRequest("INVALID_NEED", "필요 인원은 0 이상이어야 해요");
        }
        int weeklyMax = request.weeklyMaxMin() != null ? request.weeklyMaxMin() : DEFAULT_WEEKLY_MAX_MIN;
        Set<Integer> excluded = request.excludeStaffIds() == null ? Set.of() : Set.copyOf(request.excludeStaffIds());

        LocalDate effectiveFrom = request.from().isBefore(TimeUtil.today()) ? TimeUtil.today() : request.from();
        List<StorePart> parts = storePartRepository.findByStoreIdOrderByStartMinAsc(storeId);
        List<StoreWorker> staff = storeWorkerRepository.findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE);
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = effectiveFrom; !d.isAfter(request.to()); d = d.plusDays(1)) dates.add(d);

        List<Schedule> all = new ArrayList<>(scheduleRepository.findByStoreBetween(storeId,
                TimeUtil.monday(effectiveFrom).minusDays(7), request.to().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                        .plusDays(1)));
        List<Schedule> added = new ArrayList<>();
        if (request.copyLastWeek()) {
            added.addAll(copyLastWeek(staff, dates, excluded, all));
        }
        Fill fill = fill(staff, parts, request.needs(), dates, excluded, weeklyMax, true, false, all);
        added.addAll(fill.added());

        // 범위 내 직원별 총 근무 분(기존 + 새 근무)과 새 근무 예상 인건비(기본급 기준)
        Map<Integer, Integer> minutesByStaff = new LinkedHashMap<>();
        for (Schedule s : all) {
            if (s.isWork() && !s.getWorkDate().isBefore(request.from()) && !s.getWorkDate().isAfter(request.to())) {
                minutesByStaff.merge(s.getStoreWorker().getId(), s.scheduledMinutes(), Integer::sum);
            }
        }
        int cost = added.stream().mapToInt(s -> TimeUtil.pay(s.scheduledMinutes(), s.getStoreWorker().getHourlyWage()))
                .sum();

        List<SchedulePlanStore.PlannedShift> planned = added.stream()
                .map(s -> new SchedulePlanStore.PlannedShift(s.getStoreWorker().getId(), s.getWorkDate(),
                        s.getStartMin(), s.getEndMin(), s.getPart(), s.getBreakMin()))
                .toList();
        String planId = planStore.save(new SchedulePlanStore.Plan(storeId, userId, request.from(), request.to(),
                planned, Instant.now()));

        return new SchedulePlanDto.Response(planId, request.from(), request.to(),
                added.stream().map(ScheduleDto.Response::from).toList(), fill.unfilled(), minutesByStaff, cost);
    }

    /**
     * POST /schedule-plans/{planId}/apply — 미리보기 근무를 실제로 저장하고 parts[].need 갱신.
     * 미리보기 이후 생긴 겹침은 건너뛴다. 부수효과: 직원별 1건 SUB "{N}월 근무가 추가됐어요"(body "n건 · 기간").
     */
    public ShiftCommandDto.AddedResponse applyPlan(Integer userId, Integer storeId, String planId,
                                                   SchedulePlanDto.ApplyRequest request) {
        Store store = access.requireOwner(userId, storeId);
        SchedulePlanStore.Plan plan = planStore.find(planId)
                .filter(p -> p.storeId().equals(storeId) && p.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("미리보기를 찾을 수 없거나 만료됐어요. 다시 만들어 주세요"));

        storeService.applyNeeds(storeId, request.needs());

        Map<Integer, StoreWorker> staffById = storeWorkerRepository
                .findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE).stream()
                .collect(Collectors.toMap(StoreWorker::getId, Function.identity()));
        Map<Integer, Integer> countByStaff = new LinkedHashMap<>();
        int added = 0;
        for (SchedulePlanStore.PlannedShift p : plan.shifts()) {
            StoreWorker sw = staffById.get(p.staffId());
            if (sw == null) continue; // 그 사이 퇴사
            Schedule s = new Schedule();
            s.setStoreWorker(sw);
            s.setWorkDate(p.date());
            s.setStartMin(p.startMin());
            s.setEndMin(p.endMin());
            s.setPart(p.part());
            s.setBreakMin(p.breakMin());
            s.setKind(Schedule.Kind.WORK);
            if (overlapChecker.hasOverlap(sw, s, null)) continue;
            scheduleRepository.save(s);
            countByStaff.merge(sw.getId(), 1, Integer::sum);
            added++;
        }

        String period = plan.from().getMonthValue() + "/" + plan.from().getDayOfMonth() + "~"
                + plan.to().getMonthValue() + "/" + plan.to().getDayOfMonth();
        for (Map.Entry<Integer, Integer> e : countByStaff.entrySet()) {
            notifier.sendToStaff(store, staffById.get(e.getKey()), Notification.Type.SUB,
                    plan.from().getMonthValue() + "월 근무가 추가됐어요", e.getValue() + "건 · " + period, null);
        }
        planStore.remove(planId);
        return new ShiftCommandDto.AddedResponse(added);
    }

    /**
     * POST /schedule/template — 필요 인원 저장 + (선택) 오늘이 속한 주에 지난주 근무 복사.
     * 같은 직원·같은 파트가 이미 있으면 건너뜀, 재직자만, 겹침도 건너뜀.
     */
    public ShiftCommandDto.CopiedResponse applyTemplate(Integer userId, Integer storeId,
                                                        ShiftCommandDto.TemplateRequest request) {
        access.requireOwner(userId, storeId);
        storeService.applyNeeds(storeId, request.needs());
        if (!request.copyLastWeek()) {
            return new ShiftCommandDto.CopiedResponse(0);
        }

        LocalDate thisMonday = TimeUtil.monday(TimeUtil.today());
        Map<Integer, StoreWorker> active = storeWorkerRepository
                .findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE).stream()
                .collect(Collectors.toMap(StoreWorker::getId, Function.identity()));
        List<Schedule> all = new ArrayList<>(scheduleRepository.findByStoreBetween(storeId,
                thisMonday.minusDays(7), thisMonday.plusDays(7)));
        List<Schedule> lastWeek = all.stream()
                .filter(s -> s.getWorkDate().isBefore(thisMonday) && s.isWork() && !s.isSubstitute())
                .toList();

        int copied = 0;
        for (Schedule src : lastWeek) {
            StoreWorker sw = active.get(src.getStoreWorker().getId());
            if (sw == null) continue;
            LocalDate target = src.getWorkDate().plusDays(7);
            boolean duplicate = all.stream().anyMatch(s -> s.isWork()
                    && s.getWorkDate().equals(target)
                    && s.getStoreWorker().getId().equals(sw.getId())
                    && java.util.Objects.equals(s.getPart(), src.getPart()));
            if (duplicate) continue;

            Schedule s = new Schedule();
            s.setStoreWorker(sw);
            s.setWorkDate(target);
            s.setStartMin(src.getStartMin());
            s.setEndMin(src.getEndMin());
            s.setPart(src.getPart());
            s.setBreakMin(src.getBreakMin());
            s.setKind(Schedule.Kind.WORK);
            if (conflictsIn(all, sw, s)) continue;
            scheduleRepository.save(s);
            all.add(s);
            copied++;
        }
        return new ShiftCommandDto.CopiedResponse(copied);
    }

    /**
     * POST /schedule/confirm — 그 달 시간 미정 근무가 남아 있으면 409.
     * store.scheduleConfirmedAt=now, 재직 직원 전원에게 SUB "{N}월 근무표가 확정됐어요".
     */
    public void confirm(Integer userId, Integer storeId, ShiftCommandDto.ConfirmRequest request) {
        Store store = access.requireOwner(userId, storeId);
        long tbd = countTbd(storeId, request.year(), request.month());
        if (tbd > 0) {
            throw ApiException.conflict("TBD_REMAINING", "시간 미정 " + tbd + "건을 먼저 정해 주세요");
        }
        store.setScheduleConfirmedAt(TimeUtil.now());
        notifier.sendToActiveStaff(store, null, Notification.Type.SUB,
                request.month() + "월 근무표가 확정됐어요", store.getStoreName(), null);
    }

    /** GET /schedule/tbd-count?year&month */
    @Transactional(readOnly = true)
    public ShiftCommandDto.TbdCountResponse tbdCount(Integer userId, Integer storeId, int year, int month) {
        access.requireOwner(userId, storeId);
        return new ShiftCommandDto.TbdCountResponse((int) countTbd(storeId, year, month));
    }

    // ---------------------------------------------------------------- 내부 로직

    private long countTbd(Integer storeId, int year, int month) {
        if (month < 1 || month > 12) {
            throw ApiException.badRequest("INVALID_MONTH", "월이 올바르지 않아요");
        }
        LocalDate first = LocalDate.of(year, month, 1);
        return scheduleRepository.countTbd(storeId, first, first.with(TemporalAdjusters.lastDayOfMonth()));
    }

    private Schedule findShift(Integer storeId, Integer shiftId) {
        return scheduleRepository.findByIdAndStore(shiftId, storeId)
                .orElseThrow(() -> ApiException.notFound("근무를 찾을 수 없어요"));
    }

    private StoreWorker activeStaff(Integer storeId, Integer staffId) {
        StoreWorker sw = access.staff(storeId, staffId);
        if (sw.getStatus() != StoreWorker.Status.ACTIVE) {
            throw ApiException.conflict("NOT_ACTIVE", sw.getWorker().getName() + "님은 재직 중이 아니에요");
        }
        return sw;
    }

    private static void requireValidTime(int startMin, int endMin) {
        if (startMin == endMin) {
            throw ApiException.badRequest("INVALID_TIME", "시작과 종료 시각이 같을 수 없어요");
        }
    }

    private static boolean isFlex(StoreWorker sw) {
        return sw.getKind() == StoreWorker.Kind.MANAGER || sw.getKind() == StoreWorker.Kind.FULLTIME;
    }

    /** 메모리 상의 근무 목록(기존 + 새로 배정한 것)과 겹치는지. */
    private static boolean conflictsIn(List<Schedule> all, StoreWorker sw, Schedule probe) {
        return all.stream().anyMatch(s -> s.isWork()
                && s.getStoreWorker().getId().equals(sw.getId())
                && TimeUtil.overlaps(s, probe));
    }

    private record Fill(List<Schedule> added, int unfilled) {}

    /** 지난주(d-7) 실제 근무를 같은 직원·같은 시간으로 d 에 복사. 날짜 오름차순이라 앞 주 복사본이 다음 주 원본이 된다. */
    private List<Schedule> copyLastWeek(List<StoreWorker> staff, List<LocalDate> dates, Set<Integer> excluded,
                                        List<Schedule> all) {
        Map<Integer, StoreWorker> active = staff.stream()
                .collect(Collectors.toMap(StoreWorker::getId, Function.identity()));
        List<Schedule> added = new ArrayList<>();
        for (LocalDate d : dates) {
            List<Schedule> sources = all.stream()
                    .filter(s -> s.getWorkDate().equals(d.minusDays(7)) && s.isWork() && !s.isSubstitute())
                    .toList();
            for (Schedule src : sources) {
                StoreWorker sw = active.get(src.getStoreWorker().getId());
                if (sw == null || excluded.contains(sw.getId())) continue;
                Schedule copy = new Schedule();
                copy.setStoreWorker(sw);
                copy.setWorkDate(d);
                copy.setStartMin(src.getStartMin());
                copy.setEndMin(src.getEndMin());
                copy.setPart(src.getPart());
                copy.setBreakMin(src.getBreakMin());
                copy.setKind(Schedule.Kind.WORK);
                if (conflictsIn(all, sw, copy)) continue;
                all.add(copy);
                added.add(copy);
            }
        }
        return added;
    }

    /**
     * 빈칸 채우기 공통 로직 (autoFill / planSchedule). all 은 기존 근무 + 새 근무를 담는 가변 목록이며 새로 만든 것은
     * 저장하지 않고 돌려준다.
     *
     * @param needs         파트(시작 시각순)와 같은 순서의 필요 인원
     * @param weeklyMax     주 최대 근무 분 (null 이면 제한 없음)
     * @param ownPartFirst  true 면 자기 파트 직원을 먼저 (그다음 그 주 근무 적은 사람)
     * @param stopWhenEmpty true 면 채울 사람이 없을 때 즉시 중단 (autoFill), false 면 unfilled 만 올리고 계속
     */
    private Fill fill(List<StoreWorker> staff, List<StorePart> parts, List<Integer> needs, List<LocalDate> dates,
                      Set<Integer> excluded, Integer weeklyMax, boolean ownPartFirst, boolean stopWhenEmpty,
                      List<Schedule> all) {
        List<Schedule> added = new ArrayList<>();
        int unfilled = 0;

        Map<String, Integer> weekMinutes = new HashMap<>();
        for (Schedule s : all) {
            if (s.isWork()) {
                weekMinutes.merge(weekKey(s.getStoreWorker().getId(), s.getWorkDate()), s.scheduledMinutes(),
                        Integer::sum);
            }
        }

        outer:
        for (LocalDate d : dates) {
            for (int pi = 0; pi < parts.size(); pi++) {
                StorePart part = parts.get(pi);
                int need = pi < needs.size() ? needs.get(pi) : part.getNeed();
                long have = all.stream().filter(s -> s.isWork() && s.getWorkDate().equals(d)
                        && part.getName().equals(s.getPart())).count();
                int partMinutes = ScheduleDto.defaultBreakMin(part.getStartMin(), part.getEndMin());
                int length = (part.getEndMin() <= part.getStartMin() ? part.getEndMin() + 1440 : part.getEndMin())
                        - part.getStartMin() - partMinutes;

                for (long k = have; k < need; k++) {
                    Schedule probe = OverlapChecker.probe(null, d, part.getStartMin(), part.getEndMin());
                    List<StoreWorker> candidates = staff.stream()
                            .filter(sw -> !excluded.contains(sw.getId()) && !isFlex(sw))
                            .filter(sw -> all.stream().noneMatch(s -> s.isWork()
                                    && s.getStoreWorker().getId().equals(sw.getId())
                                    && s.getWorkDate().equals(d)))
                            .filter(sw -> !conflictsIn(all, sw, probe))
                            .filter(sw -> weeklyMax == null
                                    || weekMinutes.getOrDefault(weekKey(sw.getId(), d), 0) + length <= weeklyMax)
                            .sorted(Comparator
                                    .<StoreWorker>comparingInt(sw -> ownPartFirst && part.getName().equals(sw.getPart()) ? 0 : 1)
                                    .thenComparingInt(sw -> weekMinutes.getOrDefault(weekKey(sw.getId(), d), 0))
                                    .thenComparing(StoreWorker::getId))
                            .toList();
                    if (candidates.isEmpty()) {
                        unfilled++;
                        if (stopWhenEmpty) break outer;
                        continue;
                    }
                    StoreWorker pick = candidates.get(0);
                    Schedule s = new Schedule();
                    s.setStoreWorker(pick);
                    s.setWorkDate(d);
                    s.setStartMin(part.getStartMin());
                    s.setEndMin(part.getEndMin());
                    s.setPart(part.getName());
                    s.setBreakMin(partMinutes);
                    s.setKind(Schedule.Kind.WORK);
                    all.add(s);
                    added.add(s);
                    weekMinutes.merge(weekKey(pick.getId(), d), s.scheduledMinutes(), Integer::sum);
                }
            }
        }
        return new Fill(added, unfilled);
    }

    private static String weekKey(Integer staffId, LocalDate date) {
        return staffId + ":" + TimeUtil.monday(date).toEpochDay();
    }
}
