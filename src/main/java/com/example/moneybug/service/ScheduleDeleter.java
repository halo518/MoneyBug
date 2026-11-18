package com.example.moneybug.service;

import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Schedule;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.SubstituteApplicant;
import com.example.moneybug.entity.SubstituteRequest;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.ScheduleRepository;
import com.example.moneybug.repository.SubstituteApplicantRepository;
import com.example.moneybug.repository.SubstituteRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 근무 삭제 + 딸린 대타 요청 정리. 진행 중 대타 요청은 CANCELLED 로 바꾸고 요청자·지원자에게
 * "대타 요청이 취소됐어요"를 알린 뒤, FK 무결성을 위해 그 근무의 대타 요청·지원자 행을 함께 지운다.
 * 요청 행이 사라지므로 알림 refId 는 비운다. (출근 기록 검사는 호출하는 쪽의 책임이다.)
 */
@Component
@RequiredArgsConstructor
public class ScheduleDeleter {

    private final ScheduleRepository scheduleRepository;
    private final SubstituteRequestRepository substituteRequestRepository;
    private final SubstituteApplicantRepository substituteApplicantRepository;
    private final NotificationPublisher notifier;

    public void delete(Schedule schedule) {
        List<SubstituteRequest> requests = substituteRequestRepository.findByScheduleId(schedule.getId());
        for (SubstituteRequest r : requests) {
            if (r.isPending()) {
                r.setStatus(SubstituteRequest.Status.CANCELLED);
                notifyCancelled(schedule, r);
            }
            substituteApplicantRepository.deleteByRequestId(r.getId());
        }
        substituteRequestRepository.deleteAll(requests);
        scheduleRepository.delete(schedule);
    }

    private void notifyCancelled(Schedule schedule, SubstituteRequest r) {
        Store store = schedule.getStoreWorker().getStore();
        Map<Integer, User> to = new LinkedHashMap<>();
        to.put(r.getRequester().getId(), r.getRequester());
        for (SubstituteApplicant a : substituteApplicantRepository.findByRequestId(r.getId())) {
            to.putIfAbsent(a.getApplicant().getId(), a.getApplicant());
        }
        String body = TimeUtil.label(schedule) + " · 근무가 삭제됐어요";
        for (User u : to.values()) {
            notifier.send(store, u, Notification.Type.SUB, "대타 요청이 취소됐어요", body, null);
        }
    }
}
