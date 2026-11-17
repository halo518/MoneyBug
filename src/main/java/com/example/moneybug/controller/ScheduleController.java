package com.example.moneybug.controller;

import com.example.moneybug.dto.MonthScheduleCommandDto;
import com.example.moneybug.dto.MonthScheduleDto;
import com.example.moneybug.dto.ScheduleTemplateDto;
import com.example.moneybug.service.MonthScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 월 근무표·템플릿·자동 생성 (웹 사장님 화면). month = yyyy-MM. 권한: 사장님 */
@Tag(name = "월 근무표", description = "월 근무표 만들기·자동 채우기·통째 저장·확정·삭제, 템플릿, 자동 생성(미리보기→적용)")
@RestController
@RequestMapping("/api/v1/stores/{storeId}")
@RequiredArgsConstructor
public class ScheduleController {

    private final MonthScheduleService service;

    @Operation(summary = "월 근무표 만들기", description = "있으면 초안을 manual 로 갱신. prefillFixed 면 고정 근무를 채워요")
    @PostMapping("/schedules")
    public MonthScheduleDto.Response create(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                            @Valid @RequestBody MonthScheduleCommandDto.CreateRequest request) {
        return service.create(userId.intValue(), storeId, request);
    }

    @Operation(summary = "고정 근무 자동 채우기", description = "오늘 이후 날짜에 빠진 고정 근무만 추가해요")
    @PostMapping("/schedules/{month}/autofill")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void autofill(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                         @PathVariable String month) {
        service.autofill(userId.intValue(), storeId, month);
    }

    @Operation(summary = "월 근무 통째 저장", description = "그 달 근무를 목록대로 맞춰요(수정·추가·삭제). 출근 기록 있는 근무는 skipped. 겹침 400")
    @PutMapping("/schedules/{month}/shifts")
    public MonthScheduleCommandDto.SaveShiftsResponse saveShifts(
            @AuthenticationPrincipal Long userId, @PathVariable Integer storeId, @PathVariable String month,
            @Valid @RequestBody MonthScheduleCommandDto.SaveShiftsRequest request) {
        return service.saveShifts(userId.intValue(), storeId, month, request);
    }

    @Operation(summary = "월 근무표 확정", description = "시간 미정이 남아 있으면 409")
    @PostMapping("/schedules/{month}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                        @PathVariable String month) {
        service.confirm(userId.intValue(), storeId, month);
    }

    @Operation(summary = "월 근무표 삭제", description = "출근 기록 없는 그 달 근무와 메타를 지워요")
    @DeleteMapping("/schedules/{month}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                       @PathVariable String month) {
        service.delete(userId.intValue(), storeId, month);
    }

    @Operation(summary = "근무표 자동 생성 미리보기", description = "고정 근무 + 빈칸 충원을 계산만 해요(저장 없음). 30분 안에 apply")
    @PostMapping("/schedules/generate/preview")
    public MonthScheduleCommandDto.PreviewResponse preview(
            @AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
            @Valid @RequestBody MonthScheduleCommandDto.GenerateRequest request) {
        return service.preview(userId.intValue(), storeId, request);
    }

    @Operation(summary = "미리보기 적용", description = "오늘 이후 출근 기록 없는 근무를 바꿔 넣어요. 만료 410 PLAN_EXPIRED. AI 설명은 잠시 뒤 붙어요")
    @PostMapping("/schedules/generate/apply")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apply(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                      @Valid @RequestBody MonthScheduleCommandDto.ApplyRequest request) {
        service.applyPlan(userId.intValue(), storeId, request.planId());
    }

    @Operation(summary = "템플릿 저장", description = "그 달 첫 완전한 월~일 주를 요일 패턴으로 저장해요")
    @PostMapping("/schedule-templates")
    public ScheduleTemplateDto.Response saveTemplate(
            @AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
            @Valid @RequestBody MonthScheduleCommandDto.TemplateCreateRequest request) {
        return service.saveTemplate(userId.intValue(), storeId, request);
    }

    @Operation(summary = "템플릿 삭제")
    @DeleteMapping("/schedule-templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                               @PathVariable Integer templateId) {
        service.deleteTemplate(userId.intValue(), storeId, templateId);
    }

    @Operation(summary = "템플릿 적용", description = "요일별로 그 달을 채워요. 오늘 이후·출근 기록 없는 기존 근무는 교체")
    @PostMapping("/schedule-templates/{templateId}/apply")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void applyTemplate(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                              @PathVariable Integer templateId,
                              @Valid @RequestBody MonthScheduleCommandDto.TemplateApplyRequest request) {
        service.applyTemplate(userId.intValue(), storeId, templateId, request.month());
    }
}
