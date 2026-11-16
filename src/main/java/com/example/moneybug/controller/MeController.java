package com.example.moneybug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.dto.MeDto;
import com.example.moneybug.service.MeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 정보 (설계서 §4.1).
 * 로그인/토큰 갱신/로그아웃(/auth/kakao, /auth/refresh, /auth/logout)은 이미 AuthController(/api/auth/**)에 있다.
 */
@Tag(name = "내 정보", description = "로그인한 사용자 정보 (설계서 4.1)")
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final MeService meService;

    @Operation(summary = "내 정보 조회", description = "person + 소유 매장(ownedStoreIds) + 소속 매장(memberships, ACTIVE/PENDING). 가입 승인 대기 화면은 이 응답의 status 를 폴링해요.")
    @GetMapping
    public MeDto.Response me(@AuthenticationPrincipal Long userId) {
        return meService.getMe(userId.intValue());
    }
}
