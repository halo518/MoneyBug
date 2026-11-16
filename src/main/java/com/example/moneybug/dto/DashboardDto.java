package com.example.moneybug.dto;

import java.util.List;

/** GET /stores/{storeId}/dashboard (설계서 §4.11, 제안). */
public class DashboardDto {

    public record TodayShift(
            ScheduleDto.Response shift,
            StaffDto staff,
            AttendanceViewDto.View attendance,
            AttendanceViewDto.AttStatus status) {}

    /** 보건증 만료 임박·미등록 직원. daysLeft 가 null 이면 미등록. */
    public record CertAlert(Integer staffId, Long daysLeft) {}

    public record Response(
            List<TodayShift> todayShifts,
            List<SubstituteRequestDto.Response> pendingSubstitutes,
            int pendingJoinRequests,
            List<CertAlert> certAlerts,
            /** 서명이 끝나지 않은 직원(StoreWorker id) */
            List<Integer> unsignedContracts,
            long unreadNotificationCount) {}
}
