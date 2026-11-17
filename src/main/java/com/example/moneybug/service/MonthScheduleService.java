package com.example.moneybug.service;

import com.example.moneybug.dto.MonthScheduleCommandDto;
import com.example.moneybug.dto.MonthScheduleDto;
import com.example.moneybug.dto.ScheduleDto;
import com.example.moneybug.dto.ScheduleTemplateDto;
import com.example.moneybug.dto.ShiftCommandDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.ScheduleTemplate;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.MonthScheduleRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.ScheduleTemplateRepository;
import com.example.moneybug.repository.StorePartRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.service.FixedShiftPlanner.Existing;
import com.example.moneybug.service.FixedShiftPlanner.PartDef;
import com.example.moneybug.service.FixedShiftPlanner.Person;
import com.example.moneybug.service.FixedShiftPlanner.Planned;
import com.example.moneybug.service.FixedShiftPlanner.StoreHours;
import com.example.moneybug.service.ScheduleAiService.GenInput;
import com.example.moneybug.service.ScheduleAiService.Member;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/** 월 근무표: 만들기·자동 채우기·통째 저장·확정·삭제·템플릿·AI 생성. 대상 날짜 = max(월초, 오늘) ~ 월말. */
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
    private final ScheduleJobStore jobs;
    private final StoreChangeHub changeHub;
    private final TransactionTemplate tx;
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
                                ScheduleJobStore jobs, StoreChangeHub changeHub,
                                PlatformTransactionManager txManager) {
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
        this.jobs = jobs;
        this.changeHub = changeHub;
        this.tx = new TransactionTemplate(txManager);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- 만들기·자동 채우기

    public MonthScheduleDto.Response create(Integer userId, Integer storeId,
                                            MonthScheduleCommandDto.CreateRequest req) {
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
        Store store = access.requireOwner(userId, storeId);
        fill(store, parseMonth(month));
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
        access.requireOwner(userId, storeId);
        monthRepo.lockStore(storeId); // 같은 매장의 동시 저장·확정을 줄 세운다
        YearMonth ym = parseMonth(month);
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

        List<MonthScheduleCommandDto.Skipped> skipped = new ArrayList<>();
        List<Schedule> toDelete = new ArrayList<>();
        List<Schedule> toCreate = new ArrayList<>();
        Set<Integer> kept = new HashSet<>();

        for (MonthScheduleCommandDto.ShiftItem it : req.shifts()) {
            Integer id = numericId(it.id());
            Schedule cur = id == null ? null : mine.get(id);
            StoreWorker sw = active.get(it.staffId());
            // 퇴사자의 지난 근무도 목록에 그대로 실려 온다. 담당자가 그대로면 받고, 새로 배정하는 것만 막는다.
            if (sw == null && cur != null && cur.getStoreWorker().getId().equals(it.staffId())) sw = cur.getStoreWorker();
            if (sw == null) throw ApiException.badRequest("STAFF_NOT_FOUND", "재직 중인 직원이 아니에요");
            if (it.date().isBefore(first) || it.date().isAfter(last)) {
                throw ApiException.badRequest("DATE_OUT_OF_MONTH", month + " 근무표에 다른 달 날짜가 섞여 있어요");
            }
            if (it.startMin() < 0 || it.startMin() > 1439 || it.endMin() < 0 || it.endMin() > 2880) {
                throw ApiException.badRequest("INVALID_TIME", "근무 시각이 올바르지 않아요");
            }
            Schedule.Kind kind = it.kind() != null ? it.kind() : Schedule.Kind.WORK;
            int length = (it.endMin() <= it.startMin() ? it.endMin() + 1440 : it.endMin()) - it.startMin();
            if (kind == Schedule.Kind.WORK && !Boolean.TRUE.equals(it.timeTbd())) {
                if (length > 1440) throw ApiException.badRequest("INVALID_TIME", "근무는 24시간을 넘길 수 없어요");
                if (it.breakMin() != null && (it.breakMin() < 0 || it.breakMin() > length)) {
                    throw ApiException.badRequest("INVALID_BREAK", "휴게 시간이 올바르지 않아요");
                }
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
            s.setStoreWorker(sw);
            s.setWorkDate(it.date());
            s.setStartMin(it.startMin());
            s.setEndMin(it.endMin());
            s.setPart(it.part());
            s.setBreakMin(it.breakMin() != null ? it.breakMin()
                    : ScheduleDto.defaultBreakMin(it.startMin(), it.endMin()));
            s.setKind(kind);
            s.setTimeTbd(Boolean.TRUE.equals(it.timeTbd()));
            if (cur == null) toCreate.add(s);
        }
        for (Schedule s : mine.values()) {
            if (kept.contains(s.getId())) continue;
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

        toDelete.forEach(deleter::delete);
        scheduleRepo.saveAll(toCreate);
        monthRepo.findByStoreIdAndMonth(storeId, month).ifPresent(ms -> ms.setUpdatedAt(java.time.LocalDateTime.now()));
        return new MonthScheduleCommandDto.SaveShiftsResponse(skipped);
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
        access.requireOwner(userId, storeId);
        YearMonth ym = parseMonth(month);
        monthRepo.lockStore(storeId);
        MonthSchedule ms = monthRepo.findByStoreIdAndMonth(storeId, month)
                .orElseThrow(() -> ApiException.notFound("근무표를 찾을 수 없어요"));
        if (ms.getStatus() == MonthSchedule.Status.CONFIRMED) return; // 멱등: 알림을 또 보내지 않는다
        if (scheduleRepo.countTbd(storeId, ym.atDay(1), ym.atEndOfMonth()) > 0) {
            throw ApiException.conflict("TBD_REMAINING", "시간 미정을 먼저 해결하세요");
        }
        shiftService.confirm(userId, storeId, new ShiftCommandDto.ConfirmRequest(ym.getYear(), ym.getMonthValue()));
        ms.setStatus(MonthSchedule.Status.CONFIRMED);
    }

    /** 출근 기록 없는 그 달 근무와 메타를 지운다. */
    public void delete(Integer userId, Integer storeId, String month) {
        access.requireOwner(userId, storeId);
        YearMonth ym = parseMonth(month);
        removeShifts(scheduleRepo.findByStoreBetween(storeId, ym.atDay(1), ym.atEndOfMonth()));
        monthRepo.findByStoreIdAndMonth(storeId, month).ifPresent(monthRepo::delete);
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

    // ---------------------------------------------------------------- AI 생성

    /** 작업을 만들어 백그라운드에서 돌리고 jobId 를 돌려준다. */
    public String generate(Integer userId, Integer storeId, MonthScheduleCommandDto.GenerateRequest req) {
        access.requireOwner(userId, storeId);
        YearMonth ym = writableMonth(req.month());
        if (targetDates(ym).isEmpty()) {
            throw ApiException.badRequest("PAST_MONTH", "지난 달은 새로 만들 수 없어요");
        }
        if (monthRepo.findByStoreIdAndMonth(storeId, req.month())
                .filter(m -> m.getStatus() == MonthSchedule.Status.CONFIRMED).isPresent()) {
            throw ApiException.conflict("ALREADY_CONFIRMED", "이미 확정된 근무표예요");
        }
        MonthSchedule.HolidayPolicy policy = req.weeklyHolidayPay() != null
                ? req.weeklyHolidayPay() : MonthSchedule.HolidayPolicy.MINIMIZE;
        MonthSchedule.Priority priority = req.priority() != null ? req.priority() : MonthSchedule.Priority.FAIRNESS;
        String jobId = jobs.createIfNoneRunning(storeId);
        if (jobId == null) {
            throw ApiException.conflict("JOB_RUNNING", "이미 근무표를 만들고 있어요. 끝날 때까지 기다려 주세요");
        }
        executor.submit(() -> runGenerate(jobId, storeId, ym, policy, priority));
        return jobId;
    }

    public ScheduleJobStore.JobStatus job(Integer userId, Integer storeId, String jobId) {
        access.requireOwner(userId, storeId);
        ScheduleJobStore.JobStatus s = jobs.get(storeId, jobId);
        if (s == null) throw ApiException.notFound("작업을 찾을 수 없어요");
        return s;
    }

    private record Prepared(GenInput input, Map<Integer, String> names) {}

    private void runGenerate(String jobId, Integer storeId, YearMonth ym, MonthSchedule.HolidayPolicy policy,
                             MonthSchedule.Priority priority) {
        try {
            Prepared prep = tx.execute(st -> prepare(storeId, ym, priority));
            GenInput in = prep.input();
            if (in.members().isEmpty()) {
                jobs.fail(jobId, "직원·알바생을 먼저 등록하세요");
                return;
            }
            boolean hasFlex = in.members().stream().anyMatch(m -> !m.person().partTimer());
            List<Planned> planned = new ArrayList<>(FixedShiftPlanner.fixedShifts(in.hours(), in.parts(),
                    in.members().stream().map(Member::person).filter(Person::partTimer).toList(), in.dates()));
            String comment = "직원(유동 근무)이 없어 알바생 고정 근무로만 채웠어요.";
            if (hasFlex) {
                JsonNode out = ai.generate(ScheduleAiService.buildPrompt(in));
                planned.addAll(ScheduleAiService.toShifts(out, in));
                comment = ScheduleAiService.nameBack(out.path("comment").asString(""), prep.names());
            }
            if (planned.isEmpty()) {
                jobs.fail(jobId, "AI가 조건에 맞는 근무를 만들지 못했어요");
                return;
            }
            String finalComment = comment;
            tx.executeWithoutResult(st -> applyGenerated(storeId, ym, planned, finalComment, policy, priority));
            jobs.done(jobId, "");
            changeHub.bump(storeId); // 백그라운드 생성은 요청이 끝난 뒤라 인터셉터가 못 잡는다
        } catch (ApiException e) {
            jobs.fail(jobId, e.getMessage());
        } catch (Throwable e) { // Error 도 잡아야 job 이 영영 running 으로 남지 않는다
            // 프롬프트·키가 로그에 남지 않게 예외 종류만 기록한다.
            log.warn("근무표 생성 실패: {}", e.getClass().getSimpleName());
            jobs.fail(jobId, "근무표를 만드는 중 오류가 났어요");
        }
    }

    private Prepared prepare(Integer storeId, YearMonth ym, MonthSchedule.Priority priority) {
        Store store = access.store(storeId);
        Ctx c = ctx(store);
        List<Member> members = new ArrayList<>();
        Map<Integer, String> names = new HashMap<>();
        for (StoreWorker w : c.workers.values()) {
            members.add(new Member(person(w), w.getKind() == StoreWorker.Kind.MINOR));
            names.put(w.getId(), w.getWorker().getName());
        }
        members.sort(Comparator.comparingInt(m -> m.person().id()));
        return new Prepared(new GenInput(ym.toString(), c.hours, c.parts, members, targetDates(ym), priority), names);
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
        LocalDate from = targetDates(ym).get(0);
        // WORK 는 지우고(출근 기록 있는 것 제외) 연차·휴무는 그대로 둔다.
        removeShifts(scheduleRepo.findByStoreBetween(storeId, from, ym.atEndOfMonth()).stream()
                .filter(s -> s.getKind() == Schedule.Kind.WORK).toList());
        Set<String> off = scheduleRepo.findByStoreBetween(storeId, from, ym.atEndOfMonth()).stream()
                .filter(s -> s.getKind() != Schedule.Kind.WORK)
                .map(s -> s.getStoreWorker().getId() + "|" + s.getWorkDate()).collect(Collectors.toSet());
        insert(planned.stream().filter(p -> !off.contains(p.staffId() + "|" + p.date())).toList(),
                activeWorkers(storeId));
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
        return new Person(w.getId(), partTimer, StaffPrefs.fixedParts(w), wh);
    }

    /** 대상 날짜: max(월초, 오늘) ~ 월말. 지난 달이면 빈 목록. */
    static List<LocalDate> targetDates(YearMonth ym) {
        LocalDate from = ym.atDay(1).isAfter(TimeUtil.today()) ? ym.atDay(1) : TimeUtil.today();
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) out.add(d);
        return out;
    }

    /**
     * 근무를 새로 만드는 달은 다음 달까지만. /me/state 가 다음 달 말까지만 내려주므로
     * 그 뒤 달에 만들면 FE 에 안 보이고, 빈 편집기를 저장하면 전부 지워진다.
     */
    static YearMonth writableMonth(String month) {
        YearMonth ym = parseMonth(month);
        if (ym.isAfter(YearMonth.from(TimeUtil.today()).plusMonths(1))) {
            throw ApiException.badRequest("MONTH_TOO_FAR", "근무표는 다음 달까지만 만들 수 있어요");
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

    /** 출근 기록 없는 근무만 지운다. */
    private void removeShifts(List<Schedule> shifts) {
        if (shifts.isEmpty()) return;
        Set<Integer> withAttendance = attendanceRepo
                .findByScheduleIdIn(shifts.stream().map(Schedule::getId).toList()).stream()
                .map(Attendance::getSchedule).map(Schedule::getId).collect(Collectors.toSet());
        shifts.stream().filter(s -> !withAttendance.contains(s.getId())).forEach(deleter::delete);
        scheduleRepo.flush();
    }
}
