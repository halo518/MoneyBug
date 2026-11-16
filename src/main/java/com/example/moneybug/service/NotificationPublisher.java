package com.example.moneybug.service;

import com.example.moneybug.entity.Notification;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.NotificationRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 알림 생성 (설계서 §5). 알림은 쓰기 API의 부수효과로 서버가 만든다. */
@Component
@RequiredArgsConstructor
public class NotificationPublisher {

    private final NotificationRepository notificationRepository;
    private final StoreWorkerRepository storeWorkerRepository;

    public void send(Store store, User to, Notification.Type type, String title, String body, Integer refId) {
        Notification n = new Notification();
        n.setStore(store);
        n.setToUser(to);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        n.setRefId(refId);
        notificationRepository.save(n);
    }

    public void sendToStaff(Store store, StoreWorker staff, Notification.Type type, String title, String body,
                            Integer refId) {
        send(store, staff.getWorker(), type, title, body, refId);
    }

    public void sendToOwner(Store store, Notification.Type type, String title, String body, Integer refId) {
        send(store, store.getEmployer(), type, title, body, refId);
    }

    /** 재직 직원 전원에게. exceptUserId 가 null 이 아니면 그 사용자는 제외한다. */
    public int sendToActiveStaff(Store store, Integer exceptUserId, Notification.Type type, String title,
                                 String body, Integer refId) {
        int sent = 0;
        for (StoreWorker sw : storeWorkerRepository.findByStoreIdAndStatus(store.getId(), StoreWorker.Status.ACTIVE)) {
            if (exceptUserId != null && sw.getWorker().getId().equals(exceptUserId)) {
                continue;
            }
            sendToStaff(store, sw, type, title, body, refId);
            sent++;
        }
        return sent;
    }
}
