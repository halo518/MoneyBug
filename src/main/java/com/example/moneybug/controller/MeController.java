package com.example.moneybug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.dto.AppStateDto;
import com.example.moneybug.dto.MeDto;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.service.MeService;
import com.example.moneybug.service.StoreChangeHub;
import com.example.moneybug.service.StateService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final StateService stateService;
    private final StoreChangeHub changeHub;
    private final StoreRepository storeRepository;
    private final StoreWorkerRepository storeWorkerRepository;

    @Operation(summary = "변경 신호 기다리기 (롱 폴링)", description = "내 매장(소유·재직·가입 대기) 데이터가 since 이후 바뀌면 바로, "
            + "아니면 최대 25초 뒤 응답해요. {seq, changed}. changed 면 /me/state 를 다시 읽으세요. since 를 빼면 기준 seq 만 줘요.")
    @GetMapping("/changes")
    public DeferredResult<Map<String, Object>> changes(@AuthenticationPrincipal Long userId,
                                                       @RequestParam(defaultValue = "-1") long since) {
        Integer uid = userId.intValue();
        Set<Integer> storeIds = new HashSet<>();
        storeRepository.findByEmployerId(uid).forEach(s -> storeIds.add(s.getId()));
        storeWorkerRepository.findByWorkerIdAndStatusIn(uid,
                        List.of(StoreWorker.Status.ACTIVE, StoreWorker.Status.PENDING))
                .forEach(m -> storeIds.add(m.getStore().getId()));
        return changeHub.await(storeIds, since);
    }

    @Operation(summary = "내 정보 조회", description = "person + 소유 매장(ownedStoreIds) + 소속 매장(memberships, ACTIVE/PENDING). 가입 승인 대기 화면은 이 응답의 status 를 폴링해요.")
    @GetMapping
    public MeDto.Response me(@AuthenticationPrincipal Long userId) {
        return meService.getMe(userId.intValue());
    }

    @Operation(summary = "앱 상태 한 덩어리 (FE AppState)",
            description = "FE models.dart 의 AppState.toJson() 과 같은 모양. 응답 그대로 AppStore.load(json) 에 넣을 수 있어요. "
                    + "storeId 를 주면 그 매장을 지금 보는 매장으로(소유면 사장님, 소속이면 알바생 역할) 정해요.")
    @GetMapping("/state")
    public AppStateDto.Response state(@AuthenticationPrincipal Long userId,
                                      @RequestParam(required = false) Integer storeId) {
        return stateService.getState(userId.intValue(), storeId);
    }
}
