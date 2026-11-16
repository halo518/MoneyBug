package com.example.moneybug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.dto.CommonDto;
import com.example.moneybug.dto.SubstituteCommandDto;
import com.example.moneybug.dto.SubstituteRequestDto;
import com.example.moneybug.service.SubstituteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 대타 (설계서 §4.7). */
@Tag(name = "대타", description = "대타 신청·지원·승인 흐름 (설계서 4.7). 상태: OPEN → ACCEPTED → APPROVED / REJECTED / CANCELLED")
@RestController
@RequestMapping("/api/v1/stores/{storeId}")
@RequiredArgsConstructor
public class SubstituteController {

    private final SubstituteService substituteService;

    @Operation(summary = "대타 신청", description = "본인 근무(실제 근무)만, 사유 필수. 사장님과 동료에게 알림. 권한: 근무 당사자")
    @PostMapping("/shifts/{shiftId}/substitute-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public SubstituteRequestDto.Response create(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                                @PathVariable Integer shiftId,
                                                @Valid @RequestBody SubstituteRequestDto.Request request) {
        return substituteService.create(userId.intValue(), storeId, shiftId, request);
    }

    /** status=pending(open|accepted), mine=true, available=true */
    @Operation(summary = "대타 목록", description = "최신순. status=pending(OPEN·ACCEPTED), mine=내 요청, available=내가 지원 가능한 것. 권한: 사장님·직원")
    @GetMapping("/substitute-requests")
    public CommonDto.ListResponse<SubstituteRequestDto.Response> list(
            @AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "false") boolean available) {
        return substituteService.list(userId.intValue(), storeId, status, mine, available);
    }

    @Operation(summary = "대타 지원", description = "첫 지원자가 후보가 돼요. 겹치는 근무·이미 시작한 근무는 409. 권한: 직원")
    @PostMapping("/substitute-requests/{id}/apply")
    public SubstituteRequestDto.Response apply(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                               @PathVariable Integer id) {
        return substituteService.apply(userId.intValue(), storeId, id);
    }

    @Operation(summary = "대타 요청 취소", description = "지원자 전원과 사장님에게 알림. 권한: 요청자")
    @PostMapping("/substitute-requests/{id}/cancel")
    public SubstituteRequestDto.Response cancel(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                                @PathVariable Integer id) {
        return substituteService.cancel(userId.intValue(), storeId, id);
    }

    @Operation(summary = "대타 승인", description = "후보로 근무 담당자를 교체해요(대타 표시). 후보가 없으면 400. 권한: 사장님")
    @PostMapping("/substitute-requests/{id}/approve")
    public SubstituteRequestDto.Response approve(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                                 @PathVariable Integer id) {
        return substituteService.approve(userId.intValue(), storeId, id);
    }

    @Operation(summary = "대타 거절", description = "요청자(와 후보)에게 알림. 권한: 사장님")
    @PostMapping("/substitute-requests/{id}/reject")
    public SubstituteRequestDto.Response reject(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                                @PathVariable Integer id) {
        return substituteService.reject(userId.intValue(), storeId, id);
    }

    @Operation(summary = "대타 재알림", description = "동료에게 다시 알려요(매장의 대타 푸시가 켜져 있을 때만). 권한: 요청자, 사장님")
    @PostMapping("/substitute-requests/{id}/resend")
    public SubstituteRequestDto.Response resend(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                                @PathVariable Integer id) {
        return substituteService.resend(userId.intValue(), storeId, id);
    }

    @Operation(summary = "대타 직접 배정", description = "사장님이 직원을 지정하면 곧바로 승인 처리돼요. 권한: 사장님")
    @PostMapping("/substitute-requests/{id}/assign")
    public SubstituteRequestDto.Response assign(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                                @PathVariable Integer id,
                                                @Valid @RequestBody SubstituteCommandDto.AssignRequest request) {
        return substituteService.assign(userId.intValue(), storeId, id, request);
    }
}
