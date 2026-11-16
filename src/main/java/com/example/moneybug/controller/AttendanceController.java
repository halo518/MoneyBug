package com.example.moneybug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.dto.AttendanceViewDto;
import com.example.moneybug.dto.CommonDto;
import com.example.moneybug.dto.MeDto;
import com.example.moneybug.service.AttendanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 출퇴근 · 근태 (설계서 §4.6). */
@Tag(name = "출퇴근·근태", description = "출근·퇴근 기록과 근태 수정 (설계서 4.6). 시각은 서버 시각 기준")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;

    /** 본문(qrToken) 필수 — 서비스가 검증한다(없으면 400 INVALID_QR). */
    @Operation(summary = "출근", description = "근무 시작 30분 전부터 종료 전까지. 이미 기록이 있으면 기존 기록 반환(멱등). 권한: 본인(직원)")
    @PostMapping("/stores/{storeId}/shifts/{shiftId}/clock-in")
    public AttendanceViewDto.View clockIn(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                          @PathVariable Integer shiftId,
                                          @RequestBody(required = false) AttendanceViewDto.ClockInRequest request) {
        return attendanceService.clockIn(userId.intValue(), storeId, shiftId, request);
    }

    @Operation(summary = "출근 QR 토큰", description = "30초마다 바뀌는 매장 QR 토큰. 권한: 사장님")
    @GetMapping("/stores/{storeId}/qr-token")
    public AttendanceViewDto.QrToken qrToken(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId) {
        return attendanceService.qrToken(userId.intValue(), storeId);
    }

    @Operation(summary = "퇴근", description = "내 열린 출근 기록에 퇴근을 기록해요. 없으면 404. 권한: 본인(직원)")
    @PostMapping("/stores/{storeId}/attendance/clock-out")
    public AttendanceViewDto.View clockOut(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId) {
        return attendanceService.clockOut(userId.intValue(), storeId);
    }

    @Operation(summary = "일별 근태 목록", description = "그날 실제 근무마다 근태·상태(대기/근무중/정상/지각/결근) 포함. 권한: 사장님")
    @GetMapping("/stores/{storeId}/attendance")
    public CommonDto.ListResponse<AttendanceViewDto.DayItem> listByDate(
            @AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendanceService.listByDate(userId.intValue(), storeId, date);
    }

    @Operation(summary = "근태 상세", description = "권한: 사장님, 본인")
    @GetMapping("/stores/{storeId}/attendance/{id}")
    public AttendanceViewDto.View get(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                      @PathVariable Integer id) {
        return attendanceService.get(userId.intValue(), storeId, id);
    }

    @Operation(summary = "근태 수정", description = "사유 필수. 직원에게 알림이 가요. 권한: 사장님")
    @PatchMapping("/stores/{storeId}/attendance/{id}")
    public AttendanceViewDto.View edit(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                       @PathVariable Integer id,
                                       @Valid @RequestBody AttendanceViewDto.EditRequest request) {
        return attendanceService.edit(userId.intValue(), storeId, id, request);
    }

    /** 사장님이 미출근 근무에 출근 기록을 만든다. */
    @Operation(summary = "근태 수동 생성(미출근 처리)", description = "사장님이 출근 기록을 직접 만들어요. 알림은 보내지 않아요. 권한: 사장님")
    @PostMapping("/stores/{storeId}/shifts/{shiftId}/attendance")
    @ResponseStatus(HttpStatus.CREATED)
    public AttendanceViewDto.View createManual(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                               @PathVariable Integer shiftId,
                                               @Valid @RequestBody AttendanceViewDto.EditRequest request) {
        return attendanceService.createManual(userId.intValue(), storeId, shiftId, request);
    }

    @Operation(summary = "내 오늘 근무", description = "오늘 내 근무, 근무 중 여부, 열린 출근 기록. 권한: 직원")
    @GetMapping("/me/today")
    public MeDto.Today today(@AuthenticationPrincipal Long userId) {
        return attendanceService.today(userId.intValue());
    }
}
