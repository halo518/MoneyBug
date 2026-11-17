package com.example.moneybug.service;

import com.example.moneybug.dto.AttendanceViewDto;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.repository.AttendanceRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttendanceServiceRulesTest {

    private final AttendanceRepository repo = mock(AttendanceRepository.class);
    private final StoreAccess access = mock(StoreAccess.class);
    private final AttendanceService svc = new AttendanceService(repo, null, null, access, null, null, null);

    private static AttendanceViewDto.EditRequest edit(LocalDateTime in, LocalDateTime out) {
        return new AttendanceViewDto.EditRequest(in, out, "사유");
    }

    private String codeOf(Runnable r) {
        return assertThrows(ApiException.class, r::run).getCode();
    }

    @Test
    void clockOutTooLateAfterShiftEndRejected() {
        Schedule s = new Schedule();
        s.setWorkDate(TimeUtil.now().toLocalDate().minusDays(2));
        s.setStartMin(9 * 60);
        s.setEndMin(18 * 60);
        Attendance a = new Attendance();
        a.setSchedule(s);
        a.setActualStart(s.getWorkDate().atTime(9, 0));
        when(repo.findOpenByUserAndStore(1, 1)).thenReturn(java.util.List.of(a));
        assertEquals("CLOCK_OUT_EXPIRED", codeOf(() -> svc.clockOut(1, 1)));
    }

    @Test
    void editRejectsFutureAndOver24h() {
        when(access.requireOwner(any(), any())).thenReturn(new Store());
        Attendance a = new Attendance();
        LocalDateTime base = TimeUtil.now().minusDays(3);
        a.setActualStart(base);
        when(repo.findByIdAndStore(5, 1)).thenReturn(Optional.of(a));
        assertEquals("FUTURE_TIME",
                codeOf(() -> svc.edit(1, 1, 5, edit(TimeUtil.now().minusHours(2), TimeUtil.now().plusHours(3)))));
        assertEquals("INVALID_CLOCK_RANGE",
                codeOf(() -> svc.edit(1, 1, 5, edit(base, base.plusHours(25)))));
    }
}
