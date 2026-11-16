package com.example.moneybug.dto;

import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.StoreWorker;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

public class ScheduleDto {

    /** 휴게 기본값: 근무 길이 480분 이상 60, 240분 이상 30, 그 외 0 (설계서 §4.5). */
    public static int defaultBreakMin(int startMin, int endMin) {
        int length = (endMin <= startMin ? endMin + 1440 : endMin) - startMin;
        if (length >= 480) return 60;
        if (length >= 240) return 30;
        return 0;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Request {
        @NotNull
        @JsonProperty("staffId")
        private Integer mappingId;

        @NotNull
        @JsonProperty("date")
        private LocalDate workDate;

        // 하루 기준 분 (09:30 -> 570). endMin <= startMin 이면 야간(다음 날까지)
        @NotNull
        @Min(0)
        @Max(1439)
        private Integer startMin;

        @NotNull
        @Min(0)
        @Max(1439)
        private Integer endMin;

        @Size(max = 50)
        private String part;

        // 생략 시 자동 계산
        @Min(0)
        private Integer breakMin;

        private Schedule.Kind kind = Schedule.Kind.WORK;

        private boolean timeTbd = false;

        @JsonProperty("isSubstitute")
        private boolean substitute = false;

        /** 연관 엔티티(매장-직원 매핑)는 서비스에서 조회해 넘겨준다. */
        public Schedule toEntity(StoreWorker storeWorker) {
            Schedule schedule = new Schedule();
            schedule.setStoreWorker(storeWorker);
            schedule.setWorkDate(workDate);
            schedule.setStartMin(startMin);
            schedule.setEndMin(endMin);
            schedule.setPart(part);
            schedule.setBreakMin(breakMin != null ? breakMin : defaultBreakMin(startMin, endMin));
            schedule.setKind(kind);
            schedule.setTimeTbd(timeTbd);
            schedule.setSubstitute(substitute);
            return schedule;
        }
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Integer id;
        private Integer storeId;
        // JSON 이름은 FE models.dart Shift 와 같게
        @JsonProperty("staffId")
        private Integer mappingId;
        @JsonProperty("date")
        private LocalDate workDate;
        private Integer startMin;
        private Integer endMin;
        private String part;
        private int breakMin;
        private Schedule.Kind kind;
        private boolean timeTbd;
        @JsonProperty("isSubstitute")
        private boolean substitute;
        @JsonProperty("originalStaffId")
        private Integer originalMappingId;
        // computed (JSON 이름은 설계서와 같은 isWork)
        @JsonProperty("isWork")
        private boolean isWork;
        private int scheduledMinutes;

        public static Response from(Schedule schedule) {
            return Response.builder()
                    .id(schedule.getId())
                    .storeId(schedule.getStoreWorker().getStore().getId())
                    .mappingId(schedule.getStoreWorker().getId())
                    .workDate(schedule.getWorkDate())
                    .startMin(schedule.getStartMin())
                    .endMin(schedule.getEndMin())
                    .part(schedule.getPart() == null ? "" : schedule.getPart()) // FE 는 null 을 못 받음
                    .breakMin(schedule.getBreakMin())
                    .kind(schedule.getKind())
                    .timeTbd(schedule.isTimeTbd())
                    .substitute(schedule.isSubstitute())
                    .originalMappingId(schedule.getOriginalStoreWorker() == null ? null
                            : schedule.getOriginalStoreWorker().getId())
                    .isWork(schedule.isWork())
                    .scheduledMinutes(schedule.scheduledMinutes())
                    .build();
        }
    }
}
