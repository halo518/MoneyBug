package com.example.moneybug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.dto.StoreCreateRequest;
import com.example.moneybug.dto.StoreDetailDto;
import com.example.moneybug.dto.StoreDto;
import com.example.moneybug.dto.StorePartDto;
import com.example.moneybug.service.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 매장 (설계서 §4.2). */
@Tag(name = "매장", description = "매장 생성·조회·설정·가입 신청 (설계서 4.2)")
@RestController
@RequestMapping("/api/v1/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;
    private final com.example.moneybug.service.StoreChangeHub changeHub;

    @Operation(summary = "매장 생성", description = "상호명·주소 필수. 매장 코드와 기본 파트(오픈/미들/마감)를 자동 생성해요. 권한: 로그인")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StoreDetailDto.Response create(@AuthenticationPrincipal Long userId,
                                          @Valid @RequestBody StoreCreateRequest request) {
        return storeService.create(userId.intValue(), request);
    }

    @Operation(summary = "매장 조회", description = "파트(parts)와 확정된 월별 인건비(monthlyTotals) 포함. 권한: 사장님·직원")
    @GetMapping("/{storeId}")
    public StoreDetailDto.Response get(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId) {
        return storeService.get(userId.intValue(), storeId);
    }

    @Operation(summary = "매장 정보 수정", description = "이름·주소·규모·기본공제 중 보낸 것만 반영해요. 권한: 사장님")
    @PatchMapping("/{storeId}")
    public StoreDetailDto.Response update(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                          @Valid @RequestBody StoreDto.UpdateRequest request) {
        return storeService.update(userId.intValue(), storeId, request);
    }

    @Operation(summary = "파트 목록 저장", description = "파트 전체를 통째로 맞춰요. id 있으면 수정(이름 변경은 근무·직원에 전파), 없으면 추가, 빠지면 삭제. 권한: 사장님")
    @PutMapping("/{storeId}/parts")
    public List<StorePartDto.Response> saveParts(@AuthenticationPrincipal Long userId,
                                                 @PathVariable Integer storeId,
                                                 @Valid @RequestBody StorePartDto.SaveRequest request) {
        return storeService.savePartList(userId.intValue(), storeId, request.parts());
    }

    @Operation(summary = "매장 설정 수정", description = "이름·영업시간·규모(sizeMode/isSmall)·공지 필독 기본값·대타 푸시·기본 공제. 보낸 것만 반영해요. 권한: 사장님")
    @PatchMapping("/{storeId}/settings")
    public StoreDto.Response updateSettings(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                            @Valid @RequestBody StoreDto.SettingsRequest request) {
        return storeService.updateSettings(userId.intValue(), storeId, request);
    }

    @Operation(summary = "파트별 필요 인원 수정", description = "파트(시작 시각순) 순서대로 needs 를 반영해요. 개수가 파트 수와 다르거나 0~99 밖이면 400. 권한: 사장님")
    @PutMapping("/{storeId}/parts/needs")
    public List<StorePartDto.Response> updatePartNeeds(@AuthenticationPrincipal Long userId,
                                                       @PathVariable Integer storeId,
                                                       @Valid @RequestBody StoreDetailDto.PartNeedsRequest request) {
        return storeService.updatePartNeeds(userId.intValue(), storeId, request.needs());
    }

    /** 결과별 상태 코드: REQUESTED 201 / 나머지(NOT_FOUND·ALREADY_MEMBER·ALREADY_PENDING) 200. FE 는 2xx 본문의 result 로 분기한다. */
    @Operation(summary = "매장 코드로 가입 신청", description = "코드는 대소문자·하이픈 무시. 결과(result): requested 201 / notFound·alreadyMember·alreadyPending 200. requested 일 때만 사장님에게 가입 알림이 가요.")
    @PostMapping("/join-requests")
    public ResponseEntity<StoreDetailDto.JoinResponse> requestJoin(@AuthenticationPrincipal Long userId,
                                                                   @Valid @RequestBody StoreDetailDto.JoinRequest request) {
        StoreDetailDto.JoinResponse response = storeService.requestJoin(userId.intValue(), request.code());
        if (response.result() != StoreDetailDto.JoinResult.REQUESTED) return ResponseEntity.ok(response);
        // 경로에 storeId 가 없어 인터셉터가 못 잡는다. 사장님 화면에 가입 신청이 바로 보이게.
        changeHub.bump(response.store().id());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "매장 코드 조회", description = "직원 초대용 코드. 권한: 사장님")
    @GetMapping("/{storeId}/code")
    public StoreDetailDto.CodeResponse code(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId) {
        return storeService.getCode(userId.intValue(), storeId);
    }
}
