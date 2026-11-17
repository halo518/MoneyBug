package com.example.moneybug.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class MonthScheduleWindowTest {

    @Test
    void 다음_달까지만_만들_수_있다() {
        YearMonth now = YearMonth.from(TimeUtil.today());
        assertThat(MonthScheduleService.writableMonth(now.toString())).isEqualTo(now);
        assertThat(MonthScheduleService.writableMonth(now.plusMonths(1).toString())).isEqualTo(now.plusMonths(1));
        assertThatThrownBy(() -> MonthScheduleService.writableMonth(now.plusMonths(2).toString()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("다음 달까지만");
    }
}
