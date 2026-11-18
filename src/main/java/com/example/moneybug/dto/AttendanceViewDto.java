package com.example.moneybug.dto;

import com.example.moneybug.entity.Attendance;
import com.example.moneybug.service.TimeUtil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;

/** 설계서 §3.6 AttendanceDto(계산값 포함) 와 §4.6 요청 본문. */
public class AttendanceViewDto {

    /** 서버 계산값. 저장하지 않는다. */
    public enum AttStatus {
        WAITING, WORKING, NORMAL, LATE, ABSENT;

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() { return com.example.moneybug.entity.EnumJson.camel(this); }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static AttStatus fromJson(String v) { return com.example.moneybug.entity.EnumJson.parse(AttStatus.class, v); }
    }

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

    /** POST .../clock-in — qrToken 필수(서비스가 검증). 매장별 회전 QR 토큰. */
    public record ClockInRequest(String qrToken) {}

    /** GET .../qr-token 응답. */
    public record QrToken(String token, java.time.Instant expiresAt, int periodSeconds) {}

    /** PATCH .../attendance/{id}, POST .../shifts/{id}/attendance — 사유 필수. */
    public record EditRequest(
            @JsonDeserialize(using = KstDateTime.class) LocalDateTime clockIn,
            @JsonDeserialize(using = KstDateTime.class) LocalDateTime clockOut,
            @NotBlank(message = "수정 사유를 입력해 주세요")
            @Size(max = 500, message = "수정 사유는 500자까지 입력할 수 있어요") String reason) {}

    /**
     * 근태 시각 읽기 규칙: 오프셋 없는 시각은 KST 로 보고, `Z`·`+09:00` 같은 오프셋이 있으면 KST 로 바꾼다.
     * (기본 LocalDateTime 은 `Z` 를 버리고 `+09:00` 은 거절해서 9시간 어긋났다.)
     */
    public static class KstDateTime extends ValueDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
            String text = p.getValueAsString();
            if (text == null) {
                return (LocalDateTime) ctxt.handleUnexpectedToken(LocalDateTime.class, p);
            }
            return parse(text.trim(), ctxt);
        }

        static LocalDateTime parse(String text, DeserializationContext ctxt) {
            try {
                TemporalAccessor t = DateTimeFormatter.ISO_DATE_TIME.parseBest(text,
                        ZonedDateTime::from, LocalDateTime::from);
                return t instanceof ZonedDateTime z
                        ? z.withZoneSameInstant(TimeUtil.KST).toLocalDateTime()
                        : (LocalDateTime) t;
            } catch (DateTimeParseException e) {
                throw ctxt.weirdStringException(text, LocalDateTime.class, "시각 형식이 올바르지 않아요");
            }
        }
    }
}
