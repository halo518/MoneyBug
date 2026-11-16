package com.example.moneybug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.dto.CommonDto;
import com.example.moneybug.dto.StaffCommandDto;
import com.example.moneybug.dto.StaffDto;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.service.StaffService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

/** 직원(Staff) (설계서 §4.3). staffId = StoreWorker.id */
@Tag(name = "직원", description = "매장 직원 조회·가입 승인/거절·수정·내보내기 (설계서 4.3). staffId 는 StoreWorker id")
@RestController
@RequestMapping("/api/v1/stores/{storeId}/staff")
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;

    @Operation(summary = "직원 목록", description = "status 생략 시 사장님은 재직+대기, 직원은 재직만. 권한: 사장님·직원")
    @GetMapping
    public CommonDto.ListResponse<StaffDto> list(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                                 @RequestParam(required = false) StoreWorker.Status status) {
        return staffService.list(userId.intValue(), storeId, status);
    }

    @Operation(summary = "직원 상세", description = "person·계약서 포함. 권한: 사장님, 본인")
    @GetMapping("/{staffId}")
    public StaffDto get(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                        @PathVariable Integer staffId) {
        return staffService.get(userId.intValue(), storeId, staffId);
    }

    @Operation(summary = "가입 승인", description = "시급은 최저임금(10,320원) 이상. 대기(PENDING) 상태만 가능. 신청자에게 알림이 가요. 권한: 사장님")
    @PostMapping("/{staffId}/approve")
    public StaffDto approve(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                            @PathVariable Integer staffId,
                            @Valid @RequestBody StaffCommandDto.ApproveRequest request) {
        return staffService.approve(userId.intValue(), storeId, staffId, request);
    }

    @Operation(summary = "가입 거절", description = "대기(PENDING) 상태만 가능. 신청자에게 알림이 가요. 권한: 사장님")
    @PostMapping("/{staffId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                       @PathVariable Integer staffId) {
        staffService.reject(userId.intValue(), storeId, staffId);
    }

    @Operation(summary = "직원 정보 수정", description = "시급·공제·주휴·가산·파트·구분 중 보낸 것만 반영(시급은 최저임금 검증). 권한: 사장님")
    @PatchMapping("/{staffId}")
    public StaffDto update(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                           @PathVariable Integer staffId,
                           @Valid @RequestBody StaffCommandDto.UpdateRequest request) {
        return staffService.update(userId.intValue(), storeId, staffId, request);
    }

    @Operation(summary = "직원 내보내기(퇴사 처리)", description = "오늘 이후 출근 기록 없는 근무 삭제, 진행 중 대타 정리, 본인에게 알림. 권한: 사장님")
    @DeleteMapping("/{staffId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                       @PathVariable Integer staffId) {
        staffService.remove(userId.intValue(), storeId, staffId);
    }

    @Operation(summary = "보건증 갱신 요청", description = "직원에게 보건증 알림(미등록 / D-n / 지남)을 보내요. 권한: 사장님")
    @PostMapping("/{staffId}/cert-renewal-requests")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestCertRenewal(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                   @PathVariable Integer staffId) {
        staffService.requestCertRenewal(userId.intValue(), storeId, staffId);
    }
}
