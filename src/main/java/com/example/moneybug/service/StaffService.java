package com.example.moneybug.service;

import com.example.moneybug.dto.CommonDto;
import com.example.moneybug.dto.StaffCommandDto;
import com.example.moneybug.dto.StaffDto;
import com.example.moneybug.dto.StoreWorkerDto;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StorePartRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.SubstituteApplicantRepository;
import com.example.moneybug.repository.SubstituteRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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

    /** GET /staff?status= — 사장님·직원. status 생략 시 사장님은 재직+대기, 직원은 재직만. */
    @Transactional(readOnly = true)
    public CommonDto.ListResponse<StaffDto> list(Integer userId, Integer storeId, StoreWorker.Status status) {
        Store store = access.requireOwnerOrStaff(userId, storeId);
        Collection<StoreWorker.Status> statuses;
        if (status != null) {
            statuses = List.of(status);
        } else if (access.isOwner(store, userId)) {
            statuses = List.of(StoreWorker.Status.ACTIVE, StoreWorker.Status.PENDING);
        } else {
            statuses = List.of(StoreWorker.Status.ACTIVE);
        }
        return CommonDto.ListResponse.of(storeWorkerRepository.findByStoreIdAndStatusIn(storeId, statuses).stream()
                .sorted(Comparator.comparing(StoreWorker::getId))
                .map(staffAssembler::toDto)
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
        Store store = access.requireOwner(userId, storeId);
        StoreWorker sw = access.staff(storeId, staffId);
        if (sw.getStatus() != StoreWorker.Status.PENDING) {
            throw ApiException.conflict("NOT_PENDING", "가입 신청 상태가 아니에요");
        }
        requireMinWage(request.hourlyWage());

        sw.setHourlyWage(request.hourlyWage());
        sw.setTaxType(request.taxType() != null ? request.taxType()
                : store.getDefaultDeduction() != null ? store.getDefaultDeduction() : StoreWorker.TaxType.NONE);
        sw.setStatus(StoreWorker.Status.ACTIVE);
        sw.setJoinedAt(TimeUtil.today());
        storeService.syncSize(store);

        notifier.sendToStaff(store, sw, Notification.Type.JOIN, store.getStoreName() + " 가입이 승인됐어요",
                "시급 " + String.format("%,d", sw.getHourlyWage()) + "원", sw.getId());
        return staffAssembler.toDto(sw);
    }

    /** POST .../reject — PENDING 만 가능. status=REJECTED, 신청자에게 JOIN 알림. */
    public void reject(Integer userId, Integer storeId, Integer staffId) {
        Store store = access.requireOwner(userId, storeId);
        StoreWorker sw = access.staff(storeId, staffId);
        if (sw.getStatus() != StoreWorker.Status.PENDING) {
            throw ApiException.conflict("NOT_PENDING", "가입 신청 상태가 아니에요");
        }
        sw.setStatus(StoreWorker.Status.REJECTED);
        notifier.sendToStaff(store, sw, Notification.Type.JOIN, store.getStoreName() + " 가입이 거절됐어요",
                null, sw.getId());
    }

    /** PATCH .../staff/{staffId} — 보낸 것만 반영, 시급은 최저임금 검증(시급 편집 3곳 모두 동일). */
    public StaffDto update(Integer userId, Integer storeId, Integer staffId, StaffCommandDto.UpdateRequest request) {
        access.requireOwner(userId, storeId);
        StoreWorker sw = access.staff(storeId, staffId);
        if (request.hourlyWage() != null) {
            requireMinWage(request.hourlyWage());
            sw.setHourlyWage(request.hourlyWage());
        }
        if (request.taxType() != null) sw.setTaxType(request.taxType());
        if (request.weeklyHolidayAuto() != null) sw.setWeeklyHolidayAuto(request.weeklyHolidayAuto());
        if (request.premium() != null) sw.setPremium(request.premium());
        if (request.part() != null) sw.setPart(request.part());
        if (request.kind() != null) sw.setKind(request.kind());
        if (request.fixedParts() != null) {
            StaffPrefs.setFixedParts(sw, validFixedParts(storeId, request.fixedParts()));
        }
        if (request.workHours() != null) {
            StaffPrefs.setWorkHours(sw, validWorkHours(request.workHours()));
        }
        return staffAssembler.toDto(sw);
    }

    /**
     * DELETE .../staff/{staffId} — 내보내기(LEFT). 부수효과(설계서 그대로):
     * 1) status=LEFT
     * 2) 오늘 이후이면서 출근 기록 없는 근무 삭제
     * 3) 진행 중 대타: 요청자면 CANCELLED, 지원자면 지원자에서 제거(후보였다면 taker=null, OPEN 으로 되돌림)
     * 4) 본인에게 JOIN 알림
     */
    public void remove(Integer userId, Integer storeId, Integer staffId) {
        Store store = access.requireOwner(userId, storeId);
        StoreWorker sw = access.staff(storeId, staffId);
        if (sw.getStatus() == StoreWorker.Status.LEFT) {
            return; // 멱등
        }
        sw.setStatus(StoreWorker.Status.LEFT);
        storeService.syncSize(store);
        Integer workerUserId = sw.getWorker().getId();

        List<SubstituteRequest> inProgress = substituteRequestRepository.findByStoreAndStatusIn(storeId,
                List.of(SubstituteRequest.Status.OPEN, SubstituteRequest.Status.ACCEPTED));
        for (SubstituteRequest r : inProgress) {
            if (r.getRequester().getId().equals(workerUserId)) {
                r.setStatus(SubstituteRequest.Status.CANCELLED);
            } else if (substituteApplicantRepository.existsByRequestIdAndApplicantId(r.getId(), workerUserId)) {
                substituteApplicantRepository.deleteByRequestIdAndApplicantId(r.getId(), workerUserId);
                if (r.getTaker() != null && r.getTaker().getId().equals(workerUserId)) {
                    r.setTaker(null);
                    r.setStatus(SubstituteRequest.Status.OPEN);
                }
            }
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

    private List<List<String>> validFixedParts(Integer storeId, List<List<String>> value) {
        if (value.size() != 7) throw ApiException.badRequest("INVALID_FIXED_PARTS", "고정 근무는 월~일 7칸이어야 해요");
        java.util.Set<String> names = new java.util.HashSet<>();
        storePartRepository.findByStoreIdOrderByStartMinAsc(storeId).forEach(p -> names.add(p.getName()));
        List<List<String>> out = new java.util.ArrayList<>();
        for (List<String> day : value) {
            List<String> cell = new java.util.ArrayList<>();
            for (String n : day == null ? List.<String>of() : day) {
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
            if (h.startMin() < 0 || h.startMin() > 1439 || h.endMin() <= h.startMin()
                    || h.endMin() - h.startMin() > 1440) {
                throw ApiException.badRequest("INVALID_WORK_HOURS", "퇴근 시각은 출근 시각보다 늦어야 해요");
            }
        }
        return value;
    }

    private static void requireMinWage(Integer wage) {
        if (wage == null || wage < StoreWorkerDto.MIN_WAGE_2026) {
            throw ApiException.badRequest("WAGE_BELOW_MINIMUM",
                    "시급은 2026 최저임금 " + String.format("%,d", StoreWorkerDto.MIN_WAGE_2026) + "원 이상이어야 해요");
        }
    }
}
