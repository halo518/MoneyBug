package com.example.moneybug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.dto.CommonDto;
import com.example.moneybug.dto.SchedulePlanDto;
import com.example.moneybug.dto.ScheduleDto;
import com.example.moneybug.dto.ShiftCommandDto;
import com.example.moneybug.service.ShiftService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** 근무표 (설계서 §4.5). shiftId = Schedule.id, staffId = StoreWorker.id */
@Tag(name = "근무표", description = "근무 조회·생성·수정·삭제, 자동 배정, 근무표 만들기, 확정 (설계서 4.5). staffId 는 StoreWorker id, 시각은 하루 기준 분")
@RestController
@RequestMapping("/api/v1/stores/{storeId}")
@RequiredArgsConstructor
public class ShiftController {

    private final ShiftService shiftService;

    @Operation(summary = "근무 목록", description = "시작 시각순. workOnly=true 면 실제 근무만(출퇴근·급여·대타 화면용). from/to 생략 시 이번 달. 권한: 사장님·직원")
    @GetMapping("/shifts")
    public CommonDto.ListResponse<ScheduleDto.Response> list(
            @AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer staffId,
            @RequestParam(defaultValue = "false") boolean workOnly) {
        return shiftService.list(userId.intValue(), storeId, from, to, staffId, workOnly);
    }

    @Operation(summary = "근무 단건 조회", description = "권한: 사장님·직원")
    @GetMapping("/shifts/{shiftId}")
    public ScheduleDto.Response get(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                    @PathVariable Integer shiftId) {
        return shiftService.get(userId.intValue(), storeId, shiftId);
    }

    @Operation(summary = "근무 생성", description = "staffIds 여러 명, repeatWeeks 반복 지원. 같은 직원의 실제 근무와 겹치면 409. 첫 주 근무는 직원에게 알림. 권한: 사장님")
    @PostMapping("/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    public ShiftCommandDto.CreateResponse create(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                                 @Valid @RequestBody ShiftCommandDto.CreateRequest request) {
        return shiftService.create(userId.intValue(), storeId, request);
    }

    @Operation(summary = "근무 수정", description = "보낸 것만 반영. 지난 날짜로 이동 불가, 겹침 409. 담당자/시간 변경에 따라 알림. 권한: 사장님")
    @PatchMapping("/shifts/{shiftId}")
    public ScheduleDto.Response update(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                       @PathVariable Integer shiftId,
                                       @Valid @RequestBody ShiftCommandDto.UpdateRequest request) {
        return shiftService.update(userId.intValue(), storeId, shiftId, request);
    }

    @Operation(summary = "근무 삭제", description = "출근 기록이 있으면 409. 없는 근무는 성공(멱등). 진행 중 대타 요청은 취소돼요. 권한: 사장님")
    @DeleteMapping("/shifts/{shiftId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                       @PathVariable Integer shiftId) {
        shiftService.delete(userId.intValue(), storeId, shiftId);
    }

    @Operation(summary = "근무 자동 채우기", description = "그 주(월요일 기준) 오늘 이후 날짜의 파트별 부족 인원을 근무 적은 사람부터 채워요. 권한: 사장님")
    @PostMapping("/schedule/auto-fill")
    public ShiftCommandDto.AddedResponse autoFill(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                                  @Valid @RequestBody ShiftCommandDto.AutoFillRequest request) {
        return shiftService.autoFill(userId.intValue(), storeId, request);
    }

    @Operation(summary = "근무표 만들기 미리보기", description = "저장하지 않고 배정 결과(planId)를 미리 보여줘요. 30분 뒤 만료. 권한: 사장님")
    @PostMapping("/schedule-plans")
    public SchedulePlanDto.Response planSchedule(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                                 @Valid @RequestBody SchedulePlanDto.Request request) {
        return shiftService.planSchedule(userId.intValue(), storeId, request);
    }

    @Operation(summary = "근무표 만들기 적용", description = "미리보기 근무를 저장하고 필요 인원을 갱신해요. 직원별 1건 알림. 권한: 사장님")
    @PostMapping("/schedule-plans/{planId}/apply")
    public ShiftCommandDto.AddedResponse applyPlan(@AuthenticationPrincipal Long userId,
                                                   @PathVariable Integer storeId, @PathVariable String planId,
                                                   @Valid @RequestBody SchedulePlanDto.ApplyRequest request) {
        return shiftService.applyPlan(userId.intValue(), storeId, planId, request);
    }

    @Operation(summary = "근무표 템플릿 적용", description = "필요 인원 저장 + (선택) 오늘이 속한 주로 지난주 근무 복사. 권한: 사장님")
    @PostMapping("/schedule/template")
    public ShiftCommandDto.CopiedResponse applyTemplate(@AuthenticationPrincipal Long userId,
                                                        @PathVariable Integer storeId,
                                                        @Valid @RequestBody ShiftCommandDto.TemplateRequest request) {
        return shiftService.applyTemplate(userId.intValue(), storeId, request);
    }

    @Operation(summary = "근무표 확정", description = "그 달 월 근무표를 확정해요(/schedules/{month}/confirm 과 같음). 이미 확정이면 아무것도 안 해요. 시간 미정 근무가 남아 있으면 409. 재직 직원 전원에게 알림. 권한: 사장님")
    @PostMapping("/schedule/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                        @Valid @RequestBody ShiftCommandDto.ConfirmRequest request) {
        shiftService.confirm(userId.intValue(), storeId, request);
    }

    @Operation(summary = "시간 미정 근무 수", description = "근무표 확정 전 확인용. 권한: 사장님")
    @GetMapping("/schedule/tbd-count")
    public ShiftCommandDto.TbdCountResponse tbdCount(@AuthenticationPrincipal Long userId,
                                                     @PathVariable Integer storeId,
                                                     @RequestParam int year, @RequestParam int month) {
        return shiftService.tbdCount(userId.intValue(), storeId, year, month);
    }
}
