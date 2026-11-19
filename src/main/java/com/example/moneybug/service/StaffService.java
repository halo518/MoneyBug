package com.example.moneybug.service;

import com.example.moneybug.dto.CommonDto;
import com.example.moneybug.dto.StaffCommandDto;
import com.example.moneybug.dto.StaffDto;
import com.example.moneybug.dto.StoreWorkerDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StorePart;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.SubstituteApplicant;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StorePartRepository;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.SubstituteApplicantRepository;
import com.example.moneybug.repository.SubstituteRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** 직원(Staff) (설계서 §4.3). staffId = StoreWorker.id */
@Service
@Transactional
@RequiredArgsConstructor
public class StaffService {

    private final StoreWorkerRepository storeWorkerRepository;
    private final ScheduleRepository scheduleRepository;
    private final AttendanceRepository attendanceRepository;
    private final SubstituteRequestRepository substituteRequestRepository;
    private final SubstituteApplicantRepository substituteApplicantRepository;
    private final StorePartRepository storePartRepository;
    private final StoreService storeService;
    private final StoreAccess access;
    private final StaffAssembler staffAssembler;
    private final ScheduleDeleter scheduleDeleter;
    private final NotificationPublisher notifier;
    private final StaffWageService wages;
    private final StoreRepository storeRepository;

    /** 퇴사 처리 때 열린 출근 기록을 닫으며 남기는 사유. */
    static final String LEAVE_CLOCK_OUT_REASON = "퇴사 처리로 자동 퇴근";

    /** GET /staff?status= — 사장님·직원. status 생략 시 사장님은 재직+대기, 직원은 재직만. */
    @Transactional(readOnly = true)
    public CommonDto.ListResponse<StaffDto> list(Integer userId, Integer storeId, StoreWorker.Status status) {
        Store store = access.requireOwnerOrStaff(userId, storeId);
        Collection<StoreWorker.Status> statuses;
        boolean owner = access.isOwner(store, userId);
        if (!owner) {
            statuses = List.of(StoreWorker.Status.ACTIVE); // 직원은 ?status= 와 상관없이 재직자만
        } else if (status != null) {
            statuses = List.of(status);
        } else {
            statuses = List.of(StoreWorker.Status.ACTIVE, StoreWorker.Status.PENDING);
        }
        return CommonDto.ListResponse.of(storeWorkerRepository.findByStoreIdAndStatusIn(storeId, statuses).stream()
                .sorted(Comparator.comparing(StoreWorker::getId))
                .map(staffAssembler::toDto)
                .map(d -> d.forViewer(owner, userId))
                .toList());
    }

    /** GET /staff/{staffId} — 사장님, 본인. */
    @Transactional(readOnly = true)
    public StaffDto get(Integer userId, Integer storeId, Integer staffId) {
        Store store = access.store(storeId);
        StoreWorker sw = access.staff(storeId, staffId);
        if (!access.isOwner(store, userId) && !sw.getWorker().getId().equals(userId)) {
            throw ApiException.forbidden("사장님 또는 본인만 볼 수 있어요");
        }
        return staffAssembler.toDto(sw);
    }

    /** POST .../approve — wage >= 최저임금, PENDING 만 가능. status=ACTIVE, joinedAt=오늘, 신청자에게 JOIN 알림. */
    public StaffDto approve(Integer userId, Integer storeId, Integer staffId, StaffCommandDto.ApproveRequest request) {
        lockStore(storeId);
        Store store = access.requireOwner(userId, storeId);
        StoreWorker sw = lockStaff(storeId, staffId);
        if (sw.getStatus() != StoreWorker.Status.PENDING) {
            throw ApiException.conflict("NOT_PENDING", "가입 신청 상태가 아니에요");
        }
        requireMinWage(request.hourlyWage());
        if (staffRepoActive(storeId, sw)) {
            throw ApiException.conflict("ALREADY_ACTIVE", "이미 재직 중인 직원이에요");
        }
        // 같은 사람의 다른 대기 신청(중복 신청)은 조용히 닫는다: 재직 행 하나만 남긴다.
        for (StoreWorker dup : storeWorkerRepository.findByStoreIdAndWorkerIdAndStatus(storeId,
                sw.getWorker().getId(), StoreWorker.Status.PENDING)) {
            if (!dup.getId().equals(sw.getId())) dup.setStatus(StoreWorker.Status.REJECTED);
        }

        sw.setHourlyWage(request.hourlyWage());
        sw.setTaxType(request.taxType() != null ? request.taxType()
                : store.getDefaultDeduction() != null ? store.getDefaultDeduction() : StoreWorker.TaxType.NONE);
        sw.setStatus(StoreWorker.Status.ACTIVE);
        sw.setJoinedAt(TimeUtil.today());
        sw.setLeftAt(null);
        storeService.syncSize(store);

        notifier.sendToStaff(store, sw, Notification.Type.JOIN, store.getStoreName() + " 가입이 승인됐어요",
                "시급 " + String.format("%,d", sw.getHourlyWage()) + "원", sw.getId());
        return staffAssembler.toDto(sw);
    }

    /** POST .../reject — PENDING 만 가능. status=REJECTED, 신청자에게 JOIN 알림. */
    public void reject(Integer userId, Integer storeId, Integer staffId) {
        lockStore(storeId);
        Store store = access.requireOwner(userId, storeId);
        StoreWorker sw = lockStaff(storeId, staffId);
        if (sw.getStatus() != StoreWorker.Status.PENDING) {
            throw ApiException.conflict("NOT_PENDING", "가입 신청 상태가 아니에요");
        }
        sw.setStatus(StoreWorker.Status.REJECTED);
        notifier.sendToStaff(store, sw, Notification.Type.JOIN, store.getStoreName() + " 가입이 거절됐어요",
                null, sw.getId());
    }

    /** PATCH .../staff/{staffId} — 보낸 것만 반영, 시급은 최저임금 검증(시급 편집 3곳 모두 동일). */
    public StaffDto update(Integer userId, Integer storeId, Integer staffId, StaffCommandDto.UpdateRequest request) {
        // 시급 이력 INSERT(FK 공유 락) 전에 직원 행을 먼저 잠근다: 동시 변경 데드락·기준 행 중복 방지.
        StoreWorker sw = lockStaff(storeId, staffId);
        access.requireOwner(userId, storeId);
        if (sw.getStatus() != StoreWorker.Status.ACTIVE) {
            throw ApiException.conflict("NOT_ACTIVE", "재직 중인 직원이 아니에요");
        }
        if (request.hourlyWage() != null) {
            requireMinWage(request.hourlyWage());
            if (!request.hourlyWage().equals(sw.getHourlyWage())) {
                wages.record(sw, TimeUtil.today(), request.hourlyWage());
            }
        }
        if (request.taxType() != null) sw.setTaxType(request.taxType());
        if (request.weeklyHolidayAuto() != null) sw.setWeeklyHolidayAuto(request.weeklyHolidayAuto());
        if (request.premium() != null) sw.setPremium(request.premium());
        if (request.kind() != null) sw.setKind(request.kind());
        if (request.part() != null) sw.setPart(validPart(storeId, request.part(), sw.getKind()));
        boolean employee = !isPartTimer(sw.getKind());
        List<List<String>> fixed = request.fixedParts() == null ? null
                : employee ? validEmployeeFixedParts(storeId, request.fixedParts())
                : validFixedParts(storeId, request.fixedParts());
        List<StaffPrefs.WorkHours> hours = request.workHours() == null ? null : validWorkHours(request.workHours());
        if (employee && (fixed != null || hours != null)) {
            // 보낸 값과 저장된 값을 합쳐 본다. 저장된 고정 파트 중 직원 파트가 아닌 이름은 생성에서 무시하므로 빼고 본다.
            java.util.Set<String> flexNames = new java.util.HashSet<>();
            storePartRepository.findByStoreIdOrderByStartMinAsc(storeId).stream()
                    .filter(StorePart::isFlex).forEach(x -> flexNames.add(x.getName()));
            List<List<String>> f = fixed != null ? fixed : StaffPrefs.fixedParts(sw);
            List<StaffPrefs.WorkHours> h = hours != null ? hours : StaffPrefs.workHours(sw);
            for (int i = 0; h != null && i < 7; i++) {
                if (h.get(i) != null && f.get(i).stream().anyMatch(flexNames::contains)) {
                    throw ApiException.badRequest("INVALID_FIXED_PARTS",
                            "같은 요일에 고정 파트와 근무 시간을 함께 정할 수 없어요");
                }
            }
        }
        if (fixed != null) StaffPrefs.setFixedParts(sw, fixed);
        if (hours != null) StaffPrefs.setWorkHours(sw, hours);
        if (request.availHours() != null) {
            StaffPrefs.setAvailHours(sw, validWorkHours(request.availHours()));
        }
        return staffAssembler.toDto(sw);
    }

    /**
     * DELETE .../staff/{staffId} — 내보내기(LEFT). 부수효과(설계서 그대로):
     * 1) status=LEFT
     * 2) 오늘 이후이면서 출근 기록 없는 근무 삭제
     * 3) 진행 중 대타: 요청자면 CANCELLED, 지원자면 지원자에서 제거(후보였다면 taker=null, OPEN 으로 되돌림)
     * 4) 근무 중이면 열린 출근 기록을 지금 시각으로 닫는다
     * 5) 본인에게 JOIN 알림
     * 대기·거절 신청자는 409 NOT_ACTIVE (신청 정리는 reject).
     */
    public void remove(Integer userId, Integer storeId, Integer staffId) {
        lockStore(storeId);
        Store store = access.requireOwner(userId, storeId);
        StoreWorker sw = lockStaff(storeId, staffId);
        if (sw.getStatus() == StoreWorker.Status.LEFT) {
            return; // 멱등
        }
        if (sw.getStatus() != StoreWorker.Status.ACTIVE) {
            throw ApiException.conflict("NOT_ACTIVE", "재직 중인 직원이 아니에요");
        }
        sw.setStatus(StoreWorker.Status.LEFT);
        sw.setLeftAt(TimeUtil.today());
        storeService.syncSize(store);
        Integer workerUserId = sw.getWorker().getId();
        String workerName = sw.getWorker().getName();

        List<SubstituteRequest> inProgress = substituteRequestRepository.findByStoreAndStatusIn(storeId,
                List.of(SubstituteRequest.Status.OPEN, SubstituteRequest.Status.ACCEPTED));
        for (SubstituteRequest r : inProgress) {
            String label = TimeUtil.label(r.getSchedule());
            if (r.getRequester().getId().equals(workerUserId)) {
                r.setStatus(SubstituteRequest.Status.CANCELLED);
                // 근무와 함께 요청이 지워지기 전에 지원자에게 알린다.
                for (SubstituteApplicant a : substituteApplicantRepository.findByRequestId(r.getId())) {
                    notifier.send(store, a.getApplicant(), Notification.Type.SUB,
                            workerName + "님의 대타 요청이 취소됐어요", label, r.getId());
                }
            } else if (substituteApplicantRepository.existsByRequestIdAndApplicantId(r.getId(), workerUserId)) {
                substituteApplicantRepository.deleteByRequestIdAndApplicantId(r.getId(), workerUserId);
                if (r.getTaker() != null && r.getTaker().getId().equals(workerUserId)) {
                    promoteNextApplicant(store, r, workerUserId, label);
                }
            }
        }

        LocalDateTime now = TimeUtil.now();
        for (Attendance a : attendanceRepository.findOpenByUserAndStore(workerUserId, storeId)) {
            if (!a.getSchedule().getStoreWorker().getId().equals(sw.getId())) continue;
            a.setActualEnd(now.isAfter(a.getActualStart()) ? now : a.getActualStart());
            a.setEditReason(LEAVE_CLOCK_OUT_REASON);
            a.setOvertimeMinutes(AttendanceCalc.overtimeMinutes(a.getSchedule(), a));
        }

        LocalDate today = TimeUtil.today();
        for (Schedule s : scheduleRepository.findFromDateByWorker(sw.getId(), today)) {
            if (!attendanceRepository.existsByScheduleId(s.getId())) {
                scheduleDeleter.delete(s);
            }
        }

        notifier.sendToStaff(store, sw, Notification.Type.JOIN, store.getStoreName() + "에서 퇴사 처리됐어요",
                null, sw.getId());
    }

    /** POST .../cert-renewal-requests — 직원에게 CERT 알림 (미등록 / D-n / 지남). */
    public void requestCertRenewal(Integer userId, Integer storeId, Integer staffId) {
        Store store = access.requireOwner(userId, storeId);
        StoreWorker sw = access.staff(storeId, staffId);
        if (sw.getStatus() != StoreWorker.Status.ACTIVE) {
            throw ApiException.conflict("NOT_ACTIVE", "재직 중인 직원이 아니에요");
        }
        LocalDate expiry = sw.getWorker().getCertExpiry();
        String title;
        String body = null;
        if (expiry == null) {
            title = "보건증을 등록해 주세요";
        } else {
            long daysLeft = ChronoUnit.DAYS.between(TimeUtil.today(), expiry);
            title = daysLeft >= 0 ? "보건증 만료 D-" + daysLeft : "보건증 만료 지남";
            body = "만료일 " + DateTimeFormatter.ofPattern("yyyy.MM.dd").format(expiry);
        }
        notifier.sendToStaff(store, sw, Notification.Type.CERT, title, body, sw.getId());
    }

    /**
     * 후보(taker)가 퇴사로 빠졌을 때: 남은 지원자 중 가장 먼저 지원한 사람을 후보로 올리고(ACCEPTED 유지),
     * 없으면 OPEN 으로 되돌린다. 요청자·사장님(·새 후보)에게 알린다.
     */
    private void promoteNextApplicant(Store store, SubstituteRequest r, Integer leaverUserId, String label) {
        SubstituteApplicant next = substituteApplicantRepository.findByRequestId(r.getId()).stream()
                .filter(a -> !a.getApplicant().getId().equals(leaverUserId))
                .min(Comparator.comparing(SubstituteApplicant::getAppliedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SubstituteApplicant::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        String title;
        if (next == null) {
            r.setTaker(null);
            r.setStatus(SubstituteRequest.Status.OPEN);
            title = "대타 후보가 퇴사해서 다시 대타를 구해요";
        } else {
            r.setTaker(next.getApplicant());
            r.setStatus(SubstituteRequest.Status.ACCEPTED);
            title = "대타 후보가 " + next.getApplicant().getName() + "님으로 바뀌었어요";
            notifier.send(store, next.getApplicant(), Notification.Type.SUB, "대타 후보가 됐어요", label, r.getId());
        }
        notifier.send(store, r.getRequester(), Notification.Type.SUB, title, label, r.getId());
        notifier.sendToOwner(store, Notification.Type.SUB, title, label, r.getId());
    }

    /** 매장 단위 직렬화: 트랜잭션 첫 문장으로 매장 행을 잠근다. */
    private void lockStore(Integer storeId) {
        storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> ApiException.notFound("매장을 찾을 수 없어요"));
    }

    private StoreWorker lockStaff(Integer storeId, Integer staffId) {
        return storeWorkerRepository.findByIdAndStoreIdForUpdate(staffId, storeId)
                .orElseThrow(() -> ApiException.notFound("직원을 찾을 수 없어요"));
    }

    private boolean staffRepoActive(Integer storeId, StoreWorker sw) {
        return storeWorkerRepository.existsByStoreIdAndWorkerIdAndStatus(storeId, sw.getWorker().getId(),
                StoreWorker.Status.ACTIVE);
    }

    /** 매장 파트 이름이거나 유동 근무 "직원"(또는 빈 값)만 허용. 알바생은 직원 파트를 기본 파트로 못 쓴다. */
    private String validPart(Integer storeId, String part, StoreWorker.Kind kind) {
        if (part.isBlank()) return ""; // 공백만 보내면 기본 파트 없음
        if ("직원".equals(part)) return part;
        StorePart found = storePartRepository.findByStoreIdOrderByStartMinAsc(storeId).stream()
                .filter(p -> p.getName().equals(part)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("INVALID_PART", "매장에 없는 파트예요: " + part));
        if (isPartTimer(kind) && found.isFlex()) {
            throw ApiException.badRequest("INVALID_PART", "직원 파트는 알바생의 기본 파트로 정할 수 없어요: " + part);
        }
        return part;
    }

    private static boolean isPartTimer(StoreWorker.Kind kind) {
        return kind == null || kind == StoreWorker.Kind.PART_TIMER || kind == StoreWorker.Kind.MINOR;
    }

    /** 직원 고정 파트: 요일마다 직원 파트 하나까지. */
    private List<List<String>> validEmployeeFixedParts(Integer storeId, List<List<String>> value) {
        if (value.size() != 7) throw ApiException.badRequest("INVALID_FIXED_PARTS", "고정 근무는 월~일 7칸이어야 해요");
        java.util.Map<String, Boolean> flexByName = new java.util.HashMap<>();
        storePartRepository.findByStoreIdOrderByStartMinAsc(storeId).forEach(p -> flexByName.put(p.getName(), p.isFlex()));
        List<List<String>> out = new java.util.ArrayList<>();
        for (List<String> day : value) {
            List<String> cell = new java.util.ArrayList<>();
            for (String n : day == null ? List.<String>of() : day) {
                Boolean flex = n == null ? null : flexByName.get(n);
                if (flex == null) throw ApiException.badRequest("INVALID_FIXED_PARTS", "매장에 없는 파트예요: " + n);
                if (!flex) {
                    throw ApiException.badRequest("INVALID_FIXED_PARTS", "알바 파트는 직원 고정 파트로 정할 수 없어요: " + n);
                }
                if (!cell.contains(n)) cell.add(n);
            }
            if (cell.size() > 1) {
                throw ApiException.badRequest("INVALID_FIXED_PARTS", "직원 고정 파트는 하루에 하나만 정할 수 있어요");
            }
            out.add(cell);
        }
        return out;
    }

    private List<List<String>> validFixedParts(Integer storeId, List<List<String>> value) {
        if (value.size() != 7) throw ApiException.badRequest("INVALID_FIXED_PARTS", "고정 근무는 월~일 7칸이어야 해요");
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Set<String> flexNames = new java.util.HashSet<>();
        storePartRepository.findByStoreIdOrderByStartMinAsc(storeId)
                .forEach(p -> (p.isFlex() ? flexNames : names).add(p.getName()));
        List<List<String>> out = new java.util.ArrayList<>();
        for (List<String> day : value) {
            List<String> cell = new java.util.ArrayList<>();
            for (String n : day == null ? List.<String>of() : day) {
                if (flexNames.contains(n)) {
                    throw ApiException.badRequest("INVALID_FIXED_PARTS", "직원 파트는 알바 고정 파트로 정할 수 없어요: " + n);
                }
                if (n == null || !names.contains(n)) {
                    throw ApiException.badRequest("INVALID_FIXED_PARTS", "매장에 없는 파트예요: " + n);
                }
                if (!cell.contains(n)) cell.add(n);
            }
            out.add(cell);
        }
        return out;
    }

    private static List<StaffPrefs.WorkHours> validWorkHours(List<StaffPrefs.WorkHours> value) {
        if (value.size() != 7) throw ApiException.badRequest("INVALID_WORK_HOURS", "근무 시간은 월~일 7칸이어야 해요");
        for (StaffPrefs.WorkHours h : value) {
            if (h == null) continue;
            if (h.startMin() < 0 || h.startMin() > 1439) {
                throw ApiException.badRequest("INVALID_WORK_HOURS", "출근 시각은 0시부터 23시 59분 사이여야 해요");
            }
            if (h.endMin() <= h.startMin()) {
                throw ApiException.badRequest("INVALID_WORK_HOURS", "퇴근 시각은 출근 시각보다 늦어야 해요");
            }
            if (h.endMin() - h.startMin() > 1440) {
                throw ApiException.badRequest("INVALID_WORK_HOURS", "근무 시간은 24시간을 넘을 수 없어요");
            }
        }
        return value;
    }

    private static void requireMinWage(Integer wage) {
        if (wage == null || wage < StoreWorkerDto.MIN_WAGE_2026) {
            throw ApiException.badRequest("WAGE_BELOW_MINIMUM",
                    "시급은 2026 최저임금 " + String.format("%,d", StoreWorkerDto.MIN_WAGE_2026) + "원 이상이어야 해요");
        }
        if (wage > StaffCommandDto.MAX_WAGE) {
            throw ApiException.badRequest("WAGE_TOO_HIGH", "시급은 1,000,000원 이하로 입력해 주세요");
        }
    }
}
