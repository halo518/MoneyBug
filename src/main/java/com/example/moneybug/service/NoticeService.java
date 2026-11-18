package com.example.moneybug.service;

import com.example.moneybug.dto.NoticeDto;
import com.example.moneybug.dto.NoticeViewDto;
import com.example.moneybug.entity.Notice;
import com.example.moneybug.entity.NoticeRead;
import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.repository.NoticeReadRepository;
import com.example.moneybug.repository.NoticeRepository;
import com.example.moneybug.repository.NotificationRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 공지 (설계서 §4.8). 읽음은 직원(StoreWorker) 기준이며 사장님은 읽음 현황만 본다. */
@Service
@Transactional
@RequiredArgsConstructor
public class NoticeService {

    private static final int TITLE_MAX = 200;   // notice.title VARCHAR(200)
    private static final int CONTENT_MAX = 5000;
    private static final java.time.Duration REMIND_COOLDOWN = java.time.Duration.ofHours(1);

    private final NoticeRepository noticeRepository;
    private final NoticeReadRepository noticeReadRepository;
    private final NotificationRepository notificationRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final StoreAccess access;
    private final StaffAssembler staffAssembler;
    private final NotificationPublisher notifier;

    /** GET /notices — 고정 먼저 -> 최신순. 사장님은 readBy/unreadCount, 직원은 isRead 와 unreadNoticeCount. */
    @Transactional(readOnly = true)
    public NoticeViewDto.ListResponse list(Integer userId, Integer storeId) {
        Store store = access.requireOwnerOrStaff(userId, storeId);
        boolean owner = access.isOwner(store, userId);
        StoreWorker meSw = owner ? null : access.activeStaff(userId, storeId).orElse(null);
        Integer myStaffId = meSw == null ? null : meSw.getId();
        Map<Integer, StoreWorker> active = activeStaff(storeId);

        Map<Integer, Map<Integer, LocalDateTime>> readsByNotice = new HashMap<>();
        for (NoticeRead nr : noticeReadRepository.findByNoticeStoreId(storeId)) {
            readsByNotice.computeIfAbsent(nr.getNotice().getId(), k -> new HashMap<>())
                    .put(nr.getStoreWorker().getId(), nr.getReadAt());
        }

        List<NoticeViewDto.View> items = new ArrayList<>();
        int unread = 0;
        for (Notice n : noticeRepository.findByStoreIdOrderByPinnedDescCreatedAtDescIdDesc(storeId)) {
            NoticeViewDto.View v = view(n, readsByNotice.getOrDefault(n.getId(), Map.of()), owner, myStaffId, active);
            if (!owner && !v.isRead()) unread++;
            items.add(v);
        }
        return new NoticeViewDto.ListResponse(items, null, unread);
    }

    @Transactional(readOnly = true)
    public NoticeViewDto.View get(Integer userId, Integer storeId, Integer noticeId) {
        Store store = access.requireOwnerOrStaff(userId, storeId);
        boolean owner = access.isOwner(store, userId);
        Integer myStaffId = owner ? null : access.activeStaff(userId, storeId).map(StoreWorker::getId).orElse(null);
        Notice n = find(storeId, noticeId);
        Map<Integer, LocalDateTime> reads = noticeReadRepository.findByNoticeId(n.getId()).stream()
                .collect(Collectors.toMap(r -> r.getStoreWorker().getId(), NoticeRead::getReadAt, (a, b) -> a));
        return view(n, reads, owner, myStaffId, activeStaff(storeId));
    }

    /**
     * POST /notices — 사장님. 제목·내용이 trim 후 비면 400. required 생략 시 store.noticeRequiredDefault.
     * 부수효과: 재직 직원 전원에게 NOTICE — 필독이면 "새 필독 공지", 아니면 "새 공지"(body = 제목).
     */
    public NoticeViewDto.View publish(Integer userId, Integer storeId, NoticeDto.Request request) {
        Store store = access.requireOwner(userId, storeId);
        Notice notice = request.toEntity(store);
        applyText(notice, request);
        if (notice.getCategory() == null) notice.setCategory(Notice.Category.GENERAL);
        noticeRepository.save(notice);

        notifier.sendToActiveStaff(store, null, Notification.Type.NOTICE,
                notice.isRequired() ? "새 필독 공지" : "새 공지", notice.getTitle(), notice.getId());
        return view(notice, Map.of(), true, null, activeStaff(storeId));
    }

    /** PUT /notices/{id} — 사장님. 검증은 작성과 같다. 읽음 기록은 그대로 두고 알림도 다시 보내지 않는다. */
    public NoticeViewDto.View update(Integer userId, Integer storeId, Integer noticeId, NoticeDto.Request request) {
        access.requireOwner(userId, storeId);
        Notice n = find(storeId, noticeId);
        applyText(n, request);
        if (request.getCategory() != null) n.setCategory(request.getCategory());
        if (request.getRequired() != null) n.setRequired(request.getRequired());
        Map<Integer, LocalDateTime> reads = noticeReadRepository.findByNoticeId(n.getId()).stream()
                .collect(Collectors.toMap(r -> r.getStoreWorker().getId(), NoticeRead::getReadAt, (a, b) -> a));
        return view(n, reads, true, null, activeStaff(storeId));
    }

    /** DELETE /notices/{id} — 사장님. 읽음 기록과 그 공지를 가리키는 알림도 함께 지운다. */
    public void delete(Integer userId, Integer storeId, Integer noticeId) {
        access.requireOwner(userId, storeId);
        Notice n = find(storeId, noticeId);
        noticeReadRepository.deleteByNoticeId(n.getId());
        notificationRepository.deleteByTypeAndRefId(Notification.Type.NOTICE, n.getId());
        noticeRepository.delete(n);
    }

    /** POST /notices/{id}/read — 직원. 이미 읽었으면 변경 없음(멱등). */
    public void markRead(Integer userId, Integer storeId, Integer noticeId) {
        StoreWorker me = access.requireActiveStaff(userId, storeId);
        Notice n = find(storeId, noticeId);
        saveRead(n, me);
    }

    /** POST /notices/read-all — 직원. 안 읽은 공지를 모두 읽음 처리. */
    public void markAllRead(Integer userId, Integer storeId) {
        StoreWorker me = access.requireActiveStaff(userId, storeId);
        Set<Integer> readIds = noticeReadRepository.findByNoticeStoreId(storeId).stream()
                .filter(r -> r.getStoreWorker().getId().equals(me.getId()))
                .map(r -> r.getNotice().getId()).collect(Collectors.toSet());
        for (Notice n : noticeRepository.findByStoreIdOrderByPinnedDescCreatedAtDescIdDesc(storeId)) {
            if (!readIds.contains(n.getId())) saveRead(n, me);
        }
    }

    /** GET /notices/{id}/read-status — 사장님. 재직 직원 중 읽음 기록이 없는 사람이 unread. */
    @Transactional(readOnly = true)
    public NoticeViewDto.ReadStatusResponse readStatus(Integer userId, Integer storeId, Integer noticeId) {
        access.requireOwner(userId, storeId);
        Notice n = find(storeId, noticeId);
        Map<Integer, StoreWorker> active = activeStaff(storeId);
        // 퇴사자 읽음 기록은 빼고 재직 직원만 (read + unread = 대상 직원).
        List<NoticeRead> reads = noticeReadRepository.findByNoticeId(n.getId()).stream()
                .filter(r -> active.containsKey(r.getStoreWorker().getId())).toList();
        Set<Integer> readIds = reads.stream().map(r -> r.getStoreWorker().getId()).collect(Collectors.toSet());
        return new NoticeViewDto.ReadStatusResponse(
                reads.stream().map(r -> new NoticeViewDto.ReadEntry(r.getStoreWorker().getId(), r.getReadAt())).toList(),
                unreadStaff(n, storeId, readIds).stream().map(staffAssembler::toDto).toList());
    }

    /** POST /notices/{id}/remind — 사장님. 안 읽은 직원에게 NOTICE "필독 공지를 확인해 주세요", remindedAt=now. */
    public NoticeViewDto.RemindResponse remind(Integer userId, Integer storeId, Integer noticeId) {
        Store store = access.requireOwner(userId, storeId);
        // 동시 재알림이 모두 쿨다운을 통과하지 않도록 공지 행을 잠그고 나서 remindedAt 을 본다.
        Notice n = noticeRepository.findByIdAndStoreIdForUpdate(noticeId, storeId)
                .orElseThrow(() -> ApiException.notFound("공지를 찾을 수 없어요"));
        if (!n.isRequired()) {
            throw ApiException.conflict("NOT_REQUIRED", "필독 공지만 다시 알릴 수 있어요");
        }
        if (n.getRemindedAt() != null && n.getRemindedAt().plus(REMIND_COOLDOWN).isAfter(TimeUtil.now())) {
            throw ApiException.conflict("REMIND_TOO_SOON", "방금 알렸어요. 1시간 뒤에 다시 알릴 수 있어요");
        }
        Set<Integer> readIds = noticeReadRepository.findByNoticeId(n.getId()).stream()
                .map(r -> r.getStoreWorker().getId()).collect(Collectors.toSet());
        List<StoreWorker> targets = unreadStaff(n, storeId, readIds);
        for (StoreWorker sw : targets) {
            notifier.sendToStaff(store, sw, Notification.Type.NOTICE, "필독 공지를 확인해 주세요", n.getTitle(), n.getId());
        }
        n.setRemindedAt(TimeUtil.now());
        return new NoticeViewDto.RemindResponse(targets.size());
    }

    // ------------------------------------------------------------------ 내부

    /** 제목·내용을 다듬고 검사해서 공지에 넣는다 (작성·수정 공통). */
    private static void applyText(Notice notice, NoticeDto.Request request) {
        String title = request.getTitle() == null ? "" : request.getTitle().strip();
        String body = request.getContent() == null ? "" : request.getContent().strip();
        if (title.isEmpty() || body.isEmpty()) {
            throw ApiException.badRequest("NOTICE_REQUIRED_FIELDS", "제목과 내용을 입력해 주세요");
        }
        if (charCount(title) > TITLE_MAX) {
            throw ApiException.badRequest("NOTICE_TOO_LONG", "공지 제목은 " + TITLE_MAX + "자까지 쓸 수 있어요");
        }
        if (charCount(body) > CONTENT_MAX) {
            throw ApiException.badRequest("NOTICE_TOO_LONG", "공지 내용은 " + CONTENT_MAX + "자까지 쓸 수 있어요");
        }
        notice.setTitle(title);
        notice.setContent(body);
    }

    private NoticeViewDto.View view(Notice n, Map<Integer, LocalDateTime> readBy, boolean owner, Integer myStaffId,
                                    Map<Integer, StoreWorker> active) {
        if (owner) {
            // readBy 는 재직 직원만: readBy 수 + unreadCount = 대상 직원 수(가입 전 공지 제외)라서 FE 가 한 비율로 쓸 수 있다.
            Map<Integer, LocalDateTime> activeReads = new HashMap<>();
            readBy.forEach((id, at) -> { if (active.containsKey(id)) activeReads.put(id, at); });
            int unreadCount = (int) active.values().stream()
                    .filter(sw -> !readBy.containsKey(sw.getId()) && !beforeJoin(n, sw)).count();
            return NoticeViewDto.View.from(n, activeReads, true, unreadCount);
        }
        // FE 계약: readBy 는 항상 맵. 직원에게는 본인 읽음 기록만 준다(다른 직원 읽음 여부는 숨김).
        Map<Integer, LocalDateTime> mine = myStaffId != null && readBy.containsKey(myStaffId)
                ? Map.of(myStaffId, readBy.get(myStaffId)) : Map.of();
        // 가입 전에 올라온 공지는 읽은 것으로 본다(미확인 배지·현황에 안 잡힘).
        boolean read = !mine.isEmpty() || (myStaffId != null && active.containsKey(myStaffId)
                && beforeJoin(n, active.get(myStaffId)));
        return NoticeViewDto.View.from(n, mine, read, null);
    }

    /** 글자 수: 서로게이트 쌍(이모지)을 1자로 센다 — MySQL VARCHAR 길이와 같은 기준. */
    static int charCount(String s) {
        return s.codePointCount(0, s.length());
    }

    private Notice find(Integer storeId, Integer noticeId) {
        return noticeRepository.findByIdAndStoreId(noticeId, storeId)
                .orElseThrow(() -> ApiException.notFound("공지를 찾을 수 없어요"));
    }

    private void saveRead(Notice n, StoreWorker sw) {
        noticeReadRepository.insertIgnore(n.getId(), sw.getId(), TimeUtil.now());
    }

    /** 직원이 가입(승인)한 날보다 먼저 올라온 공지인가. */
    private static boolean beforeJoin(Notice n, StoreWorker sw) {
        return sw.getJoinedAt() != null && n.getCreatedAt() != null
                && n.getCreatedAt().toLocalDate().isBefore(sw.getJoinedAt());
    }

    private Map<Integer, StoreWorker> activeStaff(Integer storeId) {
        return storeWorkerRepository.findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE).stream()
                .collect(Collectors.toMap(StoreWorker::getId, sw -> sw, (a, b) -> a));
    }

    private List<StoreWorker> unreadStaff(Notice n, Integer storeId, Set<Integer> readIds) {
        return activeStaff(storeId).values().stream()
                .filter(sw -> !readIds.contains(sw.getId()) && !beforeJoin(n, sw)).toList();
    }
}
