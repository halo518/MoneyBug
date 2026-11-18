package com.example.moneybug.service;

import com.example.moneybug.dto.HealthCertDto;
import com.example.moneybug.dto.UserDto;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 보건증 만료 알림: 만료일까지 남은 일수가 알바생이 고른 알림 시점(certAlertDays, 예: 30·14·7·0)과 같은 날,
 * 본인과 재직 중인 매장 사장님에게 CERT 알림을 보낸다 (FE employer_health_cert_alert_screen 의 "자동 발송").
 * 매시 정각(KST 9~21시)에 돌고, users.cert_alert_sent_on 으로 하루 한 번만 보낸다.
 * 그날 9~21시 내내 서버가 꺼져 있으면 그 시점 알림은 건너뛴다.
 */
@Component
@Slf4j
public class CertExpiryAlertJob {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final StoreWorkerRepository storeWorkerRepository;
    private final UserRepository userRepository;
    private final NotificationPublisher notifier;
    private final StoreChangeHub changeHub;
    private final TransactionTemplate tx;

    public CertExpiryAlertJob(StoreWorkerRepository storeWorkerRepository, UserRepository userRepository,
                              NotificationPublisher notifier, StoreChangeHub changeHub,
                              PlatformTransactionManager txManager) {
        this.storeWorkerRepository = storeWorkerRepository;
        this.userRepository = userRepository;
        this.notifier = notifier;
        this.changeHub = changeHub;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(cron = "0 0 9-21 * * *", zone = "Asia/Seoul")
    public void run() {
        run(TimeUtil.today());
    }

    void run(LocalDate today) {
        List<StoreWorker> rows = tx.execute(s -> storeWorkerRepository.findByStatusWithCertExpiryBetween(
                StoreWorker.Status.ACTIVE, today, today.plusDays(HealthCertDto.MAX_ALERT_DAY)));
        if (rows == null) return;
        List<Integer> workerIds = rows.stream().map(sw -> sw.getWorker().getId()).distinct().toList();
        for (Integer workerId : workerIds) {
            try {
                List<Integer> storeIds = tx.execute(s -> process(workerId, today));
                if (storeIds != null && !storeIds.isEmpty()) changeHub.bumpAll(storeIds);
            } catch (RuntimeException e) {
                log.warn("보건증 만료 알림 실패 userId={}: {}", workerId, e.toString());
            }
        }
    }

    /** 오늘이 알림 시점이면 보내고 알린 매장 id 를 돌려준다. 아니면 빈 목록. */
    private List<Integer> process(Integer workerId, LocalDate today) {
        User u = userRepository.lockForCert(workerId).orElse(null);
        if (u == null || u.getCertExpiry() == null || today.equals(u.getCertAlertSentOn())) return List.of();
        long left = ChronoUnit.DAYS.between(today, u.getCertExpiry());
        if (left < 0 || !UserDto.splitAlertDays(u.getCertAlertDays()).contains((int) left)) return List.of();

        String title = left == 0 ? "보건증이 오늘 만료돼요" : "보건증 만료 D-" + left;
        String body = "만료일 " + YMD.format(u.getCertExpiry());
        List<Integer> storeIds = new ArrayList<>();
        for (StoreWorker sw : storeWorkerRepository.findByWorkerIdAndStatusIn(workerId,
                List.of(StoreWorker.Status.ACTIVE))) {
            notifier.sendToStaff(sw.getStore(), sw, Notification.Type.CERT, title, body, sw.getId());
            notifier.sendToOwner(sw.getStore(), Notification.Type.CERT, u.getName() + "님 " + title, body,
                    sw.getId());
            storeIds.add(sw.getStore().getId());
        }
        u.setCertAlertSentOn(today);
        return storeIds;
    }
}
