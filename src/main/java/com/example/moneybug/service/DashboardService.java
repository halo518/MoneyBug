package com.example.moneybug.service;

import com.example.moneybug.dto.AttendanceViewDto;
import com.example.moneybug.dto.ContractDto;
import com.example.moneybug.dto.DashboardDto;
import com.example.moneybug.dto.ScheduleDto;
import com.example.moneybug.dto.SubstituteRequestDto;
import com.example.moneybug.dto.UserDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Contract;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.ContractRepository;
import com.example.moneybug.repository.NotificationRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.SubstituteApplicantRepository;
import com.example.moneybug.repository.SubstituteRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 대시보드 집계 (설계서 §4.11). */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardService {

    private static final int DEFAULT_CERT_ALERT_DAYS = 30;

    private final ScheduleRepository scheduleRepository;
    private final AttendanceRepository attendanceRepository;
    private final SubstituteRequestRepository substituteRequestRepository;
    private final SubstituteApplicantRepository substituteApplicantRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final ContractRepository contractRepository;
    private final NotificationRepository notificationRepository;
    private final StoreAccess access;
    private final StaffAssembler staffAssembler;

    public DashboardDto.Response dashboard(Integer userId, Integer storeId, LocalDate date) {
        Store store = access.requireOwner(userId, storeId);
        LocalDateTime now = TimeUtil.now();

        // 오늘 근무 (실제 근무만) + 출근 기록 + 상태
        List<Schedule> shifts = scheduleRepository.findByStoreAndDate(storeId, date).stream()
                .filter(Schedule::isWork).toList();
        Map<Integer, Attendance> attendance = shifts.isEmpty() ? Map.of()
                : attendanceRepository.findByScheduleIdIn(shifts.stream().map(Schedule::getId).toList()).stream()
                .collect(Collectors.toMap(a -> a.getSchedule().getId(), Function.identity()));
        List<DashboardDto.TodayShift> todayShifts = new ArrayList<>();
        for (Schedule s : shifts) {
            Attendance a = attendance.get(s.getId());
            AttendanceViewDto.View view = a == null ? null : AttendanceCalc.view(a, now);
            todayShifts.add(new DashboardDto.TodayShift(ScheduleDto.Response.from(s),
                    staffAssembler.toDto(s.getStoreWorker()), view, AttendanceCalc.status(s, a, now)));
        }

        // 진행 중 대타 (open | accepted)
        List<SubstituteRequestDto.Response> pendingSubs = substituteRequestRepository
                .findByStoreAndStatusIn(storeId, List.of(SubstituteRequest.Status.OPEN,
                        SubstituteRequest.Status.ACCEPTED)).stream()
                .map(r -> SubstituteRequestDto.Response.from(r, substituteApplicantRepository
                        .findByRequestId(r.getId()).stream().map(ap -> ap.getApplicant().getId()).toList()))
                .toList();

        int pendingJoin = (int) storeWorkerRepository.countByStoreIdAndStatus(storeId, StoreWorker.Status.PENDING);

        // 보건증 만료 임박·미등록 + 서명 대기 계약서 (재직 직원 기준)
        List<DashboardDto.CertAlert> certAlerts = new ArrayList<>();
        List<Integer> unsigned = new ArrayList<>();
        for (StoreWorker sw : storeWorkerRepository.findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE)) {
            var worker = sw.getWorker();
            if (worker.getCertExpiry() == null) {
                certAlerts.add(new DashboardDto.CertAlert(sw.getId(), null));
            } else {
                long daysLeft = ChronoUnit.DAYS.between(date, worker.getCertExpiry());
                int threshold = Collections.max(
                        UserDto.splitAlertDays(worker.getCertAlertDays()).isEmpty()
                                ? List.of(DEFAULT_CERT_ALERT_DAYS)
                                : UserDto.splitAlertDays(worker.getCertAlertDays()));
                if (daysLeft <= threshold) {
                    certAlerts.add(new DashboardDto.CertAlert(sw.getId(), daysLeft));
                }
            }
            Optional<Contract> latest = contractRepository
                    .findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(sw.getId());
            if (latest.isPresent() && !latest.get().isSigned()) {
                unsigned.add(sw.getId());
            }
        }

        long unread = notificationRepository.countByToUserIdAndStoreIdAndReadFalse(userId, store.getId());
        return new DashboardDto.Response(todayShifts, pendingSubs, pendingJoin, certAlerts, unsigned, unread);
    }
}
