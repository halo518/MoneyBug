package com.example.moneybug.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * /me/state 가 근무(shifts)를 내려주는 기간: 지난달 1일이 든 주의 월요일 ~ 3개월 뒤 말일.
 * FE 는 이 기간의 근무만 가지고 있으므로, 통째 저장은 이 밖의 근무를 건드리면 안 된다.
 */
public final class StateWindow {

    private StateWindow() {}

    public static LocalDate shiftsFrom(LocalDate today) {
        return today.minusMonths(1).withDayOfMonth(1).with(DayOfWeek.MONDAY);
    }

    public static LocalDate shiftsTo(LocalDate today) {
        return YearMonth.from(today).plusMonths(3).atEndOfMonth();
    }

    public static boolean contains(LocalDate today, LocalDate date) {
        return !date.isBefore(shiftsFrom(today)) && !date.isAfter(shiftsTo(today));
    }
}
