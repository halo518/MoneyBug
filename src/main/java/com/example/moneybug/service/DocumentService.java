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
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private final StaffWageService wages;
    private final StoreRepository storeRepository;
    private final StoreChangeHub changeHub;

    /**
     * PUT /me/health-cert — expiry/alertDays/photo 모두 필수. certExpiry·certAlertDays·certPhotoUrl 저장.
     * 부수효과: 내가 재직 중인 모든 매장의 사장님에게 CERT 알림 "{이름}님이 보건증을 갱신했어요" + 그 매장 변경 신호.
     * 검증(만료일·알림 시점)은 파일 저장 전에 한다. 실패해 롤백되면 새 파일을, 성공하면 예전 사진을 지운다.
     */
    public UserDto.Response uploadHealthCert(Integer userId, HealthCertDto.Request request) {
        // 만료 알림 잡과 같은 행을 고치므로 먼저 잠근다.
        User user = userRepository.lockForCert(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요"));
        MultipartFile photo = request.getPhoto();
        if (photo == null || photo.isEmpty()) {
            throw ApiException.badRequest("PHOTO_REQUIRED", "보건증 사진을 올려 주세요");
        }
        LocalDate today = TimeUtil.today();
        if (request.getExpiry().isBefore(today)) {
            throw ApiException.badRequest("EXPIRY_IN_PAST", "만료일이 이미 지났어요. 유효한 보건증을 올려 주세요");
        }
        if (request.getExpiry().isAfter(today.plusYears(HealthCertDto.MAX_EXPIRY_YEARS))) {
            throw ApiException.badRequest("EXPIRY_TOO_FAR", "만료일이 너무 멀어요. 보건증의 만료일을 확인해 주세요");
        }
        List<Integer> alertDays = validAlertDays(request.getAlertDays());

        String replaced = user.getCertPhotoUrl();
        String path = fileStorage.saveImage("health-cert/" + userId, photo);
        user.setCertExpiry(request.getExpiry());
        user.setCertAlertDays(UserDto.joinAlertDays(alertDays));
        user.setCertPhotoUrl(path);

        String body = "만료일 " + DateTimeFormatter.ofPattern("yyyy.MM.dd").format(request.getExpiry());
        List<Integer> storeIds = new ArrayList<>();
        for (StoreWorker sw : storeWorkerRepository.findByWorkerIdAndStatusIn(userId,
                List.of(StoreWorker.Status.ACTIVE))) {
            notifier.sendToOwner(sw.getStore(), Notification.Type.CERT,
                    user.getName() + "님이 보건증을 갱신했어요", body, sw.getId());
            storeIds.add(sw.getStore().getId());
        }
        afterCertSaved(path, replaced, "health-cert/" + userId + "/", storeIds);
        return UserDto.Response.from(user);
    }

    /** 빈 항목·범위·개수를 파일 저장 전에 검사한다. 중복 제거 후 내림차순. */
    static List<Integer> validAlertDays(List<Integer> raw) {
        if (raw == null || raw.isEmpty() || raw.stream().anyMatch(d -> d == null || d < 0
                || d > HealthCertDto.MAX_ALERT_DAY)) {
            throw ApiException.badRequest("INVALID_ALERT_DAYS",
                    "알림 시점은 0~" + HealthCertDto.MAX_ALERT_DAY + "일 전 사이로 골라 주세요");
        }
        List<Integer> days = raw.stream().distinct().sorted(Comparator.reverseOrder()).toList();
        if (days.size() > HealthCertDto.MAX_ALERTS) {
            throw ApiException.badRequest("INVALID_ALERT_DAYS",
                    "알림 시점은 " + HealthCertDto.MAX_ALERTS + "개까지 고를 수 있어요");
        }
        return days;
    }

    /**
     * 커밋되면 예전 사진을 지우고 변경 신호를 보낸다. 롤백되면 방금 저장한 파일을 지운다.
     * 예전 사진은 그 사용자 폴더 안의 것만 지운다.
     */
    private void afterCertSaved(String saved, String replaced, String ownFolder, List<Integer> storeIds) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    fileStorage.delete(saved);
                    return;
                }
                if (replaced != null && !replaced.equals(saved) && replaced.startsWith(ownFolder)) {
                    fileStorage.delete(replaced);
                }
                changeHub.bumpAll(storeIds);
            }
        });
    }

    // ---- 프로필 (PATCH /me/profile, PUT·DELETE /me/photo, GET /people/{id}/photo) ----

    /** 연락처: 숫자와 - 만. */
    private static final java.util.regex.Pattern CONTACT = java.util.regex.Pattern.compile("[0-9-]*");

    /**
     * PATCH /me/profile — 보낸 키(null 이 아닌 것)만 반영. fullName 은 trim 뒤 1~50자(name 도 같은 값으로), contact 는 숫자·- 20자 이하,
     * 빈 문자열이면 지운다. 커밋 뒤 내 매장(소유·재직·대기)에 변경 신호.
     */
    public UserDto.Response updateProfile(Integer userId, UserDto.ProfileRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요"));
        String fullName = request.getFullName() == null ? null : request.getFullName().trim();
        if (fullName != null) {
            if (fullName.isEmpty()) throw ApiException.badRequest("NAME_REQUIRED", "이름을 입력해 주세요");
            if (chars(fullName) > 50) throw ApiException.badRequest("NAME_TOO_LONG", "이름은 50자 이하로 입력해 주세요");
        }
        String contact = request.getContact() == null ? null : request.getContact().trim();
        if (contact != null && (contact.length() > 20 || !CONTACT.matcher(contact).matches())) {
            throw ApiException.badRequest("INVALID_CONTACT", "연락처는 숫자와 - 로 20자 이하로 입력해 주세요");
        }
        if (fullName != null) {
            user.setFullName(fullName);
            user.setName(fullName); // FE 는 대부분 화면·아바타 글자에 name 을 쓴다
        }
        if (contact != null) user.setContact(contact.isEmpty() ? null : contact);
        afterCertSaved(null, null, "", myStoreIds(userId));
        return UserDto.Response.from(user);
    }

    /** PUT /me/photo — 형식·크기 검사는 FileStorage.saveImage. 커밋되면 예전 사진을 지우고 변경 신호. */
    public UserDto.Response uploadPhoto(Integer userId, MultipartFile photo) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요"));
        if (photo == null || photo.isEmpty()) {
            throw ApiException.badRequest("PHOTO_REQUIRED", "사진을 올려 주세요");
        }
        String replaced = user.getPhotoUrl();
        String path = fileStorage.saveImage(photoPrefix(userId), photo);
        user.setPhotoUrl(path);
        afterCertSaved(path, replaced, photoPrefix(userId) + "/", myStoreIds(userId));
        return UserDto.Response.from(user);
    }

    /** DELETE /me/photo — 없어도 200. 커밋되면 파일을 지우고 변경 신호. */
    public UserDto.Response deletePhoto(Integer userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요"));
        String replaced = user.getPhotoUrl();
        user.setPhotoUrl(null);
        afterCertSaved(null, replaced, photoPrefix(userId) + "/", myStoreIds(userId));
        return UserDto.Response.from(user);
    }

    /**
     * GET /people/{personId}/photo 경로. 본인, 그 사람이 속한(어떤 상태든) 매장의 사장님,
     * 그 사람이 사장인 매장의 재직·대기 직원, 같은 매장 재직 동료만. 아니면 403, 사진이 없으면 404.
     */
    @Transactional(readOnly = true)
    public String personPhotoPath(Integer viewerId, Integer personId) {
        if (!viewerId.equals(personId) && !sharesStore(viewerId, personId)) {
            throw ApiException.forbidden("같은 매장 사람의 사진만 볼 수 있어요");
        }
        String p = userRepository.findById(personId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요")).getPhotoUrl();
        if (p == null || p.isBlank()) throw ApiException.notFound("프로필 사진이 없어요");
        return p;
    }

    private boolean sharesStore(Integer viewerId, Integer personId) {
        List<StoreWorker> viewerRows = storeWorkerRepository.findByWorkerIdAndStatusIn(viewerId,
                List.of(StoreWorker.Status.ACTIVE, StoreWorker.Status.PENDING));
        if (viewerRows.stream().anyMatch(m -> m.getStore().getEmployer().getId().equals(personId))) return true;
        java.util.Set<Integer> viewerActive = new java.util.HashSet<>();
        viewerRows.stream().filter(m -> m.getStatus() == StoreWorker.Status.ACTIVE)
                .forEach(m -> viewerActive.add(m.getStore().getId()));
        return storeWorkerRepository.findByWorkerIdAndStatusIn(personId, List.of(StoreWorker.Status.values()))
                .stream().anyMatch(m -> m.getStore().getEmployer().getId().equals(viewerId)
                        || (m.getStatus() == StoreWorker.Status.ACTIVE && viewerActive.contains(m.getStore().getId())));
    }

    /** 내 이름·사진이 보이는 매장: 소유 + 재직·가입 대기 (WatchedStores 와 같은 범위). */
    private List<Integer> myStoreIds(Integer userId) {
        List<Integer> ids = new ArrayList<>();
        storeRepository.findByEmployerId(userId).forEach(s -> ids.add(s.getId()));
        storeWorkerRepository.findByWorkerIdAndStatusIn(userId,
                List.of(StoreWorker.Status.ACTIVE, StoreWorker.Status.PENDING)).forEach(m -> ids.add(m.getStore().getId()));
        return ids;
    }

    private static String photoPrefix(Integer userId) {
        return "profile-photo/" + userId;
    }

    /** POST /me/signatures — 서명 PNG 저장. 돌려주는 id 만 계약 서명 API 가 받는다. */
    public String saveSignature(Integer userId, MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw ApiException.badRequest("IMAGE_REQUIRED", "서명 이미지를 올려 주세요");
        }
        return fileStorage.saveImage(signaturePrefix(userId), image);
    }

    private static String signaturePrefix(Integer userId) {
        return "signatures/" + userId;
    }

    /** 본인이 올린 서명이고 실제로 저장된 파일인지. */
    private void requireOwnSignatureFile(Integer userId, String signatureUrl) {
        requireOwnSignature(userId, signatureUrl);
        try {
            fileStorage.read(signatureUrl);
        } catch (ApiException e) {
            if (e.getStatus() != HttpStatus.NOT_FOUND) throw e;
            throw ApiException.badRequest("INVALID_SIGNATURE", "서명 이미지를 찾을 수 없어요. 다시 서명해 주세요");
        }
    }

    /** 본인이 올린 서명인지 (다른 사람 파일·경로 탈출 차단). */
    static void requireOwnSignature(Integer userId, String signatureUrl) {
        if (signatureUrl == null || signatureUrl.contains("..")
                || !signatureUrl.startsWith(signaturePrefix(userId) + "/")) {
            throw ApiException.badRequest("INVALID_SIGNATURE", "본인이 올린 서명 이미지가 아니에요");
        }
    }

    /** 내 보건증 사진 (경로). 없으면 404. */
    @Transactional(readOnly = true)
    public String myCertPhotoPath(Integer userId) {
        return certPath(userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요")));
    }

    /** 직원 보건증 사진 (경로). 그 매장 사장님만, 재직(ACTIVE) 직원만. */
    @Transactional(readOnly = true)
    public String staffCertPhotoPath(Integer userId, Integer storeId, Integer staffId) {
        access.requireOwner(userId, storeId);
        StoreWorker sw = access.staff(storeId, staffId);
        // 보건증은 사람 단위라 퇴사·거절·대기 중인 사람의 지금 사진이 나가지 않게 재직 직원만 허용한다.
        if (sw.getStatus() != StoreWorker.Status.ACTIVE) {
            throw ApiException.forbidden("재직 중인 직원의 보건증만 볼 수 있어요");
        }
        return certPath(sw.getWorker());
    }

    private static String certPath(User u) {
        String p = u.getCertPhotoUrl();
        if (p == null || p.isBlank()) throw ApiException.notFound("보건증 사진이 없어요");
        return p;
    }

    /** 서명 이미지 경로. who = owner | employee. 그 매장 사장님이나 계약 당사자 직원만. */
    @Transactional(readOnly = true)
    public String signaturePath(Integer userId, Integer storeId, Integer staffId, String who) {
        if (!"owner".equals(who) && !"employee".equals(who)) {
            throw ApiException.badRequest("INVALID_WHO", "서명 종류는 owner 또는 employee 여야 해요");
        }
        StoreWorker sw = access.staff(storeId, staffId);
        if (!access.isOwner(access.store(storeId), userId) && !sw.getWorker().getId().equals(userId)) {
            throw ApiException.forbidden("계약 당사자만 볼 수 있어요");
        }
        Contract c = contractRepository.findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(sw.getId())
                .orElseThrow(() -> ApiException.notFound("근로계약서가 없어요"));
        String p = "owner".equals(who) ? c.getOwnerSignatureUrl() : c.getEmployeeSignatureUrl();
        if (p == null || p.isBlank()) throw ApiException.notFound("서명 이미지가 없어요");
        return p;
    }

    /**
     * PUT .../staff/{staffId}/contract — 사장님이 작성+서명해서 보냄.
     * 검증: 시급 >= 최저임금, 종료일 > 시작일, 필수 4항목(DTO). 새 계약서를 이력으로 쌓고 직원에게 CONTRACT 알림.
     * 매장 행을 먼저 잠가 같은 직원 계약서·시급 이력 쓰기를 직렬화한다.
     */
    public ContractDto.Response upsertContract(Integer userId, Integer storeId, Integer staffId,
                                               ContractDto.Request request) {
        storeRepository.findByIdForUpdate(storeId);
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
        // DB varchar 는 글자 수로 센다 (@Size 는 UTF-16 단위라 이모지를 두 번 센다)
        if (chars(request.getWorkDays()) > 50) {
            throw ApiException.badRequest("WORK_DAYS_TOO_LONG", "근무 요일은 50자 이하로 적어 주세요");
        }
        if (chars(request.getDuty()) > 100) {
            throw ApiException.badRequest("DUTY_TOO_LONG", "업무 내용은 100자 이하로 적어 주세요");
        }

        if (request.getOwnerSignatureUrl() != null && !request.getOwnerSignatureUrl().isBlank()) {
            requireOwnSignatureFile(userId, request.getOwnerSignatureUrl());
        } else {
            request.setOwnerSignatureUrl(null);
        }
        // 서명 전에 대체되는 계약서는 효력이 없었으므로 그 시급 단계도 지운다
        Integer supersededUnsigned = contractRepository.findFirstByStoreWorkerIdOrderByCreatedAtDescIdDesc(sw.getId())
                .filter(prev -> prev.getEmployeeSignedAt() == null).map(Contract::getId).orElse(null);
        Contract contract = request.toEntity(sw); // ownerSignedAt = 지금, employeeSignedAt = null
        contractRepository.save(contract);
        wages.recordContract(sw, contract.getId(), request.getStartDate(), request.getEndDate(), request.getWage(),
                supersededUnsigned);

        notifier.sendToStaff(store, sw, Notification.Type.CONTRACT, "근로계약서 서명 요청",
                store.getStoreName(), contract.getId());
        return ContractDto.Response.from(contract);
    }

    /**
     * POST .../contract/sign — 본인(직원)만. 계약서가 없으면 404, 이미 서명했으면 409,
     * contractId 를 보냈는데 현재 계약서가 아니면 409 CONTRACT_CHANGED. 현재 계약서 행을 잠가 동시 서명은 한 번만 성공.
     * employeeSignedAt=now + 서명 이미지·IP·약관 버전 기록, 사장님에게 CONTRACT 알림.
     */
    public ContractDto.Response signContract(Integer userId, Integer storeId, Integer staffId,
                                             ContractDto.SignRequest request, String clientIp) {
        Store store = access.store(storeId);
        StoreWorker sw = access.staff(storeId, staffId);
        if (!sw.getWorker().getId().equals(userId)) {
            throw ApiException.forbidden("본인의 계약서만 서명할 수 있어요");
        }
        if (sw.getStatus() != StoreWorker.Status.ACTIVE) {
            throw ApiException.conflict("NOT_ACTIVE", "재직 중인 직원이 아니에요");
        }
        requireOwnSignatureFile(userId, request.getSignatureUrl());
        Contract contract = contractRepository.findFirstForUpdateByStoreWorkerIdOrderByCreatedAtDescIdDesc(sw.getId())
                .orElseThrow(() -> ApiException.notFound("서명할 근로계약서가 없어요"));
        if (request.getContractId() != null && !request.getContractId().equals(contract.getId())) {
            throw ApiException.conflict("CONTRACT_CHANGED", "사장님이 계약서를 다시 작성했어요. 새 계약서를 확인하고 서명해 주세요");
        }
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

    private static int chars(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }
}
