package com.example.moneybug.service;

import com.example.moneybug.dto.CommonDto;
import com.example.moneybug.dto.SubstituteCommandDto;
import com.example.moneybug.dto.SubstituteRequestDto;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.SubstituteApplicant;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.entity.SubstituteRequest.Status;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.SubstituteApplicantRepository;
import com.example.moneybug.repository.SubstituteRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 대타 (설계서 §4.7). 상태 흐름: OPEN -> (첫 지원자) ACCEPTED -> (사장님) APPROVED | REJECTED, 요청자 취소는 CANCELLED.
 * 지원자(applicant)와 후보(taker)는 User 기준이고, 근무 담당 교체 때 해당 매장의 StoreWorker 로 변환한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SubstituteService {

    private static final List<Status> PENDING = List.of(Status.OPEN, Status.ACCEPTED);

    private final SubstituteRequestRepository substituteRequestRepository;
    private final SubstituteApplicantRepository substituteApplicantRepository;
    private final ScheduleRepository scheduleRepository;
    private final AttendanceRepository attendanceRepository;
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
        Store store = access.store(storeId);
        StoreWorker me = access.requireActiveStaff(userId, storeId);
        Schedule shift = scheduleRepository.findByIdAndStore(shiftId, storeId)
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
     * mine=내가 요청자, available=내가 지원 가능(open ∧ 요청자 ≠ 나 ∧ 이미 지원 안 함 ∧ 근무 시작 전).
     */
    @Transactional(readOnly = true)
    public CommonDto.ListResponse<SubstituteRequestDto.Response> list(Integer userId, Integer storeId, String status,
                                                                      boolean mine, boolean available) {
        access.requireOwnerOrStaff(userId, storeId);
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
                .filter(r -> !pendingFilter || (r.isPending() && TimeUtil.start(r.getSchedule()).isAfter(now)))
                .filter(r -> wanted == null || r.getStatus() == wanted)
                .filter(r -> !mine || r.getRequester().getId().equals(userId))
                .filter(r -> !available || (r.getStatus() == Status.OPEN
                        && !r.getRequester().getId().equals(userId)
                        && !substituteApplicantRepository.existsByRequestIdAndApplicantId(r.getId(), userId)
                        && TimeUtil.start(r.getSchedule()).isAfter(now)))
                .map(this::dto)
                .toList());
    }

    /**
     * POST .../apply — 직원. 요청자 본인이거나 이미 지원했으면 무시(그대로 반환).
     * 지원자에 추가, 첫 지원자면 후보(taker)=나 + ACCEPTED. 이미 시작한 근무·겹치는 근무는 409.
     * 알림: 요청자에게 "…대타를 수락했어요", 사장님에게 "…수락했어요"(body "승인하면 근무표에 반영돼요").
     */
    public SubstituteRequestDto.Response apply(Integer userId, Integer storeId, Integer requestId) {
        Store store = access.store(storeId);
        StoreWorker me = access.requireActiveStaff(userId, storeId);
        SubstituteRequest r = find(storeId, requestId);
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

        String title = me.getWorker().getName() + "님이 대타를 수락했어요";
        notifier.send(store, r.getRequester(), Notification.Type.SUB, title, TimeUtil.label(shift), r.getId());
        notifier.sendToOwner(store, Notification.Type.SUB, title, "승인하면 근무표에 반영돼요", r.getId());
        return dto(r);
    }

    /** POST .../cancel — 요청자만. CANCELLED, 지원자 전원 + 사장님에게 "…대타 요청을 취소했어요". */
    public SubstituteRequestDto.Response cancel(Integer userId, Integer storeId, Integer requestId) {
        Store store = access.store(storeId);
        SubstituteRequest r = find(storeId, requestId);
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
        Store store = access.requireOwner(userId, storeId);
        SubstituteRequest r = find(storeId, requestId);
        doApprove(store, r);
        return dto(r);
    }

    /** POST .../reject — 사장님. REJECTED, 요청자(와 후보가 있으면 그 직원)에게 "대타 요청이 거절됐어요". */
    public SubstituteRequestDto.Response reject(Integer userId, Integer storeId, Integer requestId) {
        Store store = access.requireOwner(userId, storeId);
        SubstituteRequest r = find(storeId, requestId);
        requirePending(r);
        r.setStatus(Status.REJECTED);

        String label = TimeUtil.label(r.getSchedule());
        notifier.send(store, r.getRequester(), Notification.Type.SUB, "대타 요청이 거절됐어요", label, r.getId());
        if (r.getTaker() != null && !r.getTaker().getId().equals(r.getRequester().getId())) {
            notifier.send(store, r.getTaker(), Notification.Type.SUB, "대타 요청이 거절됐어요", label, r.getId());
        }
        return dto(r);
    }

    /** POST .../resend — 요청자·사장님. notifiedAt=now, substitutePush 가 켜져 있을 때만 동료 재알림(사장님 알림 없음). */
    public SubstituteRequestDto.Response resend(Integer userId, Integer storeId, Integer requestId) {
        Store store = access.store(storeId);
        SubstituteRequest r = find(storeId, requestId);
        if (!access.isOwner(store, userId) && !r.getRequester().getId().equals(userId)) {
            throw ApiException.forbidden("요청자 또는 사장님만 다시 알릴 수 있어요");
        }
        requirePending(r);
        r.setNotifiedAt(TimeUtil.now());
        if (store.isSubstitutePush()) {
            notifier.sendToActiveStaff(store, r.getRequester().getId(), Notification.Type.SUB,
                    r.getRequester().getName() + "님 대타를 다시 구해요", TimeUtil.label(r.getSchedule()), r.getId());
        }
        return dto(r);
    }

    /** POST .../assign — 사장님 직접 지정: 지원자 추가 + 후보 설정 + 곧바로 승인 처리. */
    public SubstituteRequestDto.Response assign(Integer userId, Integer storeId, Integer requestId,
                                                SubstituteCommandDto.AssignRequest request) {
        Store store = access.requireOwner(userId, storeId);
        SubstituteRequest r = find(storeId, requestId);
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

        shift.setOriginalStoreWorker(shift.getStoreWorker());
        shift.setStoreWorker(takerStaff);
        shift.setSubstitute(true);
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

    private SubstituteRequest find(Integer storeId, Integer requestId) {
        return substituteRequestRepository.findByIdAndStore(requestId, storeId)
                .orElseThrow(() -> ApiException.notFound("대타 요청을 찾을 수 없어요"));
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
