package com.example.moneybug.service;

import com.example.moneybug.entity.Schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * 시간 유틸. 설계서 §10-6: 모든 시각 판정(출근 가능 시간, 지각, 지난 날짜)은 서버 시각(매장 현지 = KST)으로 한다.
 */
public final class TimeUtil {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private TimeUtil() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(KST);
    }

    public static LocalDate today() {
        return LocalDate.now(KST);
    }

    /** 하루 기준 분 -> "HH:mm" (1440 이상이면 다음 날 시각으로 환산). */
    public static String hhmm(int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    /** 알림 본문용 라벨. 예: "5/20 14:00–20:00" */
    public static String label(LocalDate date, int startMin, int endMin) {
        return date.getMonthValue() + "/" + date.getDayOfMonth() + " " + hhmm(startMin) + "–" + hhmm(endMin);
    }

    public static String label(Schedule s) {
        return label(s.getWorkDate(), s.getStartMin(), s.getEndMin());
    }

    /** 소정 시작 시각. */
    public static LocalDateTime start(Schedule s) {
        return s.getWorkDate().atStartOfDay().plusMinutes(s.getStartMin());
    }

    /** 소정 종료 시각 (야간이면 다음 날). */
    public static LocalDateTime end(Schedule s) {
        return s.getWorkDate().atStartOfDay().plusMinutes(s.endAbs());
    }

    private static long absStart(Schedule s) {
        return s.getWorkDate().toEpochDay() * 1440L + s.getStartMin();
    }

    private static long absEnd(Schedule s) {
        return s.getWorkDate().toEpochDay() * 1440L + s.endAbs();
    }

    /** 두 근무의 시간 구간이 겹치는지 (야간 근무 포함). */
    public static boolean overlaps(Schedule a, Schedule b) {
        return absStart(a) < absEnd(b) && absStart(b) < absEnd(a);
    }

    public static LocalDate monday(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** round(분 x 시급 / 60). int 범위로 포화한다(비현실적 시급에서 오버플로로 부호가 뒤집히지 않게). */
    public static int pay(long minutes, int hourlyWage) {
        long v = Math.round(minutes * (double) hourlyWage / 60.0);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }
}
