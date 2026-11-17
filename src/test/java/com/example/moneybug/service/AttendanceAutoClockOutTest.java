package com.example.moneybug.service;

import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.AttendanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AttendanceAutoClockOutTest {

    private final AttendanceRepository repo = mock(AttendanceRepository.class);
    private final NotificationPublisher notifier = mock(NotificationPublisher.class);
    private final StoreChangeHub hub = mock(StoreChangeHub.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final AttendanceAutoClockOut job = new AttendanceAutoClockOut(repo, notifier, hub, tm);

    private final LocalDateTime end = LocalDate.of(2026, 5, 14).atTime(18, 0);
    private final Attendance a = open();

    AttendanceAutoClockOutTest() {
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repo.findAllOpen()).thenReturn(List.of(a));
        when(repo.findById(7)).thenReturn(Optional.of(a));
    }

    private Attendance open() {
        Store store = new Store();
        store.setId(1);
        User u = new User();
        u.setName("태우");
        StoreWorker sw = new StoreWorker();
        sw.setStore(store);
        sw.setWorker(u);
        Schedule s = new Schedule();
        s.setStoreWorker(sw);
        s.setWorkDate(end.toLocalDate());
        s.setStartMin(9 * 60);
        s.setEndMin(18 * 60);
        Attendance at = new Attendance();
        at.setId(7);
        at.setSchedule(s);
        at.setActualStart(end.minusHours(9));
        return at;
    }

    @Test
    void notifiesOnceAfterOneHour() {
        job.run(end.plusMinutes(30));
        verifyNoInteractions(notifier);

        job.run(end.plusHours(1));
        job.run(end.plusHours(2));
        verify(notifier, times(1)).sendToOwner(any(), eq(Notification.Type.SUB), eq("퇴근 기록이 없어요"), any(), eq(7));
        assertNotNull(a.getMissingOutNotifiedAt());
        assertNull(a.getActualEnd());
    }

    @Test
    void autoClocksOutAfterFourHours() {
        job.run(end.plusHours(4));
        assertEquals(end, a.getActualEnd());
        assertEquals(AttendanceAutoClockOut.AUTO_REASON, a.getEditReason());
        verify(notifier).sendToOwner(any(), any(), eq("퇴근이 자동 처리됐어요"), any(), eq(7));
        verify(notifier).sendToStaff(any(), any(), any(), eq("퇴근이 자동 처리됐어요"), any(), eq(7));
        verify(hub).bump(1);
    }

    @Test
    void oneFailureDoesNotBlockOthers() {
        when(repo.findById(7)).thenThrow(new RuntimeException("boom"));
        assertDoesNotThrow(() -> job.run(end.plusHours(5)));
    }
}
