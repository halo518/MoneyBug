package com.example.moneybug.service;

import com.example.moneybug.dto.MonthScheduleCommandDto;
import com.example.moneybug.dto.ScheduleTemplateDto;
import com.example.moneybug.entity.MonthSchedule;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.ScheduleTemplate;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** 템플릿 저장·적용. DB 없이. */
class MonthScheduleTemplateTest {

    StoreAccess access = mock(StoreAccess.class);
    ScheduleRepository scheduleRepo = mock(ScheduleRepository.class);
    StoreWorkerRepository workerRepo = mock(StoreWorkerRepository.class);
    AttendanceRepository attendanceRepo = mock(AttendanceRepository.class);
    MonthScheduleRepository monthRepo = mock(MonthScheduleRepository.class);
    ScheduleTemplateRepository templateRepo = mock(ScheduleTemplateRepository.class);
    ScheduleDeleter deleter = mock(ScheduleDeleter.class);
    MonthScheduleService service = new MonthScheduleService(access, monthRepo,
            templateRepo, scheduleRepo, attendanceRepo, workerRepo,
            mock(StorePartRepository.class), mock(OverlapChecker.class), deleter, mock(ShiftService.class),
            mock(ScheduleAiService.class), mock(StaffWageService.class), mock(StoreChangeHub.class),
            mock(NotificationPublisher.class), mock(PlatformTransactionManager.class));

    static final String NEXT = YearMonth.from(TimeUtil.today()).plusMonths(1).toString();

    StoreWorker worker(int id) {
        StoreWorker sw = new StoreWorker();
        sw.setId(id);
        return sw;
    }

    static Schedule shift(int id, StoreWorker sw, LocalDate d, Schedule.Kind kind) {
        Schedule s = new Schedule();
        s.setId(id);
        s.setStoreWorker(sw);
        s.setWorkDate(d);
        s.setStartMin(540);
        s.setEndMin(1080);
        s.setKind(kind);
        return s;
    }

    void template(List<ScheduleTemplateDto.Shift> pattern) {
        ScheduleTemplate t = new ScheduleTemplate();
        t.setId(9);
        t.setName("T");
        t.setPatternJson(ScheduleTemplateDto.toJson(pattern));
        when(templateRepo.findByIdAndStoreId(9, 1)).thenReturn(Optional.of(t));
    }

    /** 이전 템플릿이 넣은 휴무·연차도 지우고 새 템플릿 근무를 넣는다. 잠금은 권한 확인보다 먼저. */
    @Test
    void replacesLeaveAndOffToo() {
        StoreWorker sw = worker(24);
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(sw));
        template(List.of(new ScheduleTemplateDto.Shift(3, 24, 720, 1020, "미들", 30, Schedule.Kind.WORK, false)));
        LocalDate thu = YearMonth.parse(NEXT).atDay(1).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.THURSDAY));
        Schedule off = shift(1, sw, thu, Schedule.Kind.OFF);
        Schedule leave = shift(2, sw, thu.plusDays(1), Schedule.Kind.LEAVE);
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of(off, leave));
        when(attendanceRepo.findByScheduleIdIn(anyCollection())).thenReturn(List.of());

        service.applyTemplate(7, 1, 9, NEXT);

        InOrder order = inOrder(monthRepo, access);
        order.verify(monthRepo).lockStore(1);
        order.verify(access).requireOwner(7, 1);
        verify(deleter).delete(off);
        verify(deleter).delete(leave);
        verify(scheduleRepo, atLeast(4)).save(argThat(s -> s.getWorkDate().getDayOfWeek() == java.time.DayOfWeek.THURSDAY
                && s.getKind() == Schedule.Kind.WORK));
        verify(monthRepo).save(argThat(m -> m.getSource() == MonthSchedule.Source.TEMPLATE));
    }

    /** 넣을 근무가 없으면(빈 템플릿·퇴사자만) 그 달을 비우지 않는다. */
    @Test
    void emptyTemplateDoesNotWipeMonth() {
        when(workerRepo.findByStoreIdAndStatus(1, StoreWorker.Status.ACTIVE)).thenReturn(List.of(worker(24)));
        template(List.of(new ScheduleTemplateDto.Shift(0, 99, 540, 1080, "오픈", 30, Schedule.Kind.WORK, false)));

        assertThatThrownBy(() -> service.applyTemplate(7, 1, 9, NEXT))
                .isInstanceOf(ApiException.class).hasMessageContaining("템플릿 근무가 없어요");
        verify(deleter, never()).delete(any());
        verify(monthRepo, never()).save(any());
    }

    @Test
    void pastMonthIsRejectedWithoutMeta() {
        String past = YearMonth.from(TimeUtil.today()).minusMonths(1).toString();
        template(List.of(new ScheduleTemplateDto.Shift(0, 24, 540, 1080, "오픈", 30, Schedule.Kind.WORK, false)));

        assertThatThrownBy(() -> service.applyTemplate(7, 1, 9, past))
                .isInstanceOf(ApiException.class).hasMessageContaining("지난 달");
        verify(monthRepo, never()).save(any());
    }

    @Test
    void savingEmptyWeekIsRejected() {
        when(scheduleRepo.findByStoreBetween(any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.saveTemplate(7, 1, new MonthScheduleCommandDto.TemplateCreateRequest("빈달", NEXT)))
                .isInstanceOf(ApiException.class).hasMessageContaining("근무가 없어");
        verify(templateRepo, never()).save(any());
    }

    @Test
    void nameOver100CharsFailsValidation() {
        Validator v = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(v.validate(new MonthScheduleCommandDto.TemplateCreateRequest("가".repeat(100), NEXT))).isEmpty();
        assertThat(v.validate(new MonthScheduleCommandDto.TemplateCreateRequest("가".repeat(101), NEXT)))
                .anySatisfy(c -> assertThat(c.getPropertyPath().toString()).isEqualTo("name"));
    }
}
