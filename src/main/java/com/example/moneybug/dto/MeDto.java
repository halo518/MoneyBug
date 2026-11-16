package com.example.moneybug.dto;

import java.util.List;

public class MeDto {

    /** GET /me (설계서 §4.1 MeDto). 사장님 N매장을 허용하므로 ownedStoreIds 를 함께 준다. */
    public record Response(
            UserDto.Response person,
            /** 사장님이면 첫 매장 id, 아니면 null (설계서 ownedStoreId) */
            Integer ownedStoreId,
            List<Integer> ownedStoreIds,
            /** 알바생 소속 매장들 (status = ACTIVE | PENDING) */
            List<StaffDto> memberships) {}

    /** GET /me/today */
    public record Today(
            ScheduleDto.Response todayShift,
            boolean working,
            AttendanceViewDto.View openAttendance) {}
}
