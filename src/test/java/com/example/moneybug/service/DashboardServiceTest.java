package com.example.moneybug.service;

import com.example.moneybug.dto.DashboardDto;
import com.example.moneybug.dto.SubstituteRequestDto;
import com.example.moneybug.entity.Contract;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.AttendanceRepository;
import com.example.moneybug.repository.ContractRepository;
import com.example.moneybug.repository.NotificationRepository;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.SubstituteRequestRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    private final ScheduleRepository schedules = mock(ScheduleRepository.class);
    private final SubstituteRequestRepository subs = mock(SubstituteRequestRepository.class);
    private final StoreWorkerRepository workers = mock(StoreWorkerRepository.class);
    private final ContractRepository contracts = mock(ContractRepository.class);
    private final StoreAccess access = mock(StoreAccess.class);
    private final SubstituteAssembler subAssembler = mock(SubstituteAssembler.class);
    private final DashboardService svc = new DashboardService(schedules, mock(AttendanceRepository.class), subs,
            workers, contracts, mock(NotificationRepository.class), access, mock(StaffAssembler.class), subAssembler);

    private static StoreWorker worker(int id, LocalDate certExpiry, String alertDays) {
        User u = new User();
        u.setCertExpiry(certExpiry);
        u.setCertAlertDays(alertDays);
        StoreWorker sw = new StoreWorker();
        sw.setId(id);
        sw.setWorker(u);
        return sw;
    }

    @Test
    void certAlertsFollowFeRuleAndOrderUnsignedAsStrings() {
        LocalDate d = LocalDate.of(2026, 11, 17);
        Store store = new Store();
        store.setId(1);
        when(access.requireOwner(1, 1)).thenReturn(store);
        when(subs.findByStoreAndStatusIn(any(), any())).thenReturn(List.of());
        when(workers.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(
                worker(76, d.plusDays(30), "7"),   // D-30, 개인 알림 7일이어도 포함
                worker(77, d.plusDays(14), "7"),
                worker(78, d.plusDays(3), "0"),
                worker(79, d.plusDays(31), "60"),  // D-31 은 제외
                worker(80, null, null)));
        Contract unsigned = mock(Contract.class);
        when(contracts.findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(any())).thenReturn(Optional.empty());
        when(contracts.findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(76)).thenReturn(Optional.of(unsigned));

        DashboardDto.Response r = svc.dashboard(1, 1, d);

        assertEquals(List.of(80, 78, 77, 76), r.certAlerts().stream().map(DashboardDto.CertAlert::staffId).toList());
        assertEquals(Arrays.asList(null, 3L, 14L, 30L), r.certAlerts().stream().map(DashboardDto.CertAlert::daysLeft).toList());
        assertEquals(List.of("76"), r.unsignedContracts());
    }

    @Test
    void pendingSubstitutesSkipStartedShifts() {
        Store store = new Store();
        store.setId(1);
        when(access.requireOwner(1, 1)).thenReturn(store);
        when(workers.findByStoreIdAndStatus(any(), any())).thenReturn(List.of());
        LocalDateTime now = TimeUtil.now();
        SubstituteRequest past = request(now.minusHours(1));
        SubstituteRequest future = request(now.plusDays(1));
        SubstituteRequestDto.Response dto = mock(SubstituteRequestDto.Response.class);
        when(subAssembler.toDto(future)).thenReturn(dto);
        when(subs.findByStoreAndStatusIn(any(), any())).thenReturn(List.of(past, future));

        DashboardDto.Response r = svc.dashboard(1, 1, now.toLocalDate());

        assertEquals(List.of(dto), r.pendingSubstitutes());
    }

    private static SubstituteRequest request(LocalDateTime start) {
        Schedule s = new Schedule();
        s.setWorkDate(start.toLocalDate());
        s.setStartMin(start.getHour() * 60 + start.getMinute());
        s.setEndMin((s.getStartMin() + 60) % 1440);
        SubstituteRequest r = new SubstituteRequest();
        r.setSchedule(s);
        return r;
    }
}
