package com.example.moneybug.dto;

import com.example.moneybug.entity.Attendance;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

/** 설계서 §3.6 AttendanceDto(계산값 포함) 와 §4.6 요청 본문. */
public class AttendanceViewDto {

    /** 서버 계산값. 저장하지 않는다. */
    public enum AttStatus { WAITING, WORKING, NORMAL, LATE, ABSENT }

    public record View(
            Integer id,
            Integer storeId,
            Integer shiftId,
            Integer staffId,
            LocalDateTime clockIn,
            LocalDateTime clockOut,
            String editReason,
            // computed
            AttStatus status,
            int lateMinutes,
            int workedMinutes,
            int overtimeMinutes,
            int dayPay) {

        /** 저장된 값 + 서비스가 계산한 값으로 View 를 만든다. */
        public static View of(Attendance a, AttStatus status, int lateMinutes, int workedMinutes,
                              int overtimeMinutes, int dayPay) {
            return new View(
                    a.getId(),
                    a.getSchedule().getStoreWorker().getStore().getId(),
                    a.getSchedule().getId(),
                    a.getSchedule().getStoreWorker().getId(),
                    a.getActualStart(),
                    a.getActualEnd(),
                    a.getEditReason(),
                    status, lateMinutes, workedMinutes, overtimeMinutes, dayPay);
        }
    }

    /** GET /attendance?date= 한 행: 근태 기록(없을 수 있음) + 근무 + 직원 요약. */
    public record DayItem(
            ScheduleDto.Response shift,
            StaffDto staff,
            View attendance,
            AttStatus status) {}

    /** POST .../clock-in — 본문은 선택. 매장별 회전 QR 토큰 (제안). */
    public record ClockInRequest(String qrToken) {}

    /** PATCH .../attendance/{id}, POST .../shifts/{id}/attendance — 사유 필수. */
    public record EditRequest(
            LocalDateTime clockIn,
            LocalDateTime clockOut,
            @NotBlank(message = "수정 사유를 입력해 주세요") String reason) {}
}
