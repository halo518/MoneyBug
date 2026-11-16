package com.example.moneybug.service;

import com.example.moneybug.dto.MonthScheduleCommandDto;
import com.example.moneybug.dto.MonthScheduleCommandDto.ShiftItem;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** 통째 저장: 기존 근무 수정은 그 행에 반영, 빠진 근무는 삭제. DB 없이. */
class MonthScheduleSaveShiftsTest {

    ScheduleRepository scheduleRepo = mock(ScheduleRepository.class);
    StoreWorkerRepository workerRepo = mock(StoreWorkerRepository.class);
    AttendanceRepository attendanceRepo = mock(AttendanceRepository.class);
    MonthScheduleRepository monthRepo = mock(MonthScheduleRepository.class);
    ScheduleDeleter deleter = mock(ScheduleDeleter.class);
    MonthScheduleService service = new MonthScheduleService(mock(StoreAccess.class), monthRepo,
            mock(ScheduleTemplateRepository.class), scheduleRepo, attendanceRepo, workerRepo,
            mock(StorePartRepository.class), mock(OverlapChecker.class), deleter, mock(ShiftService.class),
            mock(ScheduleAiService.class), mock(ScheduleJobStore.class), mock(StoreChangeHub.class),
            mock(PlatformTransactionManager.class));

    static Schedule shift(int id, StoreWorker sw, LocalDate d, int start, int end) {
        Schedule s = new Schedule();
        s.setId(id);
        s.setStoreWorker(sw);
        s.setWorkDate(d);
        s.setStartMin(start);
        s.setEndMin(end);
        s.setPart("오픈");
        return s;
    }

    static ShiftItem item(String id, int staffId, LocalDate d, int start, int end) {
        return new ShiftItem(id, staffId, d, start, end, "오픈", 30, Schedule.Kind.WORK, false);
    }

    @Test
    void editsInPlaceAndDeletesMissing() {
        StoreWorker sw = new StoreWorker();
        sw.setId(45);
        LocalDate d3 = LocalDate.of(2026, 12, 3), d5 = LocalDate.of(2026, 12, 5);
        Schedule keep = shift(123, sw, d3, 540, 1080);
        Schedule gone = shift(124, sw, d5, 540, 1080);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(keep, gone));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        service.saveShifts(7, 1, "2026-12", new MonthScheduleCommandDto.SaveShiftsRequest(List.of(
                item("123", 45, d3, 600, 1140),
                item(null, 45, LocalDate.of(2026, 12, 4), 540, 1080))));

        assertThat(keep.getStartMin()).isEqualTo(600);
        assertThat(keep.getEndMin()).isEqualTo(1140);
        verify(deleter).delete(gone);
        verify(deleter, never()).delete(keep);
        verify(scheduleRepo).saveAll(argThat(l -> ((List<?>) l).size() == 1));
    }

    /** 퇴사자(LEFT)의 지난 근무도 그 달 목록에 그대로 실려 온다. 이것 때문에 통째 저장 전체가 400 이 되면 안 된다. */
    @Test
    void keepsShiftsOfStaffWhoLeft() {
        StoreWorker sw = new StoreWorker();
        sw.setId(45);
        StoreWorker left = new StoreWorker();
        left.setId(46);
        left.setStatus(StoreWorker.Status.LEFT);
        LocalDate d3 = LocalDate.of(2026, 11, 3);
        Schedule mine = shift(123, sw, d3, 540, 1080);
        Schedule old = shift(200, left, LocalDate.of(2026, 11, 2), 540, 1080);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(old, mine));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        service.saveShifts(7, 1, "2026-11", new MonthScheduleCommandDto.SaveShiftsRequest(List.of(
                item("200", 46, LocalDate.of(2026, 11, 2), 540, 1080),
                item("123", 45, d3, 600, 1140))));

        assertThat(mine.getStartMin()).isEqualTo(600);
        verify(deleter, never()).delete(any());
    }

    /** 퇴사자에게 새로 근무를 넣는 것은 여전히 막는다. */
    @Test
    void rejectsNewShiftForStaffWhoLeft() {
        StoreWorker left = new StoreWorker();
        left.setId(46);
        Schedule old = shift(200, left, LocalDate.of(2026, 11, 2), 540, 1080);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of());
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(old));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveShifts(7, 1, "2026-11",
                new MonthScheduleCommandDto.SaveShiftsRequest(List.of(
                        item(null, 46, LocalDate.of(2026, 11, 5), 540, 1080)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("재직");
    }
}
