package com.example.moneybug.service;

import com.example.moneybug.dto.AttendanceViewDto;
import com.example.moneybug.dto.ContractDto;
import com.example.moneybug.dto.DashboardDto;
import com.example.moneybug.dto.ScheduleDto;
import com.example.moneybug.dto.SubstituteRequestDto;
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
import com.example.moneybug.repository.SubstituteRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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

    private static final int CERT_ALERT_DAYS = 30;

    private final ScheduleRepository scheduleRepository;
    private final AttendanceRepository attendanceRepository;
    private final SubstituteRequestRepository substituteRequestRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final ContractRepository contractRepository;
    private final NotificationRepository notificationRepository;
    private final StoreAccess access;
    private final StaffAssembler staffAssembler;
    private final SubstituteAssembler substituteAssembler;

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

        // 진행 중 대타 (open | accepted). 근무가 이미 시작한 요청은 승인할 수 없으므로 뺀다.
        List<SubstituteRequestDto.Response> pendingSubs = substituteRequestRepository
                .findByStoreAndStatusIn(storeId, List.of(SubstituteRequest.Status.OPEN,
                        SubstituteRequest.Status.ACCEPTED)).stream()
                .filter(r -> TimeUtil.start(r.getSchedule()).isAfter(now))
                .map(substituteAssembler::toDto)
                .toList();

        int pendingJoin = (int) storeWorkerRepository.countByStoreIdAndStatus(storeId, StoreWorker.Status.PENDING);

        // 보건증 미등록·만료·30일 이내 + 서명 대기 계약서 (재직 직원 기준). FE web_dashboard_screen certAlerts 와 같은 규칙.
        List<DashboardDto.CertAlert> certAlerts = new ArrayList<>();
        List<String> unsigned = new ArrayList<>();
        for (StoreWorker sw : storeWorkerRepository.findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE)) {
            var worker = sw.getWorker();
            if (worker.getCertExpiry() == null) {
                certAlerts.add(new DashboardDto.CertAlert(sw.getId(), null));
            } else {
                long daysLeft = ChronoUnit.DAYS.between(date, worker.getCertExpiry());
                if (daysLeft <= CERT_ALERT_DAYS) {
                    certAlerts.add(new DashboardDto.CertAlert(sw.getId(), daysLeft));
                }
            }
            Optional<Contract> latest = contractRepository
                    .findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(sw.getId());
            if (latest.isPresent() && !latest.get().isSigned()) {
                unsigned.add(String.valueOf(sw.getId()));
            }
        }
        // 급한 순 (미등록이 맨 앞)
        certAlerts.sort(Comparator.comparingLong(c -> c.daysLeft() == null ? Long.MIN_VALUE : c.daysLeft()));

        long unread = notificationRepository.countByToUserIdAndStoreIdAndReadFalse(userId, store.getId());
        return new DashboardDto.Response(todayShifts, pendingSubs, pendingJoin, certAlerts, unsigned, unread);
    }
}
