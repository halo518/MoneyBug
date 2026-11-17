package com.example.moneybug.service;

import com.example.moneybug.dto.AttendanceViewDto;
import com.example.moneybug.dto.CommonDto;
import com.example.moneybug.dto.MeDto;
import com.example.moneybug.dto.ScheduleDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 출퇴근 · 근태 (설계서 §4.6). 모든 시각 판정은 서버 시각 기준(클라이언트 시각 신뢰 금지). */
@Service
@Transactional
@RequiredArgsConstructor
public class AttendanceService {

    /** 근무 시작 이 시간(분) 전부터 출근할 수 있다. */
    private static final int CLOCK_IN_EARLY_MINUTES = 30;
    /** 소정 종료 후 이 시간(시간)이 지나면 퇴근 기록 불가 — 사장님이 수정해야 한다. */
    static final int CLOCK_OUT_GRACE_HOURS = 12;
    /** 한 근태 기록의 최대 길이(시간). */
    static final int MAX_WORK_HOURS = 24;
    /** 서버·기기 시각 오차 허용(분). */
    private static final int FUTURE_SLACK_MINUTES = 1;

    private final AttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final StoreAccess access;
    private final StaffAssembler staffAssembler;
    private final NotificationPublisher notifier;
    private final QrTokenService qrTokens;

    /**
     * POST .../shifts/{shiftId}/clock-in
     * 본인 근무인지, isWork 인지 확인. 이미 기록이 있으면 새로 만들지 않고 기존 기록을 반환(멱등).
     * 종료 시각이 지났으면 409, 시작 30분 전 이전이면 409. 시각은 서버 시각으로 기록한다.
     * QR 토큰(qrToken)은 필수. 없거나 만료·위조·다른 매장이면 400 INVALID_QR.
     */
    public AttendanceViewDto.View clockIn(Integer userId, Integer storeId, Integer shiftId,
                                          AttendanceViewDto.ClockInRequest request) {
        StoreWorker me = access.requireActiveStaff(userId, storeId);
        if (request == null || !qrTokens.verify(storeId, request.qrToken(), Instant.now())) {
            throw ApiException.badRequest("INVALID_QR", "QR 코드가 만료됐거나 다른 매장 QR이에요");
        }
        Schedule shift = scheduleRepository.findByIdAndStore(shiftId, storeId)
                .orElseThrow(() -> ApiException.notFound("근무를 찾을 수 없어요"));
        if (!shift.getStoreWorker().getId().equals(me.getId())) {
            throw ApiException.forbidden("본인 근무만 출근할 수 있어요");
        }
        if (!shift.isWork()) {
            throw ApiException.conflict("NOT_WORK_SHIFT", "연차·휴무·시간 미정 근무는 출근할 수 없어요");
        }

        LocalDateTime now = TimeUtil.now();
        var existing = attendanceRepository.findByScheduleId(shift.getId());
        if (existing.isPresent()) {
            if (existing.get().getActualEnd() != null) {
                throw ApiException.conflict("ALREADY_CLOCKED_OUT", "이미 퇴근한 근무예요");
            }
            return AttendanceCalc.view(existing.get(), now);
        }
        if (!attendanceRepository.findOpenByUser(userId).isEmpty()) {
            throw ApiException.conflict("ALREADY_CLOCKED_IN", "퇴근하지 않은 근무가 있어요. 먼저 퇴근해 주세요");
        }
        if (now.isAfter(TimeUtil.end(shift))) {
            throw ApiException.conflict("SHIFT_ENDED", "근무 시간이 지나 출근할 수 없어요");
        }
        if (now.isBefore(TimeUtil.start(shift).minusMinutes(CLOCK_IN_EARLY_MINUTES))) {
            throw ApiException.conflict("TOO_EARLY", "근무 시작 30분 전부터 출근할 수 있어요");
        }

        Attendance a = new Attendance();
        a.setSchedule(shift);
        a.setActualStart(now);
        a.setCheckMethod(Attendance.CheckMethod.QR);
        attendanceRepository.save(a);
        return AttendanceCalc.view(a, now);
    }

    /** GET .../qr-token — 사장님. 30초마다 바뀌는 출근 QR 토큰. */
    @Transactional(readOnly = true)
    public AttendanceViewDto.QrToken qrToken(Integer userId, Integer storeId) {
        access.requireOwner(userId, storeId);
        Instant now = Instant.now();
        return new AttendanceViewDto.QrToken(qrTokens.issue(storeId, now), qrTokens.expiresAt(now),
                QrTokenService.PERIOD_SECONDS);
    }

    /** POST .../attendance/clock-out — 내 열린 출근 기록(clockOut == null)에 퇴근 기록. 없으면 404. */
    public AttendanceViewDto.View clockOut(Integer userId, Integer storeId) {
        access.requireActiveStaff(userId, storeId);
        Attendance a = attendanceRepository.findOpenByUserAndStore(userId, storeId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("퇴근할 출근 기록이 없어요"));
        LocalDateTime now = TimeUtil.now();
        if (now.isAfter(TimeUtil.end(a.getSchedule()).plusHours(CLOCK_OUT_GRACE_HOURS))) {
            throw ApiException.conflict("CLOCK_OUT_EXPIRED",
                    "퇴근 가능 시간이 지났어요. 사장님께 근태 수정을 요청해 주세요");
        }
        a.setActualEnd(now);
        a.setOvertimeMinutes(AttendanceCalc.overtimeMinutes(a.getSchedule(), a));
        return AttendanceCalc.view(a, now);
    }

    /** GET .../attendance?date= — 사장님. 그날 실제 근무마다 근태 + 근무 + 직원 요약. */
    @Transactional(readOnly = true)
    public CommonDto.ListResponse<AttendanceViewDto.DayItem> listByDate(Integer userId, Integer storeId,
                                                                        LocalDate date) {
        access.requireOwner(userId, storeId);
        List<Schedule> shifts = scheduleRepository.findByStoreAndDate(storeId, date).stream()
                .filter(Schedule::isWork).toList();
        Map<Integer, Attendance> byShift = shifts.isEmpty() ? Map.of()
                : attendanceRepository.findByScheduleIdIn(shifts.stream().map(Schedule::getId).toList()).stream()
                .collect(Collectors.toMap(a -> a.getSchedule().getId(), Function.identity()));

        LocalDateTime now = TimeUtil.now();
        List<AttendanceViewDto.DayItem> items = new ArrayList<>();
        for (Schedule s : shifts) {
            Attendance a = byShift.get(s.getId());
            items.add(new AttendanceViewDto.DayItem(ScheduleDto.Response.from(s),
                    staffAssembler.toDto(s.getStoreWorker()),
                    a == null ? null : AttendanceCalc.view(a, now),
                    AttendanceCalc.status(s, a, now)));
        }
        return CommonDto.ListResponse.of(items);
    }

    /** GET .../attendance/{id} — 사장님, 본인. */
    @Transactional(readOnly = true)
    public AttendanceViewDto.View get(Integer userId, Integer storeId, Integer attendanceId) {
        Store store = access.store(storeId);
        Attendance a = find(storeId, attendanceId);
        boolean self = a.getSchedule().getStoreWorker().getWorker().getId().equals(userId);
        if (!access.isOwner(store, userId) && !self) {
            throw ApiException.forbidden("사장님 또는 본인만 볼 수 있어요");
        }
        return AttendanceCalc.view(a, TimeUtil.now());
    }

    /**
     * PATCH .../attendance/{id} — 사장님. 사유 필수, clockOut 은 선택(없으면 기존 값 유지).
     * 부수효과: editReason 저장 + 직원에게 PAY "근태 기록이 수정됐어요"(body = 사유).
     */
    public AttendanceViewDto.View edit(Integer userId, Integer storeId, Integer attendanceId,
                                       AttendanceViewDto.EditRequest request) {
        Store store = access.requireOwner(userId, storeId);
        Attendance a = find(storeId, attendanceId);
        requireReason(request.reason());

        LocalDateTime clockIn = request.clockIn() != null ? request.clockIn() : a.getActualStart();
        LocalDateTime clockOut = request.clockOut() != null ? request.clockOut() : a.getActualEnd();
        requireOrder(clockIn, clockOut);
        requireNotFuture(clockIn, clockOut);

        a.setActualStart(clockIn);
        a.setActualEnd(clockOut);
        a.setEditReason(request.reason().trim());
        a.setOvertimeMinutes(AttendanceCalc.overtimeMinutes(a.getSchedule(), a));

        notifier.sendToStaff(store, a.getSchedule().getStoreWorker(), Notification.Type.PAY,
                "근태 기록이 수정됐어요", a.getEditReason(), a.getId());
        return AttendanceCalc.view(a, TimeUtil.now());
    }

    /** POST .../shifts/{shiftId}/attendance — 사장님이 출근 기록을 새로 만든다(MANUAL). 알림은 만들지 않는다. */
    public AttendanceViewDto.View createManual(Integer userId, Integer storeId, Integer shiftId,
                                               AttendanceViewDto.EditRequest request) {
        access.requireOwner(userId, storeId);
        Schedule shift = scheduleRepository.findByIdAndStore(shiftId, storeId)
                .orElseThrow(() -> ApiException.notFound("근무를 찾을 수 없어요"));
        requireReason(request.reason());
        if (request.clockIn() == null) {
            throw ApiException.badRequest("CLOCK_IN_REQUIRED", "출근 시각을 입력해 주세요");
        }
        if (!shift.isWork()) {
            throw ApiException.conflict("NOT_WORK_SHIFT", "연차·휴무·시간 미정 근무에는 근태를 만들 수 없어요");
        }
        if (attendanceRepository.existsByScheduleId(shift.getId())) {
            throw ApiException.conflict("ATTENDANCE_EXISTS", "이미 출근 기록이 있는 근무예요");
        }
        requireOrder(request.clockIn(), request.clockOut());
        requireNotFuture(request.clockIn(), request.clockOut());

        Attendance a = new Attendance();
        a.setSchedule(shift);
        a.setActualStart(request.clockIn());
        a.setActualEnd(request.clockOut());
        a.setCheckMethod(Attendance.CheckMethod.MANUAL);
        a.setEditReason(request.reason().trim());
        a.setOvertimeMinutes(AttendanceCalc.overtimeMinutes(shift, a));
        attendanceRepository.save(a);
        return AttendanceCalc.view(a, TimeUtil.now());
    }

    /** GET /me/today — 열린 출근 기록의 근무, 없으면 오늘 내 실제 근무 중 아직 끝나지 않은 첫 근무. */
    @Transactional(readOnly = true)
    public MeDto.Today today(Integer userId) {
        LocalDateTime now = TimeUtil.now();
        var open = attendanceRepository.findOpenByUser(userId).stream().findFirst();
        if (open.isPresent()) {
            Attendance a = open.get();
            return new MeDto.Today(ScheduleDto.Response.from(a.getSchedule()), true, AttendanceCalc.view(a, now));
        }

        LocalDate today = now.toLocalDate();
        List<Schedule> mine = new ArrayList<>();
        for (StoreWorker sw : storeWorkerRepository.findByWorkerIdAndStatusIn(userId,
                List.of(StoreWorker.Status.ACTIVE))) {
            scheduleRepository.findByWorkerBetween(sw.getId(), today, today).stream()
                    .filter(Schedule::isWork).forEach(mine::add);
        }
        mine.sort(Comparator.comparing(TimeUtil::start));
        Map<Integer, Attendance> done = mine.isEmpty() ? Map.of()
                : attendanceRepository.findByScheduleIdIn(mine.stream().map(Schedule::getId).toList()).stream()
                .collect(Collectors.toMap(a -> a.getSchedule().getId(), Function.identity()));
        Schedule next = mine.stream().filter(s -> !done.containsKey(s.getId())).findFirst()
                .orElse(mine.isEmpty() ? null : mine.get(mine.size() - 1));
        return new MeDto.Today(next == null ? null : ScheduleDto.Response.from(next), false,
                next != null && done.containsKey(next.getId()) ? AttendanceCalc.view(done.get(next.getId()), now) : null);
    }

    private Attendance find(Integer storeId, Integer attendanceId) {
        return attendanceRepository.findByIdAndStore(attendanceId, storeId)
                .orElseThrow(() -> ApiException.notFound("근태 기록을 찾을 수 없어요"));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("REASON_REQUIRED", "수정 사유를 입력해 주세요");
        }
    }

    private static void requireOrder(LocalDateTime clockIn, LocalDateTime clockOut) {
        if (clockIn != null && clockOut != null && !clockOut.isAfter(clockIn)) {
            throw ApiException.badRequest("INVALID_CLOCK_RANGE", "퇴근 시각은 출근 시각보다 늦어야 해요");
        }
        if (clockIn != null && clockOut != null && clockOut.isAfter(clockIn.plusHours(MAX_WORK_HOURS))) {
            throw ApiException.badRequest("INVALID_CLOCK_RANGE", "근무 시간이 24시간을 넘을 수 없어요");
        }
    }

    private static void requireNotFuture(LocalDateTime clockIn, LocalDateTime clockOut) {
        LocalDateTime limit = TimeUtil.now().plusMinutes(FUTURE_SLACK_MINUTES);
        if ((clockIn != null && clockIn.isAfter(limit)) || (clockOut != null && clockOut.isAfter(limit))) {
            throw ApiException.badRequest("FUTURE_TIME", "아직 오지 않은 시각은 입력할 수 없어요");
        }
    }
}
