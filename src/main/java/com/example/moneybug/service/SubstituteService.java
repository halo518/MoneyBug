package com.example.moneybug.service;

import com.example.moneybug.dto.CommonDto;
import com.example.moneybug.dto.SubstituteCommandDto;
import com.example.moneybug.dto.SubstituteRequestDto;
import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.SubstituteApplicant;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.entity.SubstituteRequest.Status;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.MonthScheduleRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.SubstituteApplicantRepository;
import com.example.moneybug.repository.SubstituteRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 대타 (설계서 §4.7). 상태 흐름: OPEN -> (첫 지원자) ACCEPTED -> (사장님) APPROVED | REJECTED, 요청자 취소는 CANCELLED.
 * 지원자(applicant)와 후보(taker)는 User 기준이고, 근무 담당 교체 때 해당 매장의 StoreWorker 로 변환한다.
 * 동시성: 쓰기는 모두 매장 행 → 요청 행 순서로 PESSIMISTIC_WRITE 잠금을 먼저 잡고 나서 상태를 확인한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SubstituteService {

    private static final List<Status> PENDING = List.of(Status.OPEN, Status.ACCEPTED);
    /** 같은 요청의 동료 재알림 최소 간격. */
    static final int RESEND_COOLDOWN_MINUTES = 10;

    private final SubstituteRequestRepository substituteRequestRepository;
    private final SubstituteApplicantRepository substituteApplicantRepository;
    private final ScheduleRepository scheduleRepository;
    private final AttendanceRepository attendanceRepository;
    private final StoreRepository storeRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final MonthScheduleRepository monthScheduleRepository;
    private final StoreAccess access;
    private final NotificationPublisher notifier;
    private final OverlapChecker overlapChecker;
    private final SubstituteAssembler substituteAssembler;

    /**
     * POST .../shifts/{shiftId}/substitute-requests — 근무 당사자만, isWork 근무만, 사유 필수.
     * 부수효과: 사장님에게 SUB "{이름}님이 휴무·대타를 신청했어요"(body "M/d HH:mm–HH:mm") +
     * notifyPeers && store.substitutePush 이면 다른 재직 직원 전원에게 "…대타를 구해요".
     */
    public SubstituteRequestDto.Response create(Integer userId, Integer storeId, Integer shiftId,
                                                SubstituteRequestDto.Request request) {
        Store store = lockStore(storeId); // 같은 근무의 동시 요청을 줄 세운다(진행 중 요청 1건)
        StoreWorker me = access.requireActiveStaff(userId, storeId);
        Schedule shift = scheduleRepository.findByIdAndStore(shiftId, storeId)
                .filter(s -> !draftMonths(storeId).contains(month(s)))
                .orElseThrow(() -> ApiException.notFound("근무를 찾을 수 없어요"));
        if (!shift.getStoreWorker().getId().equals(me.getId())) {
            throw ApiException.forbidden("본인 근무만 대타를 요청할 수 있어요");
        }
        if (!shift.isWork()) {
            throw ApiException.conflict("NOT_WORK_SHIFT", "연차·휴무·시간 미정 근무는 대타를 요청할 수 없어요");
        }
        assertNotStarted(shift, "요청");
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw ApiException.badRequest("REASON_REQUIRED", "사유를 입력해 주세요");
        }
        if (!substituteRequestRepository.findByScheduleIdAndStatusIn(shift.getId(), PENDING).isEmpty()) {
            throw ApiException.conflict("ALREADY_REQUESTED", "이미 진행 중인 대타 요청이 있어요");
        }

        SubstituteRequest r = request.toEntity(shift, me.getWorker());
        r.setReason(request.getReason().trim());
        substituteRequestRepository.save(r);

        String name = me.getWorker().getName();
        String label = TimeUtil.label(shift);
        notifier.sendToOwner(store, Notification.Type.SUB, name + "님이 휴무·대타를 신청했어요", label, r.getId());
        if (r.isNotifyPeers() && store.isSubstitutePush()) {
            notifier.sendToActiveStaff(store, userId, Notification.Type.SUB, name + "님이 대타를 구해요", label,
                    r.getId());
        }
        return dto(r);
    }

    /**
     * GET .../substitute-requests — 기본 매장 전체 최신순. status=pending 은 open|accepted(그 외는 enum 이름),
     * mine=내가 요청자, available=내가 지원 가능(open ∧ 요청자 ≠ 나 ∧ 이미 지원 안 함 ∧ 근무 시작 전 ∧ 내 근무와 안 겹침).
     * 직원에게는 작성 중(DRAFT) 달 근무의 요청을 보여 주지 않는다(StateService.visibleShifts 와 같은 규칙).
     */
    @Transactional(readOnly = true)
    public CommonDto.ListResponse<SubstituteRequestDto.Response> list(Integer userId, Integer storeId, String status,
                                                                      boolean mine, boolean available) {
        Store store = access.requireOwnerOrStaff(userId, storeId);
        Set<String> hidden = access.isOwner(store, userId) ? Set.of() : draftMonths(storeId);
        StoreWorker meStaff = available ? access.activeStaff(userId, storeId).orElse(null) : null;
        Status exact = null;
        boolean pendingOnly = false;
        if (status != null && !status.isBlank()) {
            if (status.equalsIgnoreCase("pending")) {
                pendingOnly = true;
            } else {
                try {
                    exact = Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("INVALID_STATUS", "status 값이 올바르지 않아요");
                }
            }
        }
        final Status wanted = exact;
        final boolean pendingFilter = pendingOnly;
        LocalDateTime now = TimeUtil.now();

        return CommonDto.ListResponse.of(substituteRequestRepository.findByStore(storeId).stream()
                .filter(r -> !hidden.contains(month(r.getSchedule())))
                .filter(r -> !pendingFilter || (r.isPending() && TimeUtil.start(r.getSchedule()).isAfter(now)))
                .filter(r -> wanted == null || r.getStatus() == wanted)
                .filter(r -> !mine || r.getRequester().getId().equals(userId))
                .filter(r -> !available || (r.getStatus() == Status.OPEN
                        && !r.getRequester().getId().equals(userId)
                        && !substituteApplicantRepository.existsByRequestIdAndApplicantId(r.getId(), userId)
                        && TimeUtil.start(r.getSchedule()).isAfter(now)
                        && (meStaff == null || !overlapChecker.hasOverlap(meStaff, r.getSchedule(),
                                r.getSchedule().getId()))))
                .map(this::dto)
                .toList());
    }

    /**
     * POST .../apply — 직원. 요청자 본인이거나 이미 지원했으면 무시(그대로 반환).
     * 지원자에 추가, 첫 지원자면 후보(taker)=나 + ACCEPTED. 이미 시작한 근무·겹치는 근무는 409.
     * 알림: 요청자에게 "…대타를 수락했어요", 사장님에게 "{나}님이 {요청자}님 대타를 수락했어요"(body "승인하면 근무표에 반영돼요").
     */
    public SubstituteRequestDto.Response apply(Integer userId, Integer storeId, Integer requestId) {
        Store store = lockStore(storeId);
        StoreWorker me = access.requireActiveStaff(userId, storeId);
        SubstituteRequest r = lockRequest(storeId, requestId);
        if (draftMonths(storeId).contains(month(r.getSchedule()))) {
            throw ApiException.notFound("대타 요청을 찾을 수 없어요");
        }
        requirePending(r);

        if (r.getRequester().getId().equals(userId)
                || substituteApplicantRepository.existsByRequestIdAndApplicantId(r.getId(), userId)) {
            return dto(r);
        }
        Schedule shift = r.getSchedule();
        if (!TimeUtil.start(shift).isAfter(TimeUtil.now())) {
            throw ApiException.conflict("SHIFT_STARTED", "이미 시작한 근무에는 지원할 수 없어요");
        }
        overlapChecker.assertNoOverlap(me, shift, shift.getId());

        SubstituteApplicant applicant = new SubstituteApplicant();
        applicant.setRequest(r);
        applicant.setApplicant(me.getWorker());
        substituteApplicantRepository.save(applicant);
        if (r.getTaker() == null) {
            r.setTaker(me.getWorker());
            r.setStatus(Status.ACCEPTED);
        }

        String name = me.getWorker().getName();
        notifier.send(store, r.getRequester(), Notification.Type.SUB, name + "님이 대타를 수락했어요",
                TimeUtil.label(shift), r.getId());
        notifier.sendToOwner(store, Notification.Type.SUB,
                name + "님이 " + r.getRequester().getName() + "님 대타를 수락했어요", "승인하면 근무표에 반영돼요", r.getId());
        return dto(r);
    }

    /** POST .../cancel — 요청자만. CANCELLED, 지원자 전원 + 사장님에게 "…대타 요청을 취소했어요". */
    public SubstituteRequestDto.Response cancel(Integer userId, Integer storeId, Integer requestId) {
        Store store = lockStore(storeId);
        SubstituteRequest r = lockRequest(storeId, requestId);
        if (!r.getRequester().getId().equals(userId)) {
            throw ApiException.forbidden("요청한 본인만 취소할 수 있어요");
        }
        requirePending(r);
        r.setStatus(Status.CANCELLED);

        String title = r.getRequester().getName() + "님이 대타 요청을 취소했어요";
        String label = TimeUtil.label(r.getSchedule());
        for (SubstituteApplicant a : substituteApplicantRepository.findByRequestId(r.getId())) {
            notifier.send(store, a.getApplicant(), Notification.Type.SUB, title, label, r.getId());
        }
        notifier.sendToOwner(store, Notification.Type.SUB, title, label, r.getId());
        return dto(r);
    }

    /** POST .../approve — 사장님. 후보가 없으면 400. 근무 담당자 교체 + 알림. */
    public SubstituteRequestDto.Response approve(Integer userId, Integer storeId, Integer requestId) {
        lockStore(storeId);
        Store store = access.requireOwner(userId, storeId);
        SubstituteRequest r = lockRequest(storeId, requestId);
        doApprove(store, r);
        return dto(r);
    }

    /** POST .../reject — 사장님. REJECTED, 요청자(와 후보가 있으면 그 직원)에게 "대타 요청이 거절됐어요". */
    public SubstituteRequestDto.Response reject(Integer userId, Integer storeId, Integer requestId) {
        lockStore(storeId);
        Store store = access.requireOwner(userId, storeId);
        SubstituteRequest r = lockRequest(storeId, requestId);
        requirePending(r);
        r.setStatus(Status.REJECTED);

        String label = TimeUtil.label(r.getSchedule());
        notifier.send(store, r.getRequester(), Notification.Type.SUB, "대타 요청이 거절됐어요", label, r.getId());
        if (r.getTaker() != null && !r.getTaker().getId().equals(r.getRequester().getId())) {
            notifier.send(store, r.getTaker(), Notification.Type.SUB, "대타 요청이 거절됐어요", label, r.getId());
        }
        return dto(r);
    }

    /**
     * POST .../resend — 요청자·사장님. notifiedAt=now, substitutePush 가 켜져 있을 때만 동료 재알림(사장님 알림 없음).
     * 시작한 근무는 409 SHIFT_STARTED, 마지막 알림 뒤 {@value #RESEND_COOLDOWN_MINUTES}분 안이면 409 RESEND_TOO_SOON.
     * 요청자·이미 지원한 직원에게는 보내지 않는다.
     */
    public SubstituteRequestDto.Response resend(Integer userId, Integer storeId, Integer requestId) {
        Store store = lockStore(storeId);
        SubstituteRequest r = lockRequest(storeId, requestId);
        if (!access.isOwner(store, userId) && !r.getRequester().getId().equals(userId)) {
            throw ApiException.forbidden("요청자 또는 사장님만 다시 알릴 수 있어요");
        }
        requirePending(r);
        assertNotStarted(r.getSchedule(), "재알림");
        LocalDateTime now = TimeUtil.now();
        LocalDateTime last = r.getNotifiedAt() != null ? r.getNotifiedAt() : r.getCreatedAt();
        if (last != null && last.plusMinutes(RESEND_COOLDOWN_MINUTES).isAfter(now)) {
            throw ApiException.conflict("RESEND_TOO_SOON",
                    "다시 알림은 " + RESEND_COOLDOWN_MINUTES + "분 뒤에 보낼 수 있어요");
        }
        r.setNotifiedAt(now);
        if (store.isSubstitutePush()) {
            Set<Integer> skip = substituteApplicantRepository.findByRequestId(r.getId()).stream()
                    .map(a -> a.getApplicant().getId()).collect(Collectors.toCollection(HashSet::new));
            skip.add(r.getRequester().getId());
            String title = r.getRequester().getName() + "님 대타를 다시 구해요";
            String label = TimeUtil.label(r.getSchedule());
            for (StoreWorker sw : activeStaffOf(storeId)) {
                if (!skip.contains(sw.getWorker().getId())) {
                    notifier.sendToStaff(store, sw, Notification.Type.SUB, title, label, r.getId());
                }
            }
        }
        return dto(r);
    }

    /** POST .../assign — 사장님 직접 지정: 지원자 추가 + 후보 설정 + 곧바로 승인 처리. */
    public SubstituteRequestDto.Response assign(Integer userId, Integer storeId, Integer requestId,
                                                SubstituteCommandDto.AssignRequest request) {
        lockStore(storeId);
        Store store = access.requireOwner(userId, storeId);
        SubstituteRequest r = lockRequest(storeId, requestId);
        requirePending(r);
        StoreWorker target = access.staff(storeId, request.staffId());
        if (target.getStatus() != StoreWorker.Status.ACTIVE) {
            throw ApiException.conflict("NOT_ACTIVE", "재직 중인 직원만 지정할 수 있어요");
        }
        if (target.getWorker().getId().equals(r.getRequester().getId())) {
            throw ApiException.conflict("SELF_ASSIGN", "요청자 본인은 지정할 수 없어요");
        }
        if (!substituteApplicantRepository.existsByRequestIdAndApplicantId(r.getId(), target.getWorker().getId())) {
            SubstituteApplicant applicant = new SubstituteApplicant();
            applicant.setRequest(r);
            applicant.setApplicant(target.getWorker());
            substituteApplicantRepository.save(applicant);
        }
        r.setTaker(target.getWorker());
        r.setStatus(Status.ACCEPTED);
        doApprove(store, r);
        return dto(r);
    }

    /**
     * 승인 공통 처리: 후보 확인 -> 후보의 재직·겹침 검증 -> 근무의 담당자를 후보로 교체
     * (originalStoreWorker = 기존 담당자, substitute = true) -> APPROVED -> 요청자·대타자 알림.
     */
    private void doApprove(Store store, SubstituteRequest r) {
        requirePending(r);
        if (r.getTaker() == null) {
            throw ApiException.badRequest("NO_ACCEPTED_APPLICANT", "수락한 직원이 없어요");
        }
        StoreWorker takerStaff = access.activeStaff(r.getTaker().getId(), store.getId())
                .orElseThrow(() -> ApiException.conflict("TAKER_NOT_ACTIVE", "대타 직원이 더 이상 재직 중이 아니에요"));
        Schedule shift = r.getSchedule();
        assertNotStarted(shift, "승인");
        // 사장님이 그 사이 근무 담당자를 바꿨다면 이 요청은 더는 유효하지 않다.
        if (!shift.getStoreWorker().getWorker().getId().equals(r.getRequester().getId()) || !shift.isWork()) {
            throw ApiException.conflict("SHIFT_CHANGED", "근무가 바뀌어서 승인할 수 없어요. 요청을 거절해 주세요");
        }
        overlapChecker.assertNoOverlap(takerStaff, shift, shift.getId());

        // 원래 정규 근무자는 처음 한 번만 기록한다(대타의 대타여도 지난주 복사는 원래 근무자에게 간다).
        StoreWorker original = shift.isSubstitute() && shift.getOriginalStoreWorker() != null
                ? shift.getOriginalStoreWorker() : shift.getStoreWorker();
        boolean backToOriginal = original.getId().equals(takerStaff.getId());
        shift.setOriginalStoreWorker(backToOriginal ? null : original);
        shift.setStoreWorker(takerStaff);
        shift.setSubstitute(!backToOriginal);
        r.setStatus(Status.APPROVED);

        String label = TimeUtil.label(shift);
        notifier.send(store, r.getRequester(), Notification.Type.SUB, "대타 요청이 승인됐어요",
                label + " · " + takerStaff.getWorker().getName() + "님이 근무해요", r.getId());
        notifier.sendToStaff(store, takerStaff, Notification.Type.SUB, "대타 근무가 확정됐어요", label, r.getId());
    }

    /** 시작했거나 지난 근무, 출근 기록이 있는 근무는 대타로 바꿀 수 없다(급여가 엉킴). */
    private void assertNotStarted(Schedule shift, String action) {
        if (!TimeUtil.start(shift).isAfter(TimeUtil.now()) || attendanceRepository.existsByScheduleId(shift.getId())) {
            throw ApiException.conflict("SHIFT_STARTED", "이미 시작했거나 지난 근무는 대타 " + action + "할 수 없어요");
        }
    }

    /**
     * 시작 시각이 지난 진행 중 요청을 마감(CANCELLED)하고 요청자·후보에게 알린다. {@link SubstituteExpiry} 가 주기적으로 부른다.
     * 잠금 순서는 다른 쓰기와 같다(매장 → 요청). 마감했으면 true.
     */
    public boolean expireIfStarted(Integer storeId, Integer requestId, LocalDateTime now) {
        Store store = lockStore(storeId);
        SubstituteRequest r = substituteRequestRepository.findByIdForUpdate(requestId).orElse(null);
        if (r == null || !r.isPending() || TimeUtil.start(r.getSchedule()).isAfter(now)) {
            return false;
        }
        r.setStatus(Status.CANCELLED);
        String label = TimeUtil.label(r.getSchedule());
        notifier.send(store, r.getRequester(), Notification.Type.SUB, "대타 요청이 마감됐어요",
                label + " · 근무 시작 시각이 지나 자동으로 마감했어요", r.getId());
        if (r.getTaker() != null && !r.getTaker().getId().equals(r.getRequester().getId())) {
            notifier.send(store, r.getTaker(), Notification.Type.SUB, "대타 요청이 마감됐어요", label, r.getId());
        }
        return true;
    }

    private Store lockStore(Integer storeId) {
        return storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> ApiException.notFound("매장을 찾을 수 없어요"));
    }

    /** 요청 행만 잠그고(FOR UPDATE) 매장 소속을 확인한다. 매장 행 잠금 뒤에 부른다. */
    private SubstituteRequest lockRequest(Integer storeId, Integer requestId) {
        return substituteRequestRepository.findByIdForUpdate(requestId)
                .filter(r -> r.getSchedule().getStoreWorker().getStore().getId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("대타 요청을 찾을 수 없어요"));
    }

    /** 작성 중(DRAFT) 달("yyyy-MM"): 직원에게는 그 달 근무가 보이지 않는다. */
    private Set<String> draftMonths(Integer storeId) {
        return monthScheduleRepository.findByStoreIdOrderByMonthAsc(storeId).stream()
                .filter(m -> m.getStatus() == MonthSchedule.Status.DRAFT)
                .map(MonthSchedule::getMonth).collect(Collectors.toSet());
    }

    private static String month(Schedule s) {
        return YearMonth.from(s.getWorkDate()).toString();
    }

    private List<StoreWorker> activeStaffOf(Integer storeId) {
        return storeWorkerRepository.findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE);
    }

    private static void requirePending(SubstituteRequest r) {
        if (!r.isPending()) {
            throw ApiException.conflict("INVALID_STATE", "이미 처리된 대타 요청이에요");
        }
    }

    private SubstituteRequestDto.Response dto(SubstituteRequest r) {
        return substituteAssembler.toDto(r);
    }
}
