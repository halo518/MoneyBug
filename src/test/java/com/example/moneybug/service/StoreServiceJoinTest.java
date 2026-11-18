package com.example.moneybug.service;

import com.example.moneybug.dto.StoreDetailDto;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 가입 신청: 매장 행 잠금이 첫 DB 접근이어야 동시 신청이 PENDING 행을 하나만 만든다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StoreServiceJoinTest {

    @Mock StoreRepository storeRepository;
    @Mock StorePartRepository storePartRepository;
    @Mock StoreWorkerRepository storeWorkerRepository;
    @Mock UserRepository userRepository;
    @Mock PayrollRepository payrollRepository;
    @Mock ScheduleRepository scheduleRepository;
    @Mock ScheduleTemplateRepository scheduleTemplateRepository;
    @Mock StoreAccess access;
    @Mock NotificationPublisher notifier;
    @InjectMocks StoreService service;

    Store store = new Store();
    User user = new User();

    @BeforeEach
    void setUp() {
        store.setId(4);
        store.setStoreName("카페");
        store.setCode("KA6U-SJUD");
        user.setId(7);
        user.setName("알바");
        when(storeRepository.findByCodeForUpdate("KA6U-SJUD")).thenReturn(Optional.of(store));
        when(userRepository.findById(7)).thenReturn(Optional.of(user));
        when(storePartRepository.findByStoreIdOrderByStartMinAsc(4)).thenReturn(List.of());
    }

    @Test
    void locksStoreByCodeBeforeAnyRead() {
        var res = service.requestJoin(7, " ka6u - sjud ");
        assertEquals(StoreDetailDto.JoinResult.REQUESTED, res.result());
        InOrder order = inOrder(storeRepository, storeWorkerRepository);
        order.verify(storeRepository).findByCodeForUpdate("KA6U-SJUD");
        order.verify(storeWorkerRepository, atLeastOnce())
                .existsByStoreIdAndWorkerIdAndStatus(anyInt(), anyInt(), any());
        verify(storeWorkerRepository).save(any(StoreWorker.class));
        verify(notifier).sendToOwner(eq(store), eq(Notification.Type.JOIN), anyString(), anyString(), any());
    }

    @Test
    void pendingAndUnknownCodesCreateNothing() {
        when(storeWorkerRepository.existsByStoreIdAndWorkerIdAndStatus(4, 7, StoreWorker.Status.PENDING)).thenReturn(true);
        assertEquals(StoreDetailDto.JoinResult.ALREADY_PENDING, service.requestJoin(7, "KA6U-SJUD").result());
        assertEquals(StoreDetailDto.JoinResult.NOT_FOUND, service.requestJoin(7, "ZZZZ-ZZZZ").result());
        assertEquals(StoreDetailDto.JoinResult.NOT_FOUND, service.requestJoin(7, "KA6U").result());
        verify(storeWorkerRepository, never()).save(any());
        verifyNoInteractions(notifier);
    }
}
