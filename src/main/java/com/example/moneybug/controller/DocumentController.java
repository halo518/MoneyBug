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
import com.example.moneybug.service.FileStorage;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;
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
    private final FileStorage fileStorage;

    private ResponseEntity<byte[]> image(String path) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(FileStorage.contentType(path)))
                .cacheControl(CacheControl.noStore()).body(fileStorage.read(path));
    }

    @Operation(summary = "내 보건증 사진", description = "이미지 바이트. 없으면 404. 권한: 본인")
    @GetMapping("/me/health-cert/photo")
    public ResponseEntity<byte[]> myCertPhoto(@AuthenticationPrincipal Long userId) {
        return image(documentService.myCertPhotoPath(userId.intValue()));
    }

    @Operation(summary = "직원 보건증 사진", description = "이미지 바이트. 권한: 그 매장 사장님")
    @GetMapping("/stores/{storeId}/staff/{staffId}/health-cert/photo")
    public ResponseEntity<byte[]> staffCertPhoto(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                                 @PathVariable Integer staffId) {
        return image(documentService.staffCertPhotoPath(userId.intValue(), storeId, staffId));
    }

    @Operation(summary = "프로필 사진", description = "이미지 바이트. 사진이 없으면 404. "
            + "권한: 본인, 그 사람이 속한 매장의 사장님, 그 사람이 사장인 매장의 재직·대기 직원, 같은 매장 재직 동료")
    @GetMapping("/people/{personId}/photo")
    public ResponseEntity<byte[]> personPhoto(@AuthenticationPrincipal Long userId, @PathVariable Integer personId) {
        return image(documentService.personPhotoPath(userId.intValue(), personId));
    }

    @Operation(summary = "내 프로필 사진 올리기", description = "multipart: photo(이미지). 예전 사진은 지워요. 권한: 본인")
    @PutMapping(value = "/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserDto.Response uploadPhoto(@AuthenticationPrincipal Long userId,
                                        @RequestParam(value = "photo", required = false) MultipartFile photo) {
        return documentService.uploadPhoto(userId.intValue(), photo);
    }

    @Operation(summary = "내 프로필 사진 지우기", description = "없어도 200. 권한: 본인")
    @DeleteMapping("/me/photo")
    public UserDto.Response deletePhoto(@AuthenticationPrincipal Long userId) {
        return documentService.deletePhoto(userId.intValue());
    }

    @Operation(summary = "내 프로필 수정", description = "{fullName?, contact?} 보낸 키만 반영. fullName 1~50자, "
            + "contact 숫자·- 20자 이하(빈 문자열이면 지움). 권한: 본인")
    @PatchMapping("/me/profile")
    public UserDto.Response updateProfile(@AuthenticationPrincipal Long userId,
                                          @RequestBody UserDto.ProfileRequest request) {
        return documentService.updateProfile(userId.intValue(), request);
    }

    @Operation(summary = "서명 이미지 올리기", description = "multipart: image(PNG). {signatureUrl} 를 계약 서명/작성 API 에 넘겨요. 권한: 로그인")
    @PostMapping(value = "/me/signatures", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> uploadSignature(@AuthenticationPrincipal Long userId,
                                               @RequestParam("image") MultipartFile image) {
        return Map.of("signatureUrl", documentService.saveSignature(userId.intValue(), image));
    }

    @Operation(summary = "계약서 서명 이미지", description = "who = owner | employee. 권한: 그 매장 사장님·계약 직원")
    @GetMapping("/stores/{storeId}/staff/{staffId}/contract/signature/{who}")
    public ResponseEntity<byte[]> signatureImage(@AuthenticationPrincipal Long userId, @PathVariable Integer storeId,
                                                 @PathVariable Integer staffId, @PathVariable String who) {
        return image(documentService.signaturePath(userId.intValue(), storeId, staffId, who));
    }

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
