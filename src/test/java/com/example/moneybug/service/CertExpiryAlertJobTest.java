package com.example.moneybug.service;

import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CertExpiryAlertJobTest {

    private final StoreWorkerRepository swRepo = mock(StoreWorkerRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final NotificationPublisher notifier = mock(NotificationPublisher.class);
    private final StoreChangeHub hub = mock(StoreChangeHub.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final CertExpiryAlertJob job = new CertExpiryAlertJob(swRepo, userRepo, notifier, hub, tm);

    private final LocalDate today = LocalDate.of(2026, 11, 18);
    private final User u = new User();
    private final Store store = new Store();
    private final StoreWorker sw = new StoreWorker();

    CertExpiryAlertJobTest() {
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        u.setId(5);
        u.setName("태우");
        u.setCertAlertDays("30,14,7,0");
        store.setId(3);
        sw.setId(11);
        sw.setStore(store);
        sw.setWorker(u);
        sw.setStatus(StoreWorker.Status.ACTIVE);
        when(swRepo.findByStatusWithCertExpiryBetween(eq(StoreWorker.Status.ACTIVE), any(), any())).thenReturn(List.of(sw));
        when(swRepo.findByWorkerIdAndStatusIn(eq(5), any())).thenReturn(List.of(sw));
        when(userRepo.lockForCert(5)).thenReturn(Optional.of(u));
    }

    @Test
    void sendsOnChosenDayToWorkerAndOwnerOnce() {
        u.setCertExpiry(today.plusDays(7));
        job.run(today);
        job.run(today); // 같은 날 다시 돌아도 한 번만

        verify(notifier).sendToStaff(store, sw, Notification.Type.CERT, "보건증 만료 D-7", "만료일 2026.11.25", 11);
        verify(notifier).sendToOwner(store, Notification.Type.CERT, "태우님 보건증 만료 D-7", "만료일 2026.11.25", 11);
        verify(hub, times(1)).bumpAll(List.of(3));
        assertEquals(today, u.getCertAlertSentOn());

        // 다음 알림 시점(D-0)에는 다시 보낸다
        job.run(today.plusDays(7));
        verify(notifier).sendToStaff(store, sw, Notification.Type.CERT, "보건증이 오늘 만료돼요", "만료일 2026.11.25", 11);
    }

    @Test
    void skipsDaysNotChosen() {
        u.setCertExpiry(today.plusDays(8));
        job.run(today);
        verify(notifier, never()).sendToStaff(any(), any(), any(), anyString(), any(), any());
        verify(notifier, never()).sendToOwner(any(), any(), anyString(), any(), any());
        verifyNoInteractions(hub);
    }
}
