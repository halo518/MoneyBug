package com.example.moneybug.service;

import com.example.moneybug.dto.AttendanceViewDto;
import com.example.moneybug.dto.AttendanceViewDto.AttStatus;
import com.example.moneybug.entity.Attendance;
import com.example.moneybug.entity.Schedule;

import java.time.Duration;
import java.time.LocalDateTime;

/** 근태 파생값 계산 (설계서 §3.6, §4.6, §6). 저장하지 않고 항상 서버가 계산한다. */
public final class AttendanceCalc {

    private AttendanceCalc() {}

    /** 지각 분 = max(0, clockIn - 근무시작) */
    public static int lateMinutes(Schedule s, Attendance a) {
        if (a == null || a.getActualStart() == null) return 0;
        return (int) Math.max(0, Duration.between(TimeUtil.start(s), a.getActualStart()).toMinutes());
    }

    /**
     * 급여 기준 실근무 분 = max(출근, 소정시작)부터 퇴근(또는 지금)까지, 퇴근 완료 시에만 휴게 차감.
     * 일찍 찍어도 소정 시작부터 계산한다.
     */
    public static int workedMinutes(Schedule s, Attendance a, LocalDateTime now) {
        if (a == null || a.getActualStart() == null) return 0;
        LocalDateTime start = TimeUtil.start(s);
        LocalDateTime from = a.getActualStart().isAfter(start) ? a.getActualStart() : start;
        LocalDateTime to = a.getActualEnd() != null ? a.getActualEnd() : now;
        long minutes = Duration.between(from, to).toMinutes();
        if (a.getActualEnd() != null) {
            minutes -= s.getBreakMin();
        }
        return (int) Math.max(0, minutes);
    }

    /** 연장 분 = 소정 종료 이후 퇴근까지의 분 (퇴근 전이면 0). */
    public static int overtimeMinutes(Schedule s, Attendance a) {
        if (a == null || a.getActualEnd() == null) return 0;
        return (int) Math.max(0, Duration.between(TimeUtil.end(s), a.getActualEnd()).toMinutes());
    }

    /**
     * 상태 판정:
     * 기록 없음 -> 종료 지났으면 ABSENT, 아니면 WAITING / 출근했고 퇴근 전 -> WORKING /
     * 퇴근 완료 -> 지각 > 0 이면 LATE, 아니면 NORMAL
     */
    public static AttStatus status(Schedule s, Attendance a, LocalDateTime now) {
        if (a == null || a.getActualStart() == null) {
            return now.isAfter(TimeUtil.end(s)) ? AttStatus.ABSENT : AttStatus.WAITING;
        }
        if (a.getActualEnd() == null) {
            return AttStatus.WORKING;
        }
        return lateMinutes(s, a) > 0 ? AttStatus.LATE : AttStatus.NORMAL;
    }

    public static AttendanceViewDto.View view(Attendance a, LocalDateTime now) {
        Schedule s = a.getSchedule();
        int worked = workedMinutes(s, a, now);
        int overtime = a.getOvertimeMinutes() != null ? a.getOvertimeMinutes() : 0;
        int dayPay = TimeUtil.pay(worked, s.getStoreWorker().getHourlyWage());
        return AttendanceViewDto.View.of(a, status(s, a, now), lateMinutes(s, a), worked, overtime, dayPay);
    }
}
