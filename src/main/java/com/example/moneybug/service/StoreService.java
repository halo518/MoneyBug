package com.example.moneybug.service;

import com.example.moneybug.dto.StoreCreateRequest;
import com.example.moneybug.dto.StoreDetailDto;
import com.example.moneybug.dto.StoreDto;
import com.example.moneybug.dto.StorePartDto;
import com.example.moneybug.dto.StoreWorkerDto;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StorePart;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.PayrollRepository;
import com.example.moneybug.repository.StorePartRepository;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 매장 (설계서 §4.2). */
@Service
@Transactional
@RequiredArgsConstructor
public class StoreService {

    // 기본 파트 템플릿: 오픈 10–16 · 미들 14–20 · 마감 18–22, need=1 (설계서 §4.2, 시드 기준)
    private static final String[] DEFAULT_PART_NAMES = {"오픈", "미들", "마감"};
    private static final int[][] DEFAULT_PART_TIMES = {{600, 960}, {840, 1200}, {1080, 1320}};

    private final StoreRepository storeRepository;
    private final StorePartRepository storePartRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final UserRepository userRepository;
    private final PayrollRepository payrollRepository;
    private final StoreAccess access;
    private final NotificationPublisher notifier;

    /** POST /stores — 코드 생성 + 기본 파트 템플릿. 상호명·주소가 비면 400. 사장님 1인 N매장 허용. */
    public StoreDetailDto.Response create(Integer userId, StoreCreateRequest request) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요"));
        if (request.name() == null || request.name().isBlank()
                || request.address() == null || request.address().isBlank()) {
            throw ApiException.badRequest("STORE_REQUIRED_FIELDS", "상호명과 주소를 입력해 주세요");
        }

        Store store = request.toEntity(owner);
        String code;
        do {
            code = Store.newCode();
        } while (storeRepository.existsByCode(code));
        store.setCode(code);
        storeRepository.save(store);

        for (int i = 0; i < DEFAULT_PART_NAMES.length; i++) {
            StorePart part = new StorePart();
            part.setStore(store);
            part.setName(DEFAULT_PART_NAMES[i]);
            part.setStartMin(DEFAULT_PART_TIMES[i][0]);
            part.setEndMin(DEFAULT_PART_TIMES[i][1]);
            part.setNeed(1);
            storePartRepository.save(part);
        }
        return detail(store);
    }

    /** GET /stores/{storeId} — 사장님·직원. */
    @Transactional(readOnly = true)
    public StoreDetailDto.Response get(Integer userId, Integer storeId) {
        return detail(access.requireOwnerOrStaff(userId, storeId));
    }

    /** PATCH /stores/{storeId} — 사장님. 보낸 것만 반영. */
    public StoreDetailDto.Response update(Integer userId, Integer storeId, StoreDto.UpdateRequest request) {
        Store store = access.requireOwner(userId, storeId);
        if (request.getStoreName() != null) {
            if (request.getStoreName().isBlank()) {
                throw ApiException.badRequest("STORE_REQUIRED_FIELDS", "상호명을 입력해 주세요");
            }
            store.setStoreName(request.getStoreName().trim());
        }
        if (request.getAddress() != null) {
            if (request.getAddress().isBlank()) {
                throw ApiException.badRequest("STORE_REQUIRED_FIELDS", "주소를 입력해 주세요");
            }
            store.setAddress(request.getAddress().trim());
        }
        if (request.getUnderFive() != null) {
            store.setUnderFive(request.getUnderFive());
        }
        if (request.getDefaultDeduction() != null) {
            store.setDefaultDeduction(request.getDefaultDeduction());
        }
        return detail(store);
    }

    /** PATCH /stores/{storeId}/settings — 사장님. 보낸 것만 반영. */
    public StoreDto.Response updateSettings(Integer userId, Integer storeId, StoreDto.SettingsRequest request) {
        Store store = access.requireOwner(userId, storeId);
        if (request.getUnderFive() != null) {
            store.setUnderFive(request.getUnderFive());
        }
        if (request.getNoticeRequiredDefault() != null) {
            store.setNoticeRequiredDefault(request.getNoticeRequiredDefault());
        }
        if (request.getSubstitutePush() != null) {
            store.setSubstitutePush(request.getSubstitutePush());
        }
        return StoreDto.Response.from(store);
    }

    /** PUT /stores/{storeId}/parts/needs — 사장님. */
    public List<StorePartDto.Response> updatePartNeeds(Integer userId, Integer storeId, List<Integer> needs) {
        access.requireOwner(userId, storeId);
        return applyNeeds(storeId, needs).stream().map(StorePartDto.Response::from).toList();
    }

    /** store.parts 순서(시작 시각순)대로 need 갱신. 인덱스가 모자라면 무시, 음수는 400. 다른 서비스도 사용한다. */
    public List<StorePart> applyNeeds(Integer storeId, List<Integer> needs) {
        List<StorePart> parts = storePartRepository.findByStoreIdOrderByStartMinAsc(storeId);
        for (int i = 0; i < parts.size() && i < needs.size(); i++) {
            Integer need = needs.get(i);
            if (need == null || need < 0) {
                throw ApiException.badRequest("INVALID_NEED", "필요 인원은 0 이상이어야 해요");
            }
            parts.get(i).setNeed(need);
        }
        return parts;
    }

    /**
     * POST /stores/join-requests — 코드는 대소문자·하이픈 무시.
     * 부수효과: StoreWorker(PENDING, 첫 파트, 최저임금, requestedAt=now) 생성 + 사장님에게 JOIN 알림.
     * 결과(JoinResult)에 따라 컨트롤러가 201/404/409 로 응답한다.
     */
    public StoreDetailDto.JoinResponse requestJoin(Integer userId, String code) {
        String normalized = code == null ? "" : code.toUpperCase().replaceAll("[^A-Z0-9]", "");
        Store store = normalized.isEmpty() ? null : storeRepository.findByNormalizedCode(normalized).orElse(null);
        if (store == null) {
            return new StoreDetailDto.JoinResponse(StoreDetailDto.JoinResult.NOT_FOUND, null);
        }
        StoreDetailDto.JoinStoreRef ref = new StoreDetailDto.JoinStoreRef(store.getId(), store.getStoreName());

        if (access.isOwner(store, userId)
                || storeWorkerRepository.existsByStoreIdAndWorkerIdAndStatus(store.getId(), userId,
                StoreWorker.Status.ACTIVE)) {
            return new StoreDetailDto.JoinResponse(StoreDetailDto.JoinResult.ALREADY_MEMBER, ref);
        }
        if (storeWorkerRepository.existsByStoreIdAndWorkerIdAndStatus(store.getId(), userId,
                StoreWorker.Status.PENDING)) {
            return new StoreDetailDto.JoinResponse(StoreDetailDto.JoinResult.ALREADY_PENDING, ref);
        }

        // LEFT / REJECTED 이후의 재가입은 허용: 새 행을 만든다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없어요"));
        List<StorePart> parts = storePartRepository.findByStoreIdOrderByStartMinAsc(store.getId());

        StoreWorker sw = new StoreWorker();
        sw.setStore(store);
        sw.setWorker(user);
        sw.setStatus(StoreWorker.Status.PENDING);
        sw.setHourlyWage(StoreWorkerDto.MIN_WAGE_2026);
        sw.setTaxType(store.getDefaultDeduction() != null ? store.getDefaultDeduction() : StoreWorker.TaxType.NONE);
        sw.setPart(parts.isEmpty() ? null : parts.get(0).getName());
        sw.setRequestedAt(TimeUtil.now());
        storeWorkerRepository.save(sw);

        notifier.sendToOwner(store, Notification.Type.JOIN, user.getName() + "님이 가입을 신청했어요",
                store.getStoreName(), sw.getId());
        return new StoreDetailDto.JoinResponse(StoreDetailDto.JoinResult.REQUESTED, ref);
    }

    /** GET /stores/{storeId}/code — 사장님. */
    @Transactional(readOnly = true)
    public StoreDetailDto.CodeResponse getCode(Integer userId, Integer storeId) {
        return new StoreDetailDto.CodeResponse(access.requireOwner(userId, storeId).getCode());
    }

    /** 매장 + 파트 + 확정된 월별 인건비(이번 달 이전, Payroll 합계). */
    private StoreDetailDto.Response detail(Store store) {
        List<StorePart> parts = storePartRepository.findByStoreIdOrderByStartMinAsc(store.getId());
        Map<String, Integer> monthlyTotals = new LinkedHashMap<>();
        String current = YearMonth.from(TimeUtil.today()).toString();
        for (Object[] row : payrollRepository.sumGrossByMonthBefore(store.getId(), current)) {
            monthlyTotals.put((String) row[0], row[1] == null ? 0 : ((Number) row[1]).intValue());
        }
        return StoreDetailDto.Response.from(store, parts, monthlyTotals);
    }
}
