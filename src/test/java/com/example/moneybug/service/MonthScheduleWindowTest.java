package com.example.moneybug.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class MonthScheduleWindowTest {

    @Test
    void 석_달_뒤까지만_만들_수_있다() {
        YearMonth now = YearMonth.from(TimeUtil.today());
        assertThat(MonthScheduleService.writableMonth(now.toString())).isEqualTo(now);
        assertThat(MonthScheduleService.writableMonth(now.plusMonths(1).toString())).isEqualTo(now.plusMonths(1));
        assertThat(MonthScheduleService.writableMonth(now.plusMonths(3).toString())).isEqualTo(now.plusMonths(3));
        assertThatThrownBy(() -> MonthScheduleService.writableMonth(now.plusMonths(4).toString()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("3개월 뒤까지만");
    }
}
