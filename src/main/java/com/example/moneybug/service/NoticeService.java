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

    private final NoticeRepository noticeRepository;
    private final NoticeReadRepository noticeReadRepository;
    private final StoreWorkerRepository storeWorkerRepository;
    private final StoreAccess access;
    private final StaffAssembler staffAssembler;
    private final NotificationPublisher notifier;

    /** GET /notices — 고정 먼저 -> 최신순. 사장님은 readBy/unreadCount, 직원은 isRead 와 unreadNoticeCount. */
    @Transactional(readOnly = true)
    public NoticeViewDto.ListResponse list(Integer userId, Integer storeId) {
        Store store = access.requireOwnerOrStaff(userId, storeId);
        boolean owner = access.isOwner(store, userId);
        Integer myStaffId = owner ? null : access.activeStaff(userId, storeId).map(StoreWorker::getId).orElse(null);
        Set<Integer> activeIds = activeStaffIds(storeId);

        Map<Integer, Map<Integer, LocalDateTime>> readsByNotice = new HashMap<>();
        for (NoticeRead nr : noticeReadRepository.findByNoticeStoreId(storeId)) {
            readsByNotice.computeIfAbsent(nr.getNotice().getId(), k -> new HashMap<>())
                    .put(nr.getStoreWorker().getId(), nr.getReadAt());
        }

        List<NoticeViewDto.View> items = new ArrayList<>();
        int unread = 0;
        for (Notice n : noticeRepository.findByStoreIdOrderByPinnedDescCreatedAtDescIdDesc(storeId)) {
            NoticeViewDto.View v = view(n, readsByNotice.getOrDefault(n.getId(), Map.of()), owner, myStaffId, activeIds);
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
        return view(n, reads, owner, myStaffId, activeStaffIds(storeId));
    }

    /**
     * POST /notices — 사장님. 제목·내용이 trim 후 비면 400. required 생략 시 store.noticeRequiredDefault.
     * 부수효과: 재직 직원 전원에게 NOTICE — 필독이면 "새 필독 공지", 아니면 "새 공지"(body = 제목).
     */
    public NoticeViewDto.View publish(Integer userId, Integer storeId, NoticeDto.Request request) {
        Store store = access.requireOwner(userId, storeId);
        if (request.getTitle() == null || request.getTitle().isBlank()
                || request.getContent() == null || request.getContent().isBlank()) {
            throw ApiException.badRequest("NOTICE_REQUIRED_FIELDS", "제목과 내용을 입력해 주세요");
        }
        Notice notice = request.toEntity(store);
        notice.setTitle(request.getTitle().trim());
        notice.setContent(request.getContent().trim());
        noticeRepository.save(notice);

        notifier.sendToActiveStaff(store, null, Notification.Type.NOTICE,
                notice.isRequired() ? "새 필독 공지" : "새 공지", notice.getTitle(), notice.getId());
        return view(notice, Map.of(), true, null, activeStaffIds(storeId));
    }

    /** POST /notices/{id}/read — 직원. 이미 읽었으면 변경 없음(멱등). */
    public void markRead(Integer userId, Integer storeId, Integer noticeId) {
        StoreWorker me = access.requireActiveStaff(userId, storeId);
        Notice n = find(storeId, noticeId);
        if (!noticeReadRepository.existsByNoticeIdAndStoreWorkerId(n.getId(), me.getId())) {
            saveRead(n, me);
        }
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
        List<NoticeRead> reads = noticeReadRepository.findByNoticeId(n.getId());
        Set<Integer> readIds = reads.stream().map(r -> r.getStoreWorker().getId()).collect(Collectors.toSet());
        return new NoticeViewDto.ReadStatusResponse(
                reads.stream().map(r -> new NoticeViewDto.ReadEntry(r.getStoreWorker().getId(), r.getReadAt())).toList(),
                unreadStaff(storeId, readIds).stream().map(staffAssembler::toDto).toList());
    }

    /** POST /notices/{id}/remind — 사장님. 안 읽은 직원에게 NOTICE "필독 공지를 확인해 주세요", remindedAt=now. */
    public NoticeViewDto.RemindResponse remind(Integer userId, Integer storeId, Integer noticeId) {
        Store store = access.requireOwner(userId, storeId);
        Notice n = find(storeId, noticeId);
        Set<Integer> readIds = noticeReadRepository.findByNoticeId(n.getId()).stream()
                .map(r -> r.getStoreWorker().getId()).collect(Collectors.toSet());
        List<StoreWorker> targets = unreadStaff(storeId, readIds);
        for (StoreWorker sw : targets) {
            notifier.sendToStaff(store, sw, Notification.Type.NOTICE, "필독 공지를 확인해 주세요", n.getTitle(), n.getId());
        }
        n.setRemindedAt(TimeUtil.now());
        return new NoticeViewDto.RemindResponse(targets.size());
    }

    // ------------------------------------------------------------------ 내부

    private NoticeViewDto.View view(Notice n, Map<Integer, LocalDateTime> readBy, boolean owner, Integer myStaffId,
                                    Set<Integer> activeIds) {
        if (owner) {
            int unreadCount = (int) activeIds.stream().filter(id -> !readBy.containsKey(id)).count();
            return NoticeViewDto.View.from(n, readBy, true, unreadCount);
        }
        // FE 계약: readBy 는 항상 맵. 직원에게는 본인 읽음 기록만 준다(다른 직원 읽음 여부는 숨김).
        Map<Integer, LocalDateTime> mine = myStaffId != null && readBy.containsKey(myStaffId)
                ? Map.of(myStaffId, readBy.get(myStaffId)) : Map.of();
        return NoticeViewDto.View.from(n, mine, !mine.isEmpty(), null);
    }

    private Notice find(Integer storeId, Integer noticeId) {
        return noticeRepository.findByIdAndStoreId(noticeId, storeId)
                .orElseThrow(() -> ApiException.notFound("공지를 찾을 수 없어요"));
    }

    private void saveRead(Notice n, StoreWorker sw) {
        NoticeRead read = new NoticeRead();
        read.setNotice(n);
        read.setStoreWorker(sw);
        read.setReadAt(TimeUtil.now());
        noticeReadRepository.save(read);
    }

    private Set<Integer> activeStaffIds(Integer storeId) {
        return storeWorkerRepository.findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE).stream()
                .map(StoreWorker::getId).collect(Collectors.toSet());
    }

    private List<StoreWorker> unreadStaff(Integer storeId, Set<Integer> readIds) {
        return storeWorkerRepository.findByStoreIdAndStatus(storeId, StoreWorker.Status.ACTIVE).stream()
                .filter(sw -> !readIds.contains(sw.getId())).toList();
    }
}
