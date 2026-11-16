package com.example.moneybug.dto;

import com.example.moneybug.entity.MonthSchedule;

import java.time.LocalDateTime;

/** FE MonthSchedule. id·storeId 는 FeIdJsonModule 이 문자열로 내보낸다. */
public class MonthScheduleDto {

    public record Response(
            Integer id,
            Integer storeId,
            String month,
            MonthSchedule.Status status,
            MonthSchedule.Source source,
            String aiComment,
            MonthSchedule.HolidayPolicy weeklyHolidayPay,
            MonthSchedule.Priority priority,
            LocalDateTime updatedAt) {

        public static Response from(MonthSchedule m) {
            return new Response(m.getId(), m.getStore().getId(), m.getMonth(), m.getStatus(), m.getSource(),
                    m.getAiComment() == null ? "" : m.getAiComment(), m.getWeeklyHolidayPay(), m.getPriority(),
                    m.getUpdatedAt());
        }
    }
}
