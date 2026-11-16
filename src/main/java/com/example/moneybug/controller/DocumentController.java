package com.example.moneybug.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.moneybug.dto.ContractDto;
import com.example.moneybug.dto.HealthCertDto;
import com.example.moneybug.dto.UserDto;
import com.example.moneybug.service.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서류: 보건증 · 근로계약서 (설계서 §4.4). */
@Tag(name = "서류", description = "보건증·근로계약서 (설계서 4.4)")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /** multipart/form-data: expiry, alertDays, photo */
    @Operation(summary = "보건증 등록/갱신", description = "multipart/form-data: expiry, alertDays(예: 30,14,7,0), photo(이미지) 모두 필수. 재직 중인 모든 매장의 사장님에게 알림이 가요. 권한: 본인")
    @PutMapping(value = "/me/health-cert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserDto.Response uploadHealthCert(@AuthenticationPrincipal Long userId,
                                             @Valid @ModelAttribute HealthCertDto.Request request) {
        return documentService.uploadHealthCert(userId.intValue(), request);
    }

    @Operation(summary = "근로계약서 작성·발송", description = "사장님이 작성+서명해 보내요. 시급 최저임금 이상, 종료일 > 시작일. 직원에게 서명 요청 알림. 이력으로 쌓여요. 권한: 사장님")
    @PutMapping("/stores/{storeId}/staff/{staffId}/contract")
    public ContractDto.Response upsertContract(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                               @PathVariable Integer staffId,
                                               @Valid @RequestBody ContractDto.Request request) {
        return documentService.upsertContract(userId.intValue(), storeId, staffId, request);
    }

    @Operation(summary = "근로계약서 서명", description = "직원 서명. 계약서가 없으면 404, 이미 서명했으면 409. 사장님에게 알림. 권한: 본인(직원)")
    @PostMapping("/stores/{storeId}/staff/{staffId}/contract/sign")
    public ContractDto.Response signContract(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                             @PathVariable Integer staffId,
                                             @Valid @RequestBody ContractDto.SignRequest request,
                                             HttpServletRequest http) {
        return documentService.signContract(userId.intValue(), storeId, staffId, request, http.getRemoteAddr());
    }
}
