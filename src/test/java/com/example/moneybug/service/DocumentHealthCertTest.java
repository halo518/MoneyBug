package com.example.moneybug.service;

import com.example.moneybug.dto.HealthCertDto;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentHealthCertTest {

    @Mock UserRepository userRepository;
    @Mock StoreWorkerRepository storeWorkerRepository;
    @Mock StoreAccess access;
    @Mock NotificationPublisher notifier;
    @Mock FileStorage fileStorage;
    @Mock StoreChangeHub changeHub;
    @InjectMocks DocumentService service;

    private final User user = new User();

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        user.setId(7);
        user.setName("태우");
        user.setCertPhotoUrl("health-cert/7/old.png");
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    private HealthCertDto.Request req(Integer... days) {
        HealthCertDto.Request r = new HealthCertDto.Request();
        r.setExpiry(TimeUtil.today().plusDays(30));
        r.setAlertDays(Arrays.asList(days));
        r.setPhoto(new MockMultipartFile("photo", "a.png", "image/png", new byte[]{1}));
        return r;
    }

    private void complete(int status) {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(status);
        }
    }

    @Test
    void badAlertDaysAndExpiryRejectedBeforeAnyFileIsSaved() {
        when(userRepository.lockForCert(7)).thenReturn(Optional.of(user));
        for (HealthCertDto.Request r : List.of(req(30, null, 7), req(366), req(-1),
                req(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))) {
            ApiException e = assertThrows(ApiException.class, () -> service.uploadHealthCert(7, r));
            assertEquals("INVALID_ALERT_DAYS", e.getCode());
        }
        HealthCertDto.Request far = req(30);
        far.setExpiry(TimeUtil.today().plusYears(2).plusDays(1));
        assertEquals("EXPIRY_TOO_FAR", assertThrows(ApiException.class,
                () -> service.uploadHealthCert(7, far)).getCode());
        verify(fileStorage, never()).saveImage(anyString(), any());
    }

    @Test
    void tenDistinctAlertDaysFitTheColumn() {
        List<Integer> days = DocumentService.validAlertDays(List.of(365, 364, 363, 362, 361, 360, 359, 358, 357, 356, 356));
        assertEquals(10, days.size());
        assertTrue(String.join(",", days.stream().map(String::valueOf).toList()).length() <= 50);
    }

    @Test
    void commitDeletesOldPhotoAndBumpsActiveStores() {
        when(userRepository.lockForCert(7)).thenReturn(Optional.of(user));
        when(fileStorage.saveImage(eq("health-cert/7"), any())).thenReturn("health-cert/7/new.png");
        Store store = new Store();
        store.setId(3);
        StoreWorker sw = new StoreWorker();
        sw.setId(11);
        sw.setStore(store);
        when(storeWorkerRepository.findByWorkerIdAndStatusIn(eq(7), any())).thenReturn(List.of(sw));

        service.uploadHealthCert(7, req(30, 7, 7, 0));
        assertEquals("30,7,0", user.getCertAlertDays());
        verifyNoInteractions(changeHub); // 커밋 전에는 신호 없음
        complete(TransactionSynchronization.STATUS_COMMITTED);

        verify(fileStorage).delete("health-cert/7/old.png");
        verify(fileStorage, never()).delete("health-cert/7/new.png");
        verify(changeHub).bumpAll(List.of(3));
    }

    @Test
    void rollbackDeletesNewFileAndKeepsOld() {
        when(userRepository.lockForCert(7)).thenReturn(Optional.of(user));
        when(fileStorage.saveImage(eq("health-cert/7"), any())).thenReturn("health-cert/7/new.png");
        when(storeWorkerRepository.findByWorkerIdAndStatusIn(eq(7), any())).thenReturn(List.of());

        service.uploadHealthCert(7, req(30));
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(fileStorage).delete("health-cert/7/new.png");
        verify(fileStorage, never()).delete("health-cert/7/old.png");
        verifyNoInteractions(changeHub);
    }

    @Test
    void ownerCannotSeeCertOfNonActiveStaff() {
        StoreWorker sw = new StoreWorker();
        sw.setWorker(user);
        when(access.staff(3, 11)).thenReturn(sw);
        for (StoreWorker.Status st : StoreWorker.Status.values()) {
            sw.setStatus(st);
            if (st == StoreWorker.Status.ACTIVE) {
                assertEquals("health-cert/7/old.png", service.staffCertPhotoPath(1, 3, 11));
            } else {
                ApiException e = assertThrows(ApiException.class, () -> service.staffCertPhotoPath(1, 3, 11));
                assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
            }
        }
    }
}
