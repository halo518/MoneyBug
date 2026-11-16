package com.example.moneybug.service;

import com.example.moneybug.dto.ContractDto;
import com.example.moneybug.dto.HealthCertDto;
import com.example.moneybug.dto.StoreWorkerDto;
import com.example.moneybug.dto.UserDto;
import com.example.moneybug.entity.Contract;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.ContractRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/** 서류: 보건증 · 근로계약서 (설계서 §4.4). */
@Service
@Transactional
@RequiredArgsConstructor
public class DocumentService {

    /** 서명 시점의 약관 버전. 약관이 바뀌면 올린다. */
    private static final String TERMS_VERSION = "v1";

    private final UserRepository userRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final ContractRepository contractRepository;
    private final StoreAccess access;
    private final NotificationPublisher notifier;
    private final FileStorage fileStorage;

    /**
     * PUT /me/health-cert — expiry/alertDays/photo 모두 필수. certExpiry·certAlertDays·certPhotoUrl 저장.
     * 부수효과: 내가 재직 중인 모든 매장의 사장님에게 CERT 알림 "{이름}님이 보건증을 갱신했어요".
     */
    public UserDto.Response uploadHealthCert(Integer userId, HealthCertDto.Request request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요"));
        MultipartFile photo = request.getPhoto();
        if (photo == null || photo.isEmpty()) {
            throw ApiException.badRequest("PHOTO_REQUIRED", "보건증 사진을 올려 주세요");
        }
        List<Integer> alertDays = request.getAlertDays().stream().distinct()
                .sorted(Comparator.reverseOrder()).toList();
        if (alertDays.stream().anyMatch(d -> d == null || d < 0)) {
            throw ApiException.badRequest("INVALID_ALERT_DAYS", "알림 시점은 0 이상이어야 해요");
        }

        String path = fileStorage.saveImage("health-cert/" + userId, photo);
        user.setCertExpiry(request.getExpiry());
        user.setCertAlertDays(UserDto.joinAlertDays(alertDays));
        user.setCertPhotoUrl(path);

        String body = "만료일 " + DateTimeFormatter.ofPattern("yyyy.MM.dd").format(request.getExpiry());
        for (StoreWorker sw : storeWorkerRepository.findByWorkerIdAndStatusIn(userId,
                List.of(StoreWorker.Status.ACTIVE))) {
            notifier.sendToOwner(sw.getStore(), Notification.Type.CERT,
                    user.getName() + "님이 보건증을 갱신했어요", body, sw.getId());
        }
        return UserDto.Response.from(user);
    }

    /**
     * PUT .../staff/{staffId}/contract — 사장님이 작성+서명해서 보냄.
     * 검증: 시급 >= 최저임금, 종료일 > 시작일, 필수 4항목(DTO). 새 계약서를 이력으로 쌓고 직원에게 CONTRACT 알림.
     */
    public ContractDto.Response upsertContract(Integer userId, Integer storeId, Integer staffId,
                                               ContractDto.Request request) {
        Store store = access.requireOwner(userId, storeId);
        StoreWorker sw = access.staff(storeId, staffId);
        if (sw.getStatus() != StoreWorker.Status.ACTIVE) {
            throw ApiException.conflict("NOT_ACTIVE", "재직 중인 직원이 아니에요");
        }
        if (request.getWage() < StoreWorkerDto.MIN_WAGE_2026) {
            throw ApiException.badRequest("WAGE_BELOW_MINIMUM",
                    "시급은 2026 최저임금 " + String.format("%,d", StoreWorkerDto.MIN_WAGE_2026) + "원 이상이어야 해요");
        }
        if (!request.getEndDate().isAfter(request.getStartDate())) {
            throw ApiException.badRequest("INVALID_CONTRACT_PERIOD", "계약 종료일은 시작일 이후여야 해요");
        }

        Contract contract = request.toEntity(sw); // ownerSignedAt = 지금, employeeSignedAt = null
        contractRepository.save(contract);

        notifier.sendToStaff(store, sw, Notification.Type.CONTRACT, "근로계약서 서명 요청",
                store.getStoreName(), contract.getId());
        return ContractDto.Response.from(contract);
    }

    /**
     * POST .../contract/sign — 본인(직원)만. 계약서가 없으면 404, 이미 서명했으면 409.
     * employeeSignedAt=now + 서명 이미지·IP·약관 버전 기록, 사장님에게 CONTRACT 알림.
     */
    public ContractDto.Response signContract(Integer userId, Integer storeId, Integer staffId,
                                             ContractDto.SignRequest request, String clientIp) {
        Store store = access.store(storeId);
        StoreWorker sw = access.staff(storeId, staffId);
        if (!sw.getWorker().getId().equals(userId)) {
            throw ApiException.forbidden("본인의 계약서만 서명할 수 있어요");
        }
        Contract contract = contractRepository.findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(sw.getId())
                .orElseThrow(() -> ApiException.notFound("서명할 근로계약서가 없어요"));
        if (contract.getEmployeeSignedAt() != null) {
            throw ApiException.conflict("ALREADY_SIGNED", "이미 서명한 계약서예요");
        }

        contract.setEmployeeSignedAt(TimeUtil.now());
        contract.setEmployeeSignatureUrl(request.getSignatureUrl());
        contract.setEmployeeSignedIp(clientIp);
        contract.setTermsVersion(TERMS_VERSION);

        notifier.sendToOwner(store, Notification.Type.CONTRACT,
                sw.getWorker().getName() + "님이 근로계약서에 서명했어요", null, contract.getId());
        return ContractDto.Response.from(contract);
    }
}
