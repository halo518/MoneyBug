package com.example.moneybug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.dto.DashboardDto;
import com.example.moneybug.service.DashboardService;
import com.example.moneybug.service.TimeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 대시보드 집계 (설계서 §4.11, 제안). date 생략 시 서버의 오늘. */
@Tag(name = "대시보드", description = "사장님 대시보드 집계 (설계서 4.11)")
@RestController
@RequestMapping("/api/v1/stores/{storeId}/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "대시보드 집계", description = "오늘 근무·진행 중 대타·가입 신청 수·보건증 경고·서명 대기 계약서·미읽음 알림. date 생략 시 오늘. 권한: 사장님")
    @GetMapping
    public DashboardDto.Response dashboard(
            @AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dashboardService.dashboard(userId.intValue(), storeId, date != null ? date : TimeUtil.today());
    }
}
