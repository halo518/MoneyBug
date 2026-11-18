package com.example.moneybug.service;

import com.example.moneybug.dto.MonthScheduleCommandDto;
import com.example.moneybug.dto.MonthScheduleDto;
import com.example.moneybug.dto.ScheduleDto;
import com.example.moneybug.dto.ScheduleTemplateDto;
import com.example.moneybug.dto.ShiftCommandDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.ScheduleTemplate;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.SubstituteApplicant;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.MonthScheduleRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.ScheduleTemplateRepository;
import com.example.moneybug.repository.StorePartRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.SubstituteApplicantRepository;
import com.example.moneybug.repository.SubstituteRequestRepository;
import com.example.moneybug.service.FixedShiftPlanner.Existing;
import com.example.moneybug.service.FixedShiftPlanner.PartDef;
import com.example.moneybug.service.FixedShiftPlanner.Person;
import com.example.moneybug.service.FixedShiftPlanner.Planned;
import com.example.moneybug.service.FixedShiftPlanner.StoreHours;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/** 월 근무표: 만들기·자동 채우기·통째 저장·확정·삭제·템플릿·자동 생성(미리보기→적용). 대상 날짜 = max(월초, 오늘) ~ 월말. */
@Slf4j
@Service
@Transactional
public class MonthScheduleService {

    static final String FLEX_PART = "직원";

    private final StoreAccess access;
    private final MonthScheduleRepository monthRepo;
    private final ScheduleTemplateRepository templateRepo;
    private final ScheduleRepository scheduleRepo;
    private final AttendanceRepository attendanceRepo;
    private final StoreWorkerRepository workerRepo;
    private final StorePartRepository partRepo;
    private final OverlapChecker overlapChecker;
    private final ScheduleDeleter deleter;
    private final ShiftService shiftService;
    private final ScheduleAiService ai;
    private final StaffWageService wageService;
    private final StoreChangeHub changeHub;
    private final NotificationPublisher notifier;
    private final TransactionTemplate tx;
    // 생성자 대신 필드 주입: 다른 브랜치가 같은 생성자를 고쳐도 충돌하지 않게.
    @Autowired SubstituteRequestRepository subRepo;
    @Autowired SubstituteApplicantRepository applicantRepo;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "schedule-ai");
        t.setDaemon(true);
        return t;
    });

    public MonthScheduleService(StoreAccess access, MonthScheduleRepository monthRepo,
                                ScheduleTemplateRepository templateRepo, ScheduleRepository scheduleRepo,
                                AttendanceRepository attendanceRepo, StoreWorkerRepository workerRepo,
                                StorePartRepository partRepo, OverlapChecker overlapChecker,
                                ScheduleDeleter deleter, ShiftService shiftService, ScheduleAiService ai,
                                StaffWageService wageService, StoreChangeHub changeHub,
                                NotificationPublisher notifier, PlatformTransactionManager txManager) {
        this.access = access;
        this.monthRepo = monthRepo;
        this.templateRepo = templateRepo;
        this.scheduleRepo = scheduleRepo;
        this.attendanceRepo = attendanceRepo;
        this.workerRepo = workerRepo;
        this.partRepo = partRepo;
        this.overlapChecker = overlapChecker;
        this.deleter = deleter;
        this.shiftService = shiftService;
        this.ai = ai;
        this.wageService = wageService;
        this.changeHub = changeHub;
        this.notifier = notifier;
        this.tx = new TransactionTemplate(txManager);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- 만들기·자동 채우기

    public MonthScheduleDto.Response create(Integer userId, Integer storeId,
                                            MonthScheduleCommandDto.CreateRequest req) {
        monthRepo.lockStore(storeId); // 동시 만들기: 메타 중복(500)·고정 근무 중복을 막는다
        Store store = access.requireOwner(userId, storeId);
        YearMonth ym = writableMonth(req.month());
        MonthSchedule ms = monthRepo.findByStoreIdAndMonth(storeId, req.month()).orElse(null);
        if (ms == null) {
            ms = newMeta(store, req.month());
        } else if (ms.getStatus() == MonthSchedule.Status.DRAFT) {
            ms.setSource(MonthSchedule.Source.MANUAL);
            ms.setUpdatedAt(java.time.LocalDateTime.now());
        } else {
            return MonthScheduleDto.Response.from(ms);
        }
        monthRepo.save(ms);
        if (!Boolean.FALSE.equals(req.prefillFixed())) fill(store, ym);
        return MonthScheduleDto.Response.from(ms);
    }

    public void autofill(Integer userId, Integer storeId, String month) {
        monthRepo.lockStore(storeId); // 동시 자동 채우기가 서로의 삽입을 못 보고 중복으로 넣지 않게
        Store store = access.requireOwner(userId, storeId);
        YearMonth ym = writableMonth(month);
        if (isConfirmed(storeId, month)) {
            throw ApiException.conflict("ALREADY_CONFIRMED", "확정된 근무표는 자동 채우기를 쓸 수 없어요");
        }
        fill(store, ym);
        monthRepo.findByStoreIdAndMonth(storeId, month).ifPresent(ms -> ms.setUpdatedAt(java.time.LocalDateTime.now()));
    }

    /** 이미 있는 근무는 두고 빠진 고정 근무만 넣는다. */
    private int fill(Store store, YearMonth ym) {
        List<LocalDate> dates = targetDates(ym);
        if (dates.isEmpty()) return 0;
        Ctx c = ctx(store);
        List<Existing> existing = scheduleRepo.findByStoreBetween(store.getId(), dates.get(0), ym.atEndOfMonth())
                .stream().map(s -> new Existing(s.getStoreWorker().getId(), s.getWorkDate(), s.getPart(),
                        s.getKind() != Schedule.Kind.WORK)).toList();
        return insert(FixedShiftPlanner.autofill(c.hours, c.parts, c.people, dates, existing), c.workers);
    }

    // ---------------------------------------------------------------- 통째 저장

    public MonthScheduleCommandDto.SaveShiftsResponse saveShifts(Integer userId, Integer storeId, String month,
                                                                 MonthScheduleCommandDto.SaveShiftsRequest req) {
        // 같은 매장의 동시 저장·확정을 줄 세운다. 읽기보다 먼저 잡아야 앞 저장의 결과를 본다.
        monthRepo.lockStore(storeId);
        Store store = access.requireOwner(userId, storeId);
        YearMonth ym = writableMonth(month); // 지난 달은 그대로 고칠 수 있고, 3개월 뒤를 넘는 달만 막는다
        LocalDate today = TimeUtil.today();
        LocalDate first = ym.atDay(1);
        LocalDate last = ym.atEndOfMonth();
        Map<Integer, StoreWorker> active = workerRepo.findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE)
                .stream().collect(Collectors.toMap(StoreWorker::getId, w -> w));

        // 월 앞뒤 하루까지 읽어 야간 근무가 이웃 달과 겹치는지도 본다.
        List<Schedule> around = scheduleRepo.findByStoreBetween(storeId, first.minusDays(1), last.plusDays(1));
        Map<Integer, Schedule> mine = new LinkedHashMap<>();
        for (Schedule s : around) {
            if (!s.getWorkDate().isBefore(first) && !s.getWorkDate().isAfter(last)) mine.put(s.getId(), s);
        }
        Set<Integer> withAttendance = mine.isEmpty() ? Set.of()
                : attendanceRepo.findByScheduleIdIn(mine.keySet()).stream()
                .map(a -> a.getSchedule().getId()).collect(Collectors.toSet());

        // 확정된 달은 바뀐 직원에게 알린다(직원은 이미 확정 근무표를 받았다).
        boolean confirmed = isConfirmed(storeId, month);
        Map<Integer, String> before = new HashMap<>();
        if (confirmed) mine.values().forEach(s -> before.put(s.getId(), signature(s)));

        List<MonthScheduleCommandDto.Skipped> skipped = new ArrayList<>();
        List<Schedule> toDelete = new ArrayList<>();
        List<Schedule> toCreate = new ArrayList<>();
        Set<Integer> kept = new HashSet<>();
        ChangedDays changed = new ChangedDays();

        for (MonthScheduleCommandDto.ShiftItem it : req.shifts()) {
            Integer id = numericId(it.id());
            Schedule cur = id == null ? null : mine.get(id);
            if (it.date().isBefore(first) || it.date().isAfter(last)) {
                throw ApiException.badRequest("DATE_OUT_OF_MONTH", month + " 근무표에 다른 달 날짜가 섞여 있어요");
            }
            if (it.startMin() < 0 || it.startMin() > 1439 || it.endMin() < 0 || it.endMin() > 2880) {
                throw ApiException.badRequest("INVALID_TIME", "근무 시각이 올바르지 않아요");
            }
            Schedule.Kind kind = it.kind() != null ? it.kind() : Schedule.Kind.WORK;
            boolean tbd = Boolean.TRUE.equals(it.timeTbd());
            int length = (it.endMin() <= it.startMin() ? it.endMin() + 1440 : it.endMin()) - it.startMin();
            // 연차도 시간만큼 유급이라 종류와 관계없이 같은 범위를 본다.
            if (length > 1440) throw ApiException.badRequest("INVALID_TIME", "근무는 24시간을 넘길 수 없어요");
            if (it.breakMin() != null && (it.breakMin() < 0 || it.breakMin() > length)) {
                throw ApiException.badRequest("INVALID_BREAK", "휴게 시간이 올바르지 않아요");
            }
            int breakMin = it.breakMin() != null ? it.breakMin()
                    : ScheduleDto.defaultBreakMin(it.startMin(), it.endMin());
            StoreWorker sw = active.get(it.staffId());
            // 퇴사자의 지난 근무도 목록에 그대로 실려 온다. 아무것도 안 바뀌었을 때만 받고, 옮기거나 새로 배정하는 것은 막는다.
            if (sw == null && cur != null && signature(cur).equals(signature(it.staffId(), it.date(), it.startMin(),
                    it.endMin(), it.part(), breakMin, kind, tbd))) {
                sw = cur.getStoreWorker();
            }
            if (sw == null) throw ApiException.badRequest("STAFF_NOT_FOUND", "재직 중인 직원이 아니에요");
            if (confirmed && tbd && !(cur != null && cur.isTimeTbd())) {
                throw ApiException.conflict("TBD_IN_CONFIRMED", "확정된 근무표에는 시간 미정 근무를 넣을 수 없어요");
            }
            if (cur != null) kept.add(cur.getId());
            if (cur != null && withAttendance.contains(cur.getId())) {
                if (!cur.getWorkDate().equals(it.date()) || !cur.getStoreWorker().getId().equals(sw.getId())) {
                    skipped.add(new MonthScheduleCommandDto.Skipped(cur.getId(), "출근 기록이 있어 날짜·사람을 바꾸지 않았어요"));
                    continue;
                }
                // 근태·급여는 실제 근무(WORK, 시간 확정)만 센다.
                if (kind != Schedule.Kind.WORK || Boolean.TRUE.equals(it.timeTbd())) {
                    skipped.add(new MonthScheduleCommandDto.Skipped(cur.getId(), "출근 기록이 있어 종류를 바꾸지 않았어요"));
                    continue;
                }
            }
            Schedule s = cur != null ? cur : new Schedule();
            StoreWorker prevStaff = cur != null ? cur.getStoreWorker() : null;
            LocalDate prevDate = cur != null ? cur.getWorkDate() : null;
            String prevLabel = cur != null ? TimeUtil.label(cur) : null;
            boolean wasWork = cur != null && cur.isWork();
            s.setStoreWorker(sw);
            s.setWorkDate(it.date());
            s.setStartMin(it.startMin());
            s.setEndMin(it.endMin());
            s.setPart(it.part());
            s.setBreakMin(breakMin);
            s.setKind(kind);
            s.setTimeTbd(tbd);
            if (cur == null) toCreate.add(s);
            // ShiftService.update 와 같이: 담당자·날짜·시간·종류가 바뀌면 진행 중 대타 요청은 더 이상 맞지 않는다.
            if (cur != null && (!prevStaff.getId().equals(sw.getId()) || !prevLabel.equals(TimeUtil.label(s))
                    || wasWork != s.isWork())) {
                cancelPendingSubs(store, cur, prevLabel);
            }
            if (confirmed && (cur == null || !before.get(cur.getId()).equals(signature(s)))) {
                if (prevStaff != null) changed.add(prevStaff, prevDate);
                changed.add(sw, it.date());
            }
        }
        for (Schedule s : mine.values()) {
            if (kept.contains(s.getId())) continue;
            // /me/state 기간 밖 근무는 FE 가 받은 적이 없다. 목록에 없다고 지우면 안 된다.
            if (!StateWindow.contains(today, s.getWorkDate())) continue;
            if (withAttendance.contains(s.getId())) {
                skipped.add(new MonthScheduleCommandDto.Skipped(s.getId(), "출근 기록이 있어 삭제하지 않았어요"));
            } else {
                toDelete.add(s);
            }
        }

        List<Schedule> finalState = new ArrayList<>();
        around.stream().filter(s -> !toDelete.contains(s)).forEach(finalState::add);
        finalState.addAll(toCreate);
        assertNoOverlap(finalState, first, last);

        if (confirmed) toDelete.forEach(s -> changed.add(s.getStoreWorker(), s.getWorkDate()));
        toDelete.forEach(deleter::delete);
        scheduleRepo.saveAll(toCreate);
        monthRepo.findByStoreIdAndMonth(storeId, month).ifPresent(ms -> ms.setUpdatedAt(java.time.LocalDateTime.now()));
        changed.notify(notifier, store, ym);
        return new MonthScheduleCommandDto.SaveShiftsResponse(skipped);
    }

    private boolean isConfirmed(Integer storeId, String month) {
        return monthRepo.findByStoreIdAndMonth(storeId, month)
                .filter(m -> m.getStatus() == MonthSchedule.Status.CONFIRMED).isPresent();
    }

    private static String signature(Schedule s) {
        return signature(s.getStoreWorker().getId(), s.getWorkDate(), s.getStartMin(), s.getEndMin(), s.getPart(),
                s.getBreakMin(), s.getKind(), s.isTimeTbd());
    }

    /** part 는 /me/state 가 null 을 "" 로 내려주므로 같게 본다. */
    private static String signature(Integer staffId, LocalDate date, int startMin, int endMin, String part,
                                    int breakMin, Schedule.Kind kind, boolean tbd) {
        return staffId + "|" + date + "|" + startMin + "|" + endMin + "|" + (part == null ? "" : part)
                + "|" + breakMin + "|" + kind + "|" + tbd;
    }

    /** 바뀐 근무의 진행 중 대타 요청을 취소하고 요청자·지원자에게 알린다. 사장님은 바꾼 본인이라 알리지 않는다. */
    private void cancelPendingSubs(Store store, Schedule shift, String oldLabel) {
        for (SubstituteRequest r : subRepo.findByScheduleIdAndStatusIn(shift.getId(),
                List.of(SubstituteRequest.Status.OPEN, SubstituteRequest.Status.ACCEPTED))) {
            r.setStatus(SubstituteRequest.Status.CANCELLED);
            String title = "근무가 바뀌어 대타 요청이 취소됐어요";
            Set<Integer> sent = new HashSet<>();
            if (sent.add(r.getRequester().getId())) {
                notifier.send(store, r.getRequester(), Notification.Type.SUB, title, oldLabel, r.getId());
            }
            for (SubstituteApplicant a : applicantRepo.findByRequestId(r.getId())) {
                if (sent.add(a.getApplicant().getId())) {
                    notifier.send(store, a.getApplicant(), Notification.Type.SUB, title, oldLabel, r.getId());
                }
            }
            if (r.getTaker() != null && sent.add(r.getTaker().getId())) {
                notifier.send(store, r.getTaker(), Notification.Type.SUB, title, oldLabel, r.getId());
            }
        }
    }

    /** 확정 달 저장에서 직원별로 바뀐 날짜. 직원마다 알림 한 건. */
    static final class ChangedDays {
        private final Map<Integer, StoreWorker> staff = new LinkedHashMap<>();
        private final Map<Integer, TreeSet<LocalDate>> days = new HashMap<>();

        void add(StoreWorker sw, LocalDate d) {
            staff.putIfAbsent(sw.getId(), sw);
            days.computeIfAbsent(sw.getId(), k -> new TreeSet<>()).add(d);
        }

        void notify(NotificationPublisher notifier, Store store, YearMonth ym) {
            staff.forEach((id, sw) -> notifier.sendToStaff(store, sw, Notification.Type.SUB,
                    ym.getMonthValue() + "월 확정 근무표가 바뀌었어요",
                    days.get(id).stream().map(d -> d.getMonthValue() + "/" + d.getDayOfMonth())
                            .collect(Collectors.joining(", ")), null));
        }
    }

    /** 같은 직원의 실제 근무끼리 겹치면 400. */
    static void assertNoOverlap(List<Schedule> shifts, LocalDate first, LocalDate last) {
        Map<Integer, List<Schedule>> byStaff = shifts.stream().filter(Schedule::isWork)
                .collect(Collectors.groupingBy(s -> s.getStoreWorker().getId()));
        for (List<Schedule> list : byStaff.values()) {
            list.sort(Comparator.comparing(Schedule::getWorkDate).thenComparing(Schedule::getStartMin));
            for (int i = 0; i + 1 < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    // 둘 다 이웃 달 근무면 이번 달 저장과 무관하다.
                    boolean aIn = !list.get(i).getWorkDate().isBefore(first) && !list.get(i).getWorkDate().isAfter(last);
                    boolean bIn = !list.get(j).getWorkDate().isBefore(first) && !list.get(j).getWorkDate().isAfter(last);
                    if ((aIn || bIn) && TimeUtil.overlaps(list.get(i), list.get(j))) {
                        throw ApiException.badRequest("SHIFT_OVERLAP",
                                list.get(i).getStoreWorker().getWorker().getName()
                                        + "님의 근무 시간이 겹쳐요 (" + TimeUtil.label(list.get(j)) + ")");
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 확정·삭제

    public void confirm(Integer userId, Integer storeId, String month) {
        monthRepo.lockStore(storeId); // 읽기보다 먼저: 동시 확정이 앞 요청의 CONFIRMED 를 보고 알림을 또 보내지 않게
        access.requireOwner(userId, storeId);
        YearMonth ym = parseMonth(month);
        MonthSchedule ms = monthRepo.findByStoreIdAndMonth(storeId, month)
                .orElseThrow(() -> ApiException.notFound("근무표를 찾을 수 없어요"));
        if (ms.getStatus() == MonthSchedule.Status.CONFIRMED) return; // 멱등: 알림을 또 보내지 않는다
        if (scheduleRepo.countTbd(storeId, ym.atDay(1), ym.atEndOfMonth()) > 0) {
            throw ApiException.conflict("TBD_REMAINING", "시간 미정을 먼저 해결하세요");
        }
        shiftService.confirm(userId, storeId, new ShiftCommandDto.ConfirmRequest(ym.getYear(), ym.getMonthValue()));
        ms.setStatus(MonthSchedule.Status.CONFIRMED);
    }

    /** 출근 기록 없는 그 달 근무와 메타를 지운다. 확정된 달이면 근무가 지워진 재직 직원에게 알린다. */
    public void delete(Integer userId, Integer storeId, String month) {
        monthRepo.lockStore(storeId);
        Store store = access.requireOwner(userId, storeId);
        YearMonth ym = parseMonth(month);
        boolean confirmed = isConfirmed(storeId, month);
        List<Schedule> removed = removeShifts(scheduleRepo.findByStoreBetween(storeId, ym.atDay(1), ym.atEndOfMonth()));
        monthRepo.findByStoreIdAndMonth(storeId, month).ifPresent(monthRepo::delete);
        if (confirmed) {
            ChangedDays changed = new ChangedDays();
            removed.stream().filter(s -> s.getStoreWorker().getStatus() == StoreWorker.Status.ACTIVE)
                    .forEach(s -> changed.add(s.getStoreWorker(), s.getWorkDate()));
            changed.notify(notifier, store, ym);
        }
    }

    // ---------------------------------------------------------------- 템플릿

    public ScheduleTemplateDto.Response saveTemplate(Integer userId, Integer storeId,
                                                     MonthScheduleCommandDto.TemplateCreateRequest req) {
        Store store = access.requireOwner(userId, storeId);
        YearMonth ym = parseMonth(req.month());
        List<LocalDate> week = TemplatePattern.firstFullWeek(ym);
        List<ScheduleTemplateDto.Shift> pattern = week.isEmpty() ? List.of()
                : scheduleRepo.findByStoreBetween(storeId, week.get(0), week.get(6)).stream()
                .map(s -> new ScheduleTemplateDto.Shift(TemplatePattern.dayIndex(s.getWorkDate()),
                        s.getStoreWorker().getId(), s.getStartMin(), s.getEndMin(), s.getPart(), s.getBreakMin(),
                        s.getKind(), s.isTimeTbd())).toList();
        ScheduleTemplate t = new ScheduleTemplate();
        t.setStore(store);
        t.setName(req.name().trim());
        t.setPatternJson(ScheduleTemplateDto.toJson(pattern));
        return ScheduleTemplateDto.Response.from(templateRepo.save(t));
    }

    public void deleteTemplate(Integer userId, Integer storeId, Integer templateId) {
        access.requireOwner(userId, storeId);
        templateRepo.findByIdAndStoreId(templateId, storeId).ifPresent(templateRepo::delete);
    }

    /** 요일별로 대상 날짜를 채운다. 오늘 이후·출근 기록 없는 기존 근무는 교체. */
    public void applyTemplate(Integer userId, Integer storeId, Integer templateId, String month) {
        Store store = access.requireOwner(userId, storeId);
        monthRepo.lockStore(storeId);
        YearMonth ym = writableMonth(month);
        ScheduleTemplate t = templateRepo.findByIdAndStoreId(templateId, storeId)
                .orElseThrow(() -> ApiException.notFound("템플릿을 찾을 수 없어요"));
        MonthSchedule ms = monthRepo.findByStoreIdAndMonth(storeId, month).orElse(null);
        if (ms != null && ms.getStatus() == MonthSchedule.Status.CONFIRMED) {
            throw ApiException.conflict("ALREADY_CONFIRMED", "이미 확정된 근무표예요");
        }
        List<LocalDate> dates = targetDates(ym);
        if (!dates.isEmpty()) {
            // WORK 만 교체한다. 연차·휴무는 두고, 그 날의 템플릿 행은 건너뛴다.
            List<Schedule> inRange = scheduleRepo.findByStoreBetween(storeId, dates.get(0), ym.atEndOfMonth());
            removeShifts(inRange.stream().filter(s -> s.getKind() == Schedule.Kind.WORK).toList());
            Set<String> off = inRange.stream().filter(s -> s.getKind() != Schedule.Kind.WORK)
                    .map(s -> s.getStoreWorker().getId() + "|" + s.getWorkDate()).collect(Collectors.toSet());
            Map<Integer, StoreWorker> workers = activeWorkers(storeId);
            List<Schedule> rows = new ArrayList<>();
            for (ScheduleTemplateDto.Shift p : ScheduleTemplateDto.parse(t.getPatternJson())) {
                StoreWorker sw = workers.get(p.staffId());
                if (sw == null) continue;
                boolean once = p.kind() == Schedule.Kind.LEAVE;
                for (LocalDate d : TemplatePattern.datesFor(p.dayIndex(), once, dates)) {
                    if (off.contains(sw.getId() + "|" + d)) continue;
                    rows.add(row(sw, d, p.startMin(), p.endMin(), p.part(), p.breakMin(),
                            p.kind() == null ? Schedule.Kind.WORK : p.kind(), p.timeTbd()));
                }
            }
            saveRows(rows);
        }
        if (ms == null) ms = newMeta(store, month);
        ms.setSource(MonthSchedule.Source.TEMPLATE);
        ms.setStatus(MonthSchedule.Status.DRAFT);
        ms.setAiComment("템플릿 \"" + t.getName() + "\"을 적용했어요.");
        ms.setUpdatedAt(java.time.LocalDateTime.now());
        monthRepo.save(ms);
    }

    // ---------------------------------------------------------------- 자동 생성: 미리보기 → 적용

    private static final java.time.Duration PLAN_TTL = java.time.Duration.ofMinutes(30);

    /** 적용 전 미리보기. 매장마다 마지막 것 하나만 30분 보관(인메모리, 재시작하면 사라짐). */
    record Plan(String id, YearMonth ym, MonthSchedule.HolidayPolicy policy, MonthSchedule.Priority priority,
                List<Planned> planned, List<Planned> prior, List<GapFiller.Gap> unfilled,
                List<ScheduleInsights.StaffInfo> staff, Map<Integer, String> names, String comment,
                java.time.Instant createdAt) {}

    private final Map<Integer, Plan> plans = new java.util.concurrent.ConcurrentHashMap<>();

    /** 동기 계산만 한다(AI 호출·DB 쓰기 없음). */
    public MonthScheduleCommandDto.PreviewResponse preview(Integer userId, Integer storeId,
                                                           MonthScheduleCommandDto.GenerateRequest req) {
        access.requireOwner(userId, storeId);
        YearMonth ym = writableMonth(req.month());
        List<LocalDate> dates = targetDates(ym);
        if (dates.isEmpty()) {
            throw ApiException.badRequest("PAST_MONTH", "지난 달은 새로 만들 수 없어요");
        }
        if (isConfirmed(storeId, req.month())) {
            throw ApiException.conflict("ALREADY_CONFIRMED", "이미 확정된 근무표예요");
        }
        MonthSchedule.HolidayPolicy policy = req.weeklyHolidayPay() != null
                ? req.weeklyHolidayPay() : MonthSchedule.HolidayPolicy.MINIMIZE;
        MonthSchedule.Priority priority = req.priority() != null ? req.priority() : MonthSchedule.Priority.FAIRNESS;
        Prepared prep = prepare(storeId, ym);
        Outcome out = compose(prep, ym, policy, priority);
        if (out.failure() != null) throw ApiException.badRequest("CANNOT_GENERATE", out.failure());

        List<Schedule> work = scheduleRepo.findByStoreBetween(storeId, dates.get(0), ym.atEndOfMonth()).stream()
                .filter(s -> s.getKind() == Schedule.Kind.WORK).toList();
        Set<Integer> withAttendance = work.isEmpty() ? Set.of() : attendanceRepo
                .findByScheduleIdIn(work.stream().map(Schedule::getId).toList()).stream()
                .map(a -> a.getSchedule().getId()).collect(Collectors.toSet());
        int replacing = (int) work.stream().filter(s -> !withAttendance.contains(s.getId())).count();

        Plan plan = new Plan(java.util.UUID.randomUUID().toString(), ym, policy, priority, out.planned(),
                prep.prior(), out.unfilled(), prep.staff(), prep.names(), String.join("\n", out.summary().lines()),
                java.time.Instant.now());
        plans.put(storeId, plan); // 같은 매장의 이전 미리보기는 버린다
        return MonthScheduleCommandDto.PreviewResponse.of(plan.id(), ym.toString(), out.planned(), FLEX_PART,
                out.summary().lines(), out.summary().laborCost(), out.unfilled(), replacing);
    }

    /** 미리보기대로 저장하고, 커밋 뒤 AI 브리핑을 백그라운드로 붙인다. */
    public void applyPlan(Integer userId, Integer storeId, String planId) {
        access.requireOwner(userId, storeId);
        Plan plan = plans.get(storeId);
        if (plan == null || !plan.id().equals(planId)
                || plan.createdAt().isBefore(java.time.Instant.now().minus(PLAN_TTL))
                || !plans.remove(storeId, plan)) { // remove 로 같은 planId 두 번 적용을 막는다
            throw new ApiException(org.springframework.http.HttpStatus.GONE, "PLAN_EXPIRED",
                    "미리보기가 만료됐어요. 다시 만들어 주세요");
        }
        applyGenerated(storeId, plan.ym(), plan.planned(), plan.comment(), plan.policy(), plan.priority());
        Runnable job = () -> attachBrief(storeId, plan);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            executor.submit(job);
                        }
                    });
        } else {
            executor.submit(job);
        }
    }

    record Prepared(StoreHours hours, List<PartDef> parts, List<GapFiller.PartNeed> needs, List<Person> people,
                    List<ScheduleInsights.StaffInfo> staff, Map<Integer, String> names, Set<String> off,
                    List<LocalDate> dates, List<Planned> prior) {
        Prepared(StoreHours hours, List<PartDef> parts, List<GapFiller.PartNeed> needs, List<Person> people,
                 List<ScheduleInsights.StaffInfo> staff, Map<Integer, String> names, Set<String> off,
                 List<LocalDate> dates) {
            this(hours, parts, needs, people, staff, names, off, dates, List.of());
        }
    }

    /** failure 가 있으면 그 메시지, 아니면 planned·unfilled·summary. */
    record Outcome(List<Planned> planned, List<GapFiller.Gap> unfilled, ScheduleInsights.Summary summary,
                   String failure) {}

    /** DB·AI 없는 순수 흐름: 고정 근무 → 부족분 충원 → 요약. */
    static Outcome compose(Prepared p, YearMonth ym, MonthSchedule.HolidayPolicy policy,
                           MonthSchedule.Priority priority) {
        if (p.people().isEmpty()) return new Outcome(List.of(), List.of(), null, "직원·알바생을 먼저 등록하세요");
        List<Planned> existing = FixedShiftPlanner.fixedShifts(p.hours(), p.parts(), p.people(), p.dates()).stream()
                .filter(s -> !p.off().contains(s.staffId() + "|" + s.date())).toList();
        Map<Integer, Integer> wages = new HashMap<>();
        p.staff().forEach(s -> wages.put(s.id(), s.wage()));
        // 첫 주 앞부분(대상 밖) 기존 근무도 주 6일·40시간 검사에 넣는다. 결과에는 안 들어간다.
        List<Planned> seen = new ArrayList<>(p.prior());
        seen.addAll(existing);
        GapFiller.Result r = GapFiller.fill(p.hours(), p.needs(), seen, p.people(), wages, p.off(), p.dates(),
                priority);
        List<Planned> planned = new ArrayList<>(existing);
        planned.addAll(r.shifts());
        planned.sort(Comparator.comparing(Planned::date).thenComparingInt(Planned::startMin)
                .thenComparingInt(Planned::endMin).thenComparingInt(Planned::staffId));
        if (planned.isEmpty()) return new Outcome(planned, r.unfilled(), null, "고정 파트·근무시간을 먼저 정하세요");
        return new Outcome(planned, r.unfilled(),
                ScheduleInsights.summarize(ym, planned, p.prior(), p.staff(), r.unfilled(), policy, priority), null);
    }

    /** 이름을 #id 로 가린 요약으로 AI 브리핑을 받아 이름을 되돌린 뒤 요약 앞에 붙인다. 브리핑이 비면 null. */
    static String briefed(Plan plan, java.util.function.Function<List<String>, String> brief) {
        List<ScheduleInsights.StaffInfo> anon = plan.staff().stream()
                .map(s -> new ScheduleInsights.StaffInfo(s.id(), "#" + s.id(), s.partTimer(), s.wage())).toList();
        List<String> idLines = ScheduleInsights.summarize(plan.ym(), plan.planned(), plan.prior(), anon, plan.unfilled(),
                plan.policy(), plan.priority()).lines();
        String ment = ScheduleAiService.nameBack(brief.apply(idLines), plan.names());
        return ment.isBlank() ? null : ment.strip() + "\n\n" + plan.comment();
    }

    void attachBrief(Integer storeId, Plan plan) {
        try {
            String comment = briefed(plan, idLines -> ai.generate(ScheduleAiService.briefPrompt(plan.ym().toString(),
                    idLines, plan.priority(), plan.policy())).path("comment").asString(""));
            if (comment == null) return;
            // 그 사이 다시 생성·적용됐거나(aiComment 가 바뀜) 삭제됐으면 덮어쓰지 않는다.
            // apply 와 같은 매장 잠금을 먼저 잡아 확인~저장 사이에 다른 apply 가 끼지 못하게 한다.
            Boolean saved = tx.execute(st -> {
                monthRepo.lockStore(storeId);
                return monthRepo.findByStoreIdAndMonth(storeId, plan.ym().toString())
                        .filter(m -> plan.comment().equals(m.getAiComment()))
                        .map(m -> {
                            m.setAiComment(comment);
                            return true;
                        }).orElse(false);
            });
            if (Boolean.TRUE.equals(saved)) changeHub.bump(storeId); // 요청이 끝난 뒤라 인터셉터가 못 잡는다
        } catch (Throwable e) {
            // 프롬프트·키가 로그에 남지 않게 예외 종류만 기록한다.
            log.warn("AI 브리핑 실패, 요약만 둔다: {}", e.getClass().getSimpleName());
        }
    }

    private Prepared prepare(Integer storeId, YearMonth ym) {
        Store store = access.store(storeId);
        Ctx c = ctx(store);
        List<LocalDate> dates = targetDates(ym);
        List<ScheduleInsights.StaffInfo> staff = new ArrayList<>();
        Map<Integer, String> names = new HashMap<>();
        for (StoreWorker w : c.workers.values()) {
            int wage = StaffWageService.wageOn(wageService.rows(w.getId()), ym.atDay(1), w.getHourlyWage());
            boolean partTimer = person(w).partTimer();
            staff.add(new ScheduleInsights.StaffInfo(w.getId(), w.getWorker().getName(), partTimer, wage));
            names.put(w.getId(), w.getWorker().getName());
        }
        List<Schedule> target = scheduleRepo.findByStoreBetween(storeId, dates.get(0), ym.atEndOfMonth());
        Set<String> off = target.stream()
                .filter(s -> s.getKind() != Schedule.Kind.WORK)
                .map(s -> s.getStoreWorker().getId() + "|" + s.getWorkDate()).collect(Collectors.toSet());
        List<GapFiller.PartNeed> needs = partRepo.findByStoreIdOrderByStartMinAsc(storeId).stream()
                .map(x -> new GapFiller.PartNeed(x.getName(), x.getStartMin(), x.getEndMin(),
                        x.getNeed() == null ? 1 : x.getNeed())).toList();
        // 맥락 근무(결과에는 안 들어감): 첫 대상일이 주 중간이면 그 주 월요일~전날의 기존 근무(지난달 말 포함),
        // 그리고 대상일의 출근 기록 있는 근무(적용해도 안 지워짐). 연차·휴무 제외.
        LocalDate monday = dates.get(0).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        List<Schedule> context = new ArrayList<>(monday.isBefore(dates.get(0))
                ? scheduleRepo.findByStoreBetween(storeId, monday, dates.get(0).minusDays(1)) : List.of());
        List<Schedule> targetWork = target.stream().filter(s -> s.getKind() == Schedule.Kind.WORK).toList();
        if (!targetWork.isEmpty()) {
            Set<Integer> attended = attendanceRepo.findByScheduleIdIn(targetWork.stream().map(Schedule::getId).toList())
                    .stream().map(a -> a.getSchedule().getId()).collect(Collectors.toSet());
            targetWork.stream().filter(s -> attended.contains(s.getId())).forEach(context::add);
        }
        List<Planned> prior = context.stream().filter(s -> s.getKind() == Schedule.Kind.WORK)
                .map(s -> new Planned(s.getStoreWorker().getId(), s.getWorkDate(), s.getStartMin(), s.endAbs(),
                        FLEX_PART.equals(s.getPart()) ? null : s.getPart(), s.getBreakMin())).toList();
        return new Prepared(c.hours, c.parts, needs, c.people, staff, names, off, dates, prior);
    }

    /** 넣을 근무: from(미리보기 뒤 자정을 넘겼으면 바뀐 오늘) 이전과 연차·휴무 날은 버린다. */
    static List<Planned> insertable(List<Planned> planned, LocalDate from, Set<String> off) {
        return planned.stream()
                .filter(p -> !p.date().isBefore(from) && !off.contains(p.staffId() + "|" + p.date())).toList();
    }

    private void applyGenerated(Integer storeId, YearMonth ym, List<Planned> planned, String comment,
                                MonthSchedule.HolidayPolicy policy, MonthSchedule.Priority priority) {
        Store store = access.store(storeId);
        monthRepo.lockStore(storeId);
        // 생성하는 동안 확정됐으면 덮어쓰지 않는다.
        if (monthRepo.findByStoreIdAndMonth(storeId, ym.toString())
                .filter(m -> m.getStatus() == MonthSchedule.Status.CONFIRMED).isPresent()) {
            throw ApiException.conflict("ALREADY_CONFIRMED", "그 사이 확정돼서 반영하지 않았어요");
        }
        List<LocalDate> dates = targetDates(ym);
        if (dates.isEmpty()) throw ApiException.badRequest("PAST_MONTH", "지난 달은 새로 만들 수 없어요");
        LocalDate from = dates.get(0);
        // WORK 는 지우고(출근 기록 있는 것 제외) 연차·휴무는 그대로 둔다.
        removeShifts(scheduleRepo.findByStoreBetween(storeId, from, ym.atEndOfMonth()).stream()
                .filter(s -> s.getKind() == Schedule.Kind.WORK).toList());
        Set<String> off = scheduleRepo.findByStoreBetween(storeId, from, ym.atEndOfMonth()).stream()
                .filter(s -> s.getKind() != Schedule.Kind.WORK)
                .map(s -> s.getStoreWorker().getId() + "|" + s.getWorkDate()).collect(Collectors.toSet());
        insert(insertable(planned, from, off), activeWorkers(storeId));
        MonthSchedule ms = monthRepo.findByStoreIdAndMonth(storeId, ym.toString())
                .orElseGet(() -> newMeta(store, ym.toString()));
        ms.setSource(MonthSchedule.Source.AI);
        ms.setStatus(MonthSchedule.Status.DRAFT);
        ms.setAiComment(comment);
        ms.setWeeklyHolidayPay(policy);
        ms.setPriority(priority);
        ms.setUpdatedAt(java.time.LocalDateTime.now());
        monthRepo.save(ms);
    }

    // ---------------------------------------------------------------- 내부

    private record Ctx(StoreHours hours, List<PartDef> parts, Map<Integer, StoreWorker> workers,
                       List<Person> people) {}

    private Ctx ctx(Store store) {
        StoreHours hours = new StoreHours(store.getOpenMin(), store.getCloseMin(),
                store.getWeekendOpenMin(), store.getWeekendCloseMin());
        List<PartDef> parts = partRepo.findByStoreIdOrderByStartMinAsc(store.getId()).stream()
                .map(p -> new PartDef(p.getName(), p.getStartMin(), p.getEndMin(), p.getBreakMin())).toList();
        Map<Integer, StoreWorker> workers = activeWorkers(store.getId());
        List<Person> people = workers.values().stream().map(MonthScheduleService::person).toList();
        return new Ctx(hours, parts, workers, people);
    }

    private Map<Integer, StoreWorker> activeWorkers(Integer storeId) {
        return workerRepo.findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE).stream()
                .sorted(Comparator.comparing(StoreWorker::getId))
                .collect(Collectors.toMap(StoreWorker::getId, w -> w, (a, b) -> a, LinkedHashMap::new));
    }

    private static Person person(StoreWorker w) {
        boolean partTimer = w.getKind() == null || w.getKind() == StoreWorker.Kind.PART_TIMER
                || w.getKind() == StoreWorker.Kind.MINOR;
        List<FixedShiftPlanner.WorkHours> wh = StaffPrefs.workHours(w) == null ? null
                : StaffPrefs.workHours(w).stream()
                .map(h -> h == null ? null : new FixedShiftPlanner.WorkHours(h.startMin(), h.endMin())).toList();
        List<FixedShiftPlanner.WorkHours> avail = StaffPrefs.availHours(w).stream()
                .map(h -> h == null ? null : new FixedShiftPlanner.WorkHours(h.startMin(), h.endMin())).toList();
        return new Person(w.getId(), partTimer, StaffPrefs.fixedParts(w), wh, avail,
                w.getKind() == StoreWorker.Kind.MINOR);
    }

    /** 대상 날짜: max(월초, 오늘) ~ 월말. 지난 달이면 빈 목록. */
    static List<LocalDate> targetDates(YearMonth ym) {
        LocalDate from = ym.atDay(1).isAfter(TimeUtil.today()) ? ym.atDay(1) : TimeUtil.today();
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) out.add(d);
        return out;
    }

    /**
     * 근무를 새로 만드는 달은 3개월 뒤까지만. /me/state 가 3개월 뒤 말까지만 내려주므로
     * 그 뒤 달에 만들면 FE 에 안 보이고, 빈 편집기를 저장하면 전부 지워진다.
     */
    static YearMonth writableMonth(String month) {
        YearMonth ym = parseMonth(month);
        if (ym.isAfter(YearMonth.from(TimeUtil.today()).plusMonths(3))) {
            throw ApiException.badRequest("MONTH_TOO_FAR", "근무표는 3개월 뒤까지만 만들 수 있어요");
        }
        return ym;
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_MONTH", "월 형식이 올바르지 않아요");
        }
    }

    private static Integer numericId(String id) {
        try {
            return id == null || id.isBlank() ? null : Integer.valueOf(id.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private MonthSchedule newMeta(Store store, String month) {
        MonthSchedule ms = new MonthSchedule();
        ms.setStore(store);
        ms.setMonth(month);
        return ms;
    }

    private static Schedule row(StoreWorker sw, LocalDate date, int startMin, int endMin, String part, int breakMin,
                                Schedule.Kind kind, boolean tbd) {
        Schedule s = new Schedule();
        s.setStoreWorker(sw);
        s.setWorkDate(date);
        s.setStartMin(startMin);
        s.setEndMin(endMin);
        s.setPart(part);
        s.setBreakMin(breakMin);
        s.setKind(kind);
        s.setTimeTbd(tbd);
        return s;
    }

    /** 계획된 근무를 저장한다. 같은 직원의 다른 실제 근무와 겹치면 건너뜀. */
    private int insert(List<Planned> planned, Map<Integer, StoreWorker> workers) {
        List<Schedule> rows = new ArrayList<>();
        for (Planned p : planned) {
            StoreWorker sw = workers.get(p.staffId());
            if (sw != null) {
                rows.add(row(sw, p.date(), p.startMin(), p.endMin(), p.part() != null ? p.part() : FLEX_PART,
                        p.breakMin(), Schedule.Kind.WORK, false));
            }
        }
        return saveRows(rows);
    }

    private int saveRows(List<Schedule> rows) {
        int n = 0;
        for (Schedule s : rows) {
            if (s.isWork() && overlapChecker.hasOverlap(s.getStoreWorker(), s, null)) continue;
            scheduleRepo.save(s);
            n++;
        }
        return n;
    }

    /** 출근 기록 없는 근무만 지운다. 지운 근무를 돌려준다. */
    private List<Schedule> removeShifts(List<Schedule> shifts) {
        if (shifts.isEmpty()) return List.of();
        Set<Integer> withAttendance = attendanceRepo
                .findByScheduleIdIn(shifts.stream().map(Schedule::getId).toList()).stream()
                .map(Attendance::getSchedule).map(Schedule::getId).collect(Collectors.toSet());
        List<Schedule> removed = shifts.stream().filter(s -> !withAttendance.contains(s.getId())).toList();
        removed.forEach(deleter::delete);
        scheduleRepo.flush();
        return removed;
    }
}
