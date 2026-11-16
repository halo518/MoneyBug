package com.example.moneybug.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** 템플릿 패턴 추출·적용 날짜 계산(DB 없는 순수 로직). 레퍼런스 mock.ts toPattern / fillMonth. */
public final class TemplatePattern {

    private TemplatePattern() {}

    /** 그 달 첫 완전한 월~일 주(7일). 없으면 빈 목록. */
    public static List<LocalDate> firstFullWeek(YearMonth ym) {
        for (LocalDate d = ym.atDay(1); !d.plusDays(6).isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() == 1) {
                List<LocalDate> week = new ArrayList<>();
                for (int i = 0; i < 7; i++) week.add(d.plusDays(i));
                return week;
            }
        }
        return List.of();
    }

    /** dayIndex(0=월). */
    public static int dayIndex(LocalDate d) {
        return d.getDayOfWeek().getValue() - 1;
    }

    /**
     * 패턴의 dayIndex 마다 적용할 날짜들. 근무·휴무(off)는 대상 기간의 같은 요일마다,
     * 연차(oneTimeOnly)는 그 요일의 첫 날에만 둔다.
     */
    public static List<LocalDate> datesFor(int dayIndex, boolean oneTimeOnly, List<LocalDate> targetDates) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d : targetDates) {
            if (dayIndex(d) != dayIndex) continue;
            out.add(d);
            if (oneTimeOnly) break;
        }
        return out;
    }
}
