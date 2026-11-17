package com.example.moneybug.service;

import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaffRemoveTest {

    @Test
    void removeSetsLeftAtToday() {
        StoreAccess access = mock(StoreAccess.class);
        ScheduleRepository schedules = mock(ScheduleRepository.class);
        SubstituteRequestRepository subs = mock(SubstituteRequestRepository.class);
        Store store = new Store();
        store.setId(1);
        User u = new User();
        u.setId(9);
        StoreWorker sw = new StoreWorker();
        sw.setId(3);
        sw.setWorker(u);
        sw.setStatus(StoreWorker.Status.ACTIVE);
        when(access.requireOwner(1, 1)).thenReturn(store);
        when(access.staff(1, 3)).thenReturn(sw);
        when(subs.findByStoreAndStatusIn(any(), any())).thenReturn(List.of());
        when(schedules.findFromDateByWorker(any(), any())).thenReturn(List.of());
        StaffService svc = new StaffService(mock(StoreWorkerRepository.class), schedules,
                mock(AttendanceRepository.class), subs, mock(SubstituteApplicantRepository.class),
                mock(StorePartRepository.class), mock(StoreService.class), access, mock(StaffAssembler.class),
                mock(ScheduleDeleter.class), mock(NotificationPublisher.class), mock(StaffWageService.class));

        svc.remove(1, 1, 3);

        assertEquals(StoreWorker.Status.LEFT, sw.getStatus());
        assertEquals(TimeUtil.today(), sw.getLeftAt());
    }
}
