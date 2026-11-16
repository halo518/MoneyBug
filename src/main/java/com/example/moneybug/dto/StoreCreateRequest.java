package com.example.moneybug.dto;

import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/stores 요청 본문 (설계서 §4.2 매장 생성).
 * { "name": "카페 커푸치노", "address": "도로명 주소", "isSmall": true, "defaultDeduction": "biz33" }
 * 사장님은 JWT 의 로그인 사용자로 정해지고, 매장 코드와 기본 파트는 서버가 만든다.
 */
@Schema(description = "매장 생성 요청")
public record StoreCreateRequest(

        @Schema(description = "상호명", example = "카페 커푸치노")
        @NotBlank(message = "상호명과 주소를 입력해 주세요")
        @Size(max = 100)
        String name,

        @Schema(description = "도로명 주소", example = "서울특별시 강남구 테헤란로 1")
        @NotBlank(message = "상호명과 주소를 입력해 주세요")
        @Size(max = 255)
        String address,

        @Schema(description = "상시 5인 미만 사업장 여부. true 면 가산수당 미적용. 생략 시 true", example = "true")
        @JsonProperty("isSmall")
        Boolean isSmall,

        @Schema(description = "가입 승인 때 적용할 기본 공제. 생략 시 BUSINESS_TAX(원천징수 3.3%)",
                example = "BUSINESS_TAX")
        StoreWorker.TaxType defaultDeduction) {

    /** 연관 엔티티(고용주)는 서비스에서 조회해 넘겨준다. code 는 서비스가 생성한다. */
    public Store toEntity(User employer) {
        Store store = new Store();
        store.setEmployer(employer);
        store.setStoreName(name.trim());
        store.setAddress(address.trim());
        store.setUnderFive(isSmall == null || isSmall);
        // isSmall 을 직접 보냈으면 수동 모드, 생략하면 재직 인원으로 자동
        store.setSizeMode(isSmall == null ? Store.SizeMode.AUTO
                : isSmall ? Store.SizeMode.UNDER5 : Store.SizeMode.OVER5);
        store.setDefaultDeduction(defaultDeduction != null ? defaultDeduction : StoreWorker.TaxType.BUSINESS_TAX);
        return store;
    }
}
