package com.example.moneybug.service;

import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.repository.AttendanceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/** 퇴근 미기록 처리: 종료 1시간 뒤 사장님께 알림(1번), 종료 4시간 뒤 예정 종료 시각으로 자동 퇴근. */
@Component
@Slf4j
public class AttendanceAutoClockOut {

    static final int NOTIFY_AFTER_HOURS = 1;
    static final String AUTO_REASON = "퇴근 미기록 자동 처리(예정 종료 시각)";

    private final AttendanceRepository attendanceRepository;
    private final NotificationPublisher notifier;
    private final StoreChangeHub changeHub;
    private final TransactionTemplate tx;

    public AttendanceAutoClockOut(AttendanceRepository attendanceRepository, NotificationPublisher notifier,
                                  StoreChangeHub changeHub, PlatformTransactionManager txManager) {
        this.attendanceRepository = attendanceRepository;
        this.notifier = notifier;
        this.changeHub = changeHub;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelay = 300_000)
    public void run() {
        run(TimeUtil.now());
    }

    void run(LocalDateTime now) {
        List<Attendance> open = tx.execute(s -> attendanceRepository.findAllOpen());
        if (open == null) return;
        for (Attendance candidate : open) {
            try {
                tx.executeWithoutResult(s -> process(candidate.getId(), now));
            } catch (RuntimeException e) {
                log.warn("자동 퇴근 처리 실패 attendanceId={}: {}", candidate.getId(), e.toString());
            }
        }
    }

    private void process(Integer id, LocalDateTime now) {
        Attendance a = attendanceRepository.findById(id).orElse(null);
        if (a == null || a.getActualEnd() != null) return;
        Schedule s = a.getSchedule();
        LocalDateTime end = TimeUtil.end(s);
        Store store = s.getStoreWorker().getStore();

        if (!now.isBefore(end.plusHours(AttendanceService.CLOCK_OUT_GRACE_HOURS))) {
            LocalDateTime out = end.isAfter(a.getActualStart()) ? end : a.getActualStart();
            a.setActualEnd(out);
            a.setEditReason(AUTO_REASON);
            a.setOvertimeMinutes(AttendanceCalc.overtimeMinutes(s, a));
            String body = s.getStoreWorker().getWorker().getName() + " · " + TimeUtil.label(s);
            notifier.sendToOwner(store, Notification.Type.SUB, "퇴근이 자동 처리됐어요", body, a.getId());
            notifier.sendToStaff(store, s.getStoreWorker(), Notification.Type.PAY, "퇴근이 자동 처리됐어요",
                    TimeUtil.label(s), a.getId());
            changeHub.bump(store.getId());
        } else if (a.getMissingOutNotifiedAt() == null && !now.isBefore(end.plusHours(NOTIFY_AFTER_HOURS))) {
            notifier.sendToOwner(store, Notification.Type.SUB, "퇴근 기록이 없어요",
                    s.getStoreWorker().getWorker().getName() + " · " + TimeUtil.label(s), a.getId());
            a.setMissingOutNotifiedAt(now);
            changeHub.bump(store.getId());
        }
    }
}
