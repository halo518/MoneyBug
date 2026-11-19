package com.example.moneybug.service;

import com.example.moneybug.dto.StoreCreateRequest;
import com.example.moneybug.dto.StoreDetailDto;
import com.example.moneybug.dto.StoreDto;
import com.example.moneybug.dto.StorePartDto;
import com.example.moneybug.dto.StoreWorkerDto;
import com.example.moneybug.dto.ScheduleTemplateDto;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.ScheduleTemplate;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.ScheduleTemplateRepository;
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

    // 기본 파트(autoSchedule 기본값): 오픈 08–12 · 미들 12–17 · 마감 17–22, 휴게 30, need=1
    private static final String[] DEFAULT_PART_NAMES = {"오픈", "미들", "마감"};
    private static final StorePart.Tone[] DEFAULT_PART_TONES =
            {StorePart.Tone.OPEN, StorePart.Tone.MIDDLE, StorePart.Tone.CLOSE};
    private static final int[][] DEFAULT_PART_TIMES = {{480, 720}, {720, 1020}, {1020, 1320}};

    private final StoreRepository storeRepository;
    private final StorePartRepository storePartRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final UserRepository userRepository;
    private final PayrollRepository payrollRepository;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleTemplateRepository scheduleTemplateRepository;
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
        checkName(request.name().trim());
        checkAddress(request.address().trim());

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
            part.setTone(DEFAULT_PART_TONES[i]);
            part.setBreakMin(30);
            part.setFlex(false);
            part.setDays(StorePart.Days.ALL);
            storePartRepository.save(part);
        }
        return detail(store);
    }

    /** GET /stores/{storeId} — 사장님·직원. */
    @Transactional(readOnly = true)
    public StoreDetailDto.Response get(Integer userId, Integer storeId) {
        Store store = access.requireOwnerOrStaff(userId, storeId);
        return detail(store, access.isOwner(store, userId));
    }

    /** PATCH /stores/{storeId} — 사장님. 보낸 것만 반영. */
    public StoreDetailDto.Response update(Integer userId, Integer storeId, StoreDto.UpdateRequest request) {
        storeRepository.findByIdForUpdate(storeId); // 매장 행을 먼저 잠근다(syncSize 와 같은 순서)
        Store store = access.requireOwner(userId, storeId);
        if (request.getStoreName() != null) {
            if (request.getStoreName().isBlank()) {
                throw ApiException.badRequest("STORE_REQUIRED_FIELDS", "상호명을 입력해 주세요");
            }
            store.setStoreName(checkName(request.getStoreName().trim()));
        }
        if (request.getAddress() != null) {
            if (request.getAddress().isBlank()) {
                throw ApiException.badRequest("STORE_REQUIRED_FIELDS", "주소를 입력해 주세요");
            }
            store.setAddress(checkAddress(request.getAddress().trim()));
        }
        if (request.getUnderFive() != null) {
            store.setSizeMode(request.getUnderFive() ? Store.SizeMode.UNDER5 : Store.SizeMode.OVER5);
            syncSize(store);
        }
        if (request.getDefaultDeduction() != null) {
            store.setDefaultDeduction(request.getDefaultDeduction());
        }
        return detail(store);
    }

    /** PATCH /stores/{storeId}/settings — 사장님. 보낸 것만 반영. */
    public StoreDto.Response updateSettings(Integer userId, Integer storeId, StoreDto.SettingsRequest request) {
        storeRepository.findByIdForUpdate(storeId); // 매장 행을 먼저 잠근다(syncSize 와 같은 순서)
        Store store = access.requireOwner(userId, storeId);
        if (request.getStoreName() != null) {
            if (request.getStoreName().isBlank()) {
                throw ApiException.badRequest("STORE_REQUIRED_FIELDS", "매장 이름을 입력하세요.");
            }
            store.setStoreName(checkName(request.getStoreName().trim()));
        }
        if (request.getAddress() != null) {
            if (request.getAddress().isBlank()) {
                throw ApiException.badRequest("STORE_REQUIRED_FIELDS", "주소를 입력해 주세요");
            }
            store.setAddress(checkAddress(request.getAddress().trim()));
        }
        if (request.getOpenMin() != null) store.setOpenMin(request.getOpenMin());
        if (request.getCloseMin() != null) store.setCloseMin(request.getCloseMin());
        if (request.getWeekendOpenMin() != null) store.setWeekendOpenMin(request.getWeekendOpenMin());
        if (request.getWeekendCloseMin() != null) store.setWeekendCloseMin(request.getWeekendCloseMin());
        validateHours(store);
        // 이전 앱의 isSmall 도 받는다. sizeMode 가 같이 오면 sizeMode 가 우선.
        if (request.getSizeMode() != null) {
            store.setSizeMode(request.getSizeMode());
        } else if (request.getUnderFive() != null) {
            store.setSizeMode(request.getUnderFive() ? Store.SizeMode.UNDER5 : Store.SizeMode.OVER5);
        }
        syncSize(store);
        if (request.getNoticeRequiredDefault() != null) {
            store.setNoticeRequiredDefault(request.getNoticeRequiredDefault());
        }
        if (request.getSubstitutePush() != null) {
            store.setSubstitutePush(request.getSubstitutePush());
        }
        if (request.getDefaultDeduction() != null) {
            store.setDefaultDeduction(request.getDefaultDeduction());
        }
        return StoreDto.Response.from(store);
    }

    /** 상호명 길이(DB varchar(100), 문자 수). trim 한 값을 받는다. */
    static String checkName(String name) {
        if (name.codePointCount(0, name.length()) > 100) {
            throw ApiException.badRequest("STORE_FIELD_TOO_LONG", "상호명은 100자 이하로 입력해 주세요");
        }
        return name;
    }

    /** 주소 길이(DB varchar(255), 문자 수). trim 한 값을 받는다. */
    static String checkAddress(String address) {
        if (address.codePointCount(0, address.length()) > 255) {
            throw ApiException.badRequest("STORE_FIELD_TOO_LONG", "주소는 255자 이하로 입력해 주세요");
        }
        return address;
    }

    /** 영업시간 검증. 400 문구는 계약 그대로. */
    static void validateHours(Store s) {
        for (int[] h : new int[][]{{s.getOpenMin(), s.getCloseMin()}, {s.getWeekendOpenMin(), s.getWeekendCloseMin()}}) {
            if (h[0] < 0 || h[0] > 1439 || h[1] > 2880) {
                throw ApiException.badRequest("INVALID_HOURS", "영업 시각은 0:00~다음 날 24:00 범위여야 합니다.");
            }
        }
        if (s.getCloseMin() <= s.getOpenMin()) {
            throw ApiException.badRequest("INVALID_HOURS", "평일 영업 종료 시각은 시작 시각보다 늦어야 합니다.");
        }
        if (s.getWeekendCloseMin() <= s.getWeekendOpenMin()) {
            throw ApiException.badRequest("INVALID_HOURS", "주말·공휴일 영업 종료 시각은 시작 시각보다 늦어야 합니다.");
        }
        if (s.getCloseMin() - s.getOpenMin() > 1440 || s.getWeekendCloseMin() - s.getWeekendOpenMin() > 1440) {
            throw ApiException.badRequest("INVALID_HOURS", "영업시간은 24시간을 넘을 수 없습니다.");
        }
    }

    /**
     * 규모 모드에 맞춰 저장된 underFive 를 갱신(급여·AI 노무가 이 값을 읽는다). 직원 승인·퇴사 때도 호출.
     * 매장 행 잠금을 먼저 잡는다: 호출한 쪽이 이미 잡았으면 같은 트랜잭션이라 그대로 지나가고,
     * 안 잡았으면 인원을 세기 전에 잡아 동시 승인끼리 UPDATE store 에서 데드락 나지 않게 한다.
     * 값이 같으면 쓰지 않는다(불필요한 UPDATE 없음).
     */
    public void syncSize(Store store) {
        storeRepository.findByIdForUpdate(store.getId());
        long active = storeWorkerRepository.countByStoreIdAndStatus(store.getId(), StoreWorker.Status.ACTIVE);
        boolean under = store.effectiveUnderFive(active);
        if (store.isUnderFive() != under) store.setUnderFive(under);
    }

    /** 필요 인원 상한(FE 입력칸 clamp(0, 99)). */
    static final int MAX_NEED = 99;

    /** 파트 이름 비교 키: DB utf8mb4_unicode_ci 처럼 전각·반각, 대소문자를 같게 본다. */
    static String partKey(String name) {
        return java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * PUT /stores/{storeId}/parts — 파트 목록을 통째로 맞춘다. id 있으면 수정(이름 변경은 근무·직원·고정 근무·템플릿에 전파),
     * 없으면 추가, 목록에 없는 기존 파트는 삭제(그 파트의 근무·템플릿 근무는 파트 없는 근무가 된다).
     */
    public List<StorePartDto.Response> savePartList(Integer userId, Integer storeId, List<StorePartDto.Item> items) {
        storeRepository.findByIdForUpdate(storeId); // 같은 매장의 동시 파트 저장을 줄 세운다
        Store store = access.requireOwner(userId, storeId);
        List<StorePart> existing = storePartRepository.findByStoreIdOrderByStartMinAsc(storeId);
        Map<Integer, StorePart> byId = new LinkedHashMap<>();
        existing.forEach(p -> byId.put(p.getId(), p));

        java.util.Set<String> names = new java.util.HashSet<>();
        Map<Integer, StorePartDto.Item> keep = new LinkedHashMap<>();
        List<StorePartDto.Item> added = new java.util.ArrayList<>();
        for (StorePartDto.Item it : items) {
            if (it == null) throw ApiException.badRequest("INVALID_PART", "비어 있는 파트가 있어요.");
            String name = it.name() == null ? "" : it.name().trim();
            if (name.isEmpty()) throw ApiException.badRequest("INVALID_PART", "파트 이름을 입력하세요.");
            if (name.codePointCount(0, name.length()) > 50) {
                throw ApiException.badRequest("INVALID_PART", "파트 이름은 50자 이하여야 해요.");
            }
            if (it.startMin() == null || it.endMin() == null || it.startMin() < 0 || it.startMin() > 1439
                    || it.endMin() > 2880 || it.endMin() <= it.startMin()) {
                throw ApiException.badRequest("INVALID_PART", name + " 종료 시각은 시작 시각보다 늦어야 합니다.");
            }
            int length = it.endMin() - it.startMin();
            if (length > 1440) {
                throw ApiException.badRequest("INVALID_PART", name + " 파트는 24시간을 넘을 수 없어요.");
            }
            if (it.breakMin() != null && it.breakMin() < 0 || it.need() != null && it.need() < 0) {
                throw ApiException.badRequest("INVALID_PART", name + " 휴게·필요 인원은 0 이상이어야 해요.");
            }
            if (it.breakMin() != null && it.breakMin() >= length) {
                throw ApiException.badRequest("INVALID_PART", name + " 휴게 시간은 파트 길이보다 짧아야 해요.");
            }
            if (it.need() != null && it.need() > MAX_NEED) {
                throw ApiException.badRequest("INVALID_PART", name + " 필요 인원은 " + MAX_NEED + "명 이하여야 해요.");
            }
            if (!names.add(partKey(name))) {
                throw ApiException.badRequest("DUPLICATE_PART", "파트 이름이 겹쳐요: " + name);
            }
            Integer id = parsePartId(it.id());
            if (id == null) {
                added.add(it);
            } else {
                if (!byId.containsKey(id)) throw ApiException.notFound("파트를 찾을 수 없어요");
                if (it.flex() != null && it.flex() != byId.get(id).isFlex()) {
                    throw ApiException.badRequest("INVALID_PART",
                            name + " 파트는 직원 파트·알바 파트를 바꿀 수 없어요. 지우고 새로 만들어 주세요.");
                }
                if (keep.put(id, it) != null) {
                    throw ApiException.badRequest("DUPLICATE_PART", "같은 파트가 두 번 들어 있어요");
                }
            }
        }

        // unique(store, name) 때문에 순서가 중요: 삭제 → 임시 이름 → 최종 이름/추가
        java.util.Set<String> deleted = new java.util.HashSet<>();
        Map<String, Boolean> deletedFlex = new java.util.HashMap<>();
        for (StorePart p : existing) {
            if (!keep.containsKey(p.getId())) {
                deleted.add(p.getName());
                deletedFlex.put(partKey(p.getName()), p.isFlex());
                storePartRepository.delete(p);
            }
        }
        storePartRepository.flush();

        Map<String, String> renames = new LinkedHashMap<>();
        for (var e : keep.entrySet()) {
            StorePart p = byId.get(e.getKey());
            String name = e.getValue().name().trim();
            if (!p.getName().equals(name)) {
                renames.put(p.getName(), name);
                p.setName("~" + p.getId());
            }
        }
        storePartRepository.flush();

        for (var e : keep.entrySet()) {
            StorePart p = byId.get(e.getKey());
            StorePartDto.Item it = e.getValue();
            p.setName(it.name().trim());
            fill(p, it);
        }
        for (StorePartDto.Item it : added) {
            StorePart p = new StorePart();
            p.setStore(store);
            p.setName(it.name().trim());
            p.setFlex(Boolean.TRUE.equals(it.flex()));
            fill(p, it);
            storePartRepository.save(p);
        }
        storePartRepository.flush();

        // 같은 저장에서 같은 이름(대소문자·전각 무시)을 같은 목록(직원·알바)에 다시 쓰는 경우는 지우지 않는다.
        // 다른 목록으로 다시 쓰면 지운 것으로 본다(알바 고정 근무에 직원 파트 이름이 남지 않게).
        Map<String, Boolean> finalFlex = new java.util.HashMap<>();
        keep.forEach((id, it) -> finalFlex.put(partKey(it.name()), byId.get(id).isFlex()));
        added.forEach(it -> finalFlex.put(partKey(it.name()), Boolean.TRUE.equals(it.flex())));
        deleted.removeIf(n -> deletedFlex.get(partKey(n)).equals(finalFlex.get(partKey(n))));
        if (!deleted.isEmpty()) dropDeletedParts(storeId, deleted);
        if (!renames.isEmpty()) propagateRenames(storeId, renames);
        return storePartRepository.findByStoreIdOrderByStartMinAsc(storeId).stream()
                .map(StorePartDto.Response::from).toList();
    }

    private static Integer parsePartId(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return Integer.valueOf(id.trim());
        } catch (NumberFormatException e) {
            return null; // 데모 시드 id('part-open')처럼 서버 id 가 아니면 새 파트
        }
    }

    private static void fill(StorePart p, StorePartDto.Item it) {
        p.setStartMin(it.startMin());
        p.setEndMin(it.endMin());
        if (it.tone() != null) p.setTone(it.tone());
        p.setBreakMin(it.breakMin() == null ? 0 : it.breakMin());
        p.setNeed(it.need() == null ? 1 : it.need());
        if (it.days() != null) p.setDays(it.days()); // 예전 앱은 안 보낸다: 기존 값 유지, 새 파트는 ALL
    }

    /**
     * 삭제된 파트 이름을 직원 기본 파트·고정 근무에서 뺀다(기본 파트는 남은 첫 파트 또는 null).
     * 그 파트의 근무와 템플릿 근무는 파트 없는 근무("")가 된다(FE 삭제 확인 창 문구).
     */
    private void dropDeletedParts(Integer storeId, java.util.Set<String> deleted) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        deleted.forEach(n -> keys.add(partKey(n)));
        for (Schedule s : scheduleRepository.findByStoreAndPartIn(storeId, deleted)) {
            if (s.getPart() != null && keys.contains(partKey(s.getPart()))) s.setPart("");
        }
        for (ScheduleTemplate t : scheduleTemplateRepository.findByStoreIdOrderByIdAsc(storeId)) {
            List<ScheduleTemplateDto.Shift> shifts = ScheduleTemplateDto.parse(t.getPatternJson());
            if (shifts.stream().noneMatch(x -> x.part() != null && keys.contains(partKey(x.part())))) continue;
            t.setPatternJson(ScheduleTemplateDto.toJson(shifts.stream()
                    .map(x -> x.part() != null && keys.contains(partKey(x.part()))
                            ? new ScheduleTemplateDto.Shift(x.dayIndex(), x.staffId(), x.startMin(), x.endMin(),
                            "", x.breakMin(), x.kind(), x.timeTbd())
                            : x)
                    .toList()));
        }
        String fallback = storePartRepository.findByStoreIdOrderByStartMinAsc(storeId).stream()
                .filter(p -> !p.isFlex()).map(StorePart::getName).findFirst().orElse(null);
        for (StoreWorker sw : storeWorkerRepository.findByStoreId(storeId)) {
            if (sw.getPart() != null && deleted.contains(sw.getPart())) sw.setPart(fallback);
            if (sw.getFixedParts() != null) {
                StaffPrefs.setFixedParts(sw, StaffPrefs.fixedParts(sw).stream()
                        .map(day -> day.stream().filter(n -> !deleted.contains(n)).toList()).toList());
            }
        }
    }

    /** 이름이 바뀐 파트를 쓰는 근무·직원·고정 근무·템플릿의 이름도 같이 바꾼다(교환도 안전하게 한 번에). */
    private void propagateRenames(Integer storeId, Map<String, String> renames) {
        for (Schedule s : scheduleRepository.findByStoreAndPartIn(storeId, renames.keySet())) {
            s.setPart(renames.get(s.getPart()));
        }
        for (StoreWorker sw : storeWorkerRepository.findByStoreId(storeId)) {
            if (sw.getPart() != null && renames.containsKey(sw.getPart())) {
                sw.setPart(renames.get(sw.getPart()));
            }
            if (sw.getFixedParts() != null) {
                List<List<String>> fixed = StaffPrefs.fixedParts(sw).stream()
                        .map(day -> day.stream().map(n -> renames.getOrDefault(n, n)).toList()).toList();
                StaffPrefs.setFixedParts(sw, fixed);
            }
        }
        for (ScheduleTemplate t : scheduleTemplateRepository.findByStoreIdOrderByIdAsc(storeId)) {
            List<ScheduleTemplateDto.Shift> shifts = ScheduleTemplateDto.parse(t.getPatternJson()).stream()
                    .map(x -> x.part() != null && renames.containsKey(x.part())
                            ? new ScheduleTemplateDto.Shift(x.dayIndex(), x.staffId(), x.startMin(), x.endMin(),
                            renames.get(x.part()), x.breakMin(), x.kind(), x.timeTbd())
                            : x)
                    .toList();
            t.setPatternJson(ScheduleTemplateDto.toJson(shifts));
        }
    }

    /** PUT /stores/{storeId}/parts/needs — 사장님. */
    public List<StorePartDto.Response> updatePartNeeds(Integer userId, Integer storeId, List<Integer> needs) {
        storeRepository.findByIdForUpdate(storeId);
        access.requireOwner(userId, storeId);
        if (needs.size() != storePartRepository.findByStoreIdOrderByStartMinAsc(storeId).size()) {
            throw ApiException.badRequest("INVALID_NEED", "필요 인원 개수가 파트 수와 달라요. 새로고침 후 다시 저장해 주세요");
        }
        return applyNeeds(storeId, needs).stream().map(StorePartDto.Response::from).toList();
    }

    /** store.parts 순서(시작 시각순)대로 need 갱신. 인덱스가 모자라면 무시, 0~99 밖은 400. 다른 서비스도 사용한다. */
    public List<StorePart> applyNeeds(Integer storeId, List<Integer> needs) {
        List<StorePart> parts = storePartRepository.findByStoreIdOrderByStartMinAsc(storeId);
        for (int i = 0; i < parts.size() && i < needs.size(); i++) {
            Integer need = needs.get(i);
            if (need == null || need < 0) {
                throw ApiException.badRequest("INVALID_NEED", "필요 인원은 0 이상이어야 해요");
            }
            if (need > MAX_NEED) {
                throw ApiException.badRequest("INVALID_NEED", "필요 인원은 " + MAX_NEED + "명 이하여야 해요");
            }
            parts.get(i).setNeed(need);
        }
        return parts;
    }

    /**
     * POST /stores/join-requests — 코드는 대소문자·하이픈 무시.
     * 부수효과: StoreWorker(PENDING, 첫 파트, 최저임금, requestedAt=now) 생성 + 사장님에게 JOIN 알림.
     * 결과(JoinResult)에 따라 컨트롤러가 REQUESTED 201, 나머지 200 으로 응답한다(FE 가 본문 result 로 분기).
     * 동시 신청 직렬화: 첫 문장에서 코드로 매장 행을 잠근다. 잠금 전에 다른 읽기를 하면 그 스냅샷 때문에
     * 앞 신청이 만든 PENDING 행이 안 보여 중복 행이 생긴다.
     */
    public StoreDetailDto.JoinResponse requestJoin(Integer userId, String code) {
        String normalized = code == null ? "" : code.toUpperCase().replaceAll("[^A-Z0-9]", "");
        // 매장 코드는 항상 XXXX-XXXX(Store.newCode). code 컬럼 유니크 인덱스로 한 행만 잠근다.
        Store store = normalized.length() != 8 ? null : storeRepository
                .findByCodeForUpdate(normalized.substring(0, 4) + "-" + normalized.substring(4)).orElse(null);
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
        sw.setPart(parts.stream().filter(p -> !p.isFlex()).map(StorePart::getName).findFirst().orElse(null));
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

    /** 직원·가입 대기자에게 주는 매장 정보: 코드·인건비 추이 없음. */
    public StoreDetailDto.Response detail(Store store, boolean isOwner) {
        StoreDetailDto.Response full = detail(store);
        return isOwner ? full : full.withoutOwnerData();
    }

    /** 매장 + 파트 + 확정된 월별 인건비(이번 달 이전, Payroll 합계). 사장님용. */
    public StoreDetailDto.Response detail(Store store) {
        List<StorePart> parts = storePartRepository.findByStoreIdOrderByStartMinAsc(store.getId());
        Map<String, Integer> monthlyTotals = new LinkedHashMap<>();
        String current = YearMonth.from(TimeUtil.today()).toString();
        for (Object[] row : payrollRepository.sumGrossByMonthBefore(store.getId(), current)) {
            monthlyTotals.put((String) row[0], row[1] == null ? 0 : ((Number) row[1]).intValue());
        }
        long active = storeWorkerRepository.countByStoreIdAndStatus(store.getId(), StoreWorker.Status.ACTIVE);
        return StoreDetailDto.Response.from(store, parts, monthlyTotals, store.effectiveUnderFive(active));
    }
}
