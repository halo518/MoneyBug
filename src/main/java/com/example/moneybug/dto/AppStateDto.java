package com.example.moneybug.dto;

import com.example.moneybug.entity.User;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/v1/me/state — FE(Flutter) lib/data/models.dart 의 AppState.toJson() 과 같은 모양.
 * 이 응답 문자열을 그대로 AppStore.load(json) 에 넣으면 앱이 뜬다.
 */
public class AppStateDto {

    /** FE AppState.version 과 같아야 한다. 다르면 FE 가 버리고 시드로 시작한다. */
    public static final int FE_STATE_VERSION = 3;

    public record Response(
            int v,
            int nextId,
            Session session,
            List<Person> persons,
            List<StoreDetailDto.Response> stores,
            List<StaffDto> staff,
            List<ScheduleDto.Response> shifts,
            List<AttendanceViewDto.View> attendance,
            List<SubstituteRequestDto.Response> subs,
            List<NoticeViewDto.View> notices,
            List<NotificationDto.Response> notifications) {}

    /** FE Session. employeeId/ownerId 는 사람(User) id. 매장이 없으면 빈 문자열(FE 가 null 을 못 받음). */
    public record Session(
            boolean loggedIn,
            String role,
            String employeeId,
            String ownerId,
            String employeeStoreId,
            String ownerStoreId) {}

    /** FE Person. 다른 사람 연락처·이메일은 내보내지 않는다. */
    public record Person(
            Integer id,
            String name,
            String fullName,
            LocalDate certExpiry,
            List<Integer> certAlertDays,
            boolean certHasPhoto) {

        public static Person from(User u) {
            UserDto.Response r = UserDto.Response.from(u);
            // FE 는 name·fullName 을 null 로 받지 못한다.
            String name = r.getName() == null ? "" : r.getName();
            String fullName = r.getFullName() == null || r.getFullName().isBlank() ? name : r.getFullName();
            return new Person(r.getId(), name, fullName, r.getCertExpiry(), r.getCertAlertDays(), r.isCertHasPhoto());
        }
    }
}
