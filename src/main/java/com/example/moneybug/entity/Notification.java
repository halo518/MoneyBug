package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** [신규] 알림 (설계서 §3.9, §5). 쓰기 API의 부수효과로 서버가 생성한다. */
@Entity
@Table(name = "notification", indexes = {
        @Index(name = "idx_notification_to_store_created", columnList = "to_user_id, store_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    // sub=근무/대타, notice=공지, cert=보건증, pay=근태/급여, join=가입, contract=계약서
    public enum Type {
        SUB, NOTICE, CERT, PAY, JOIN, CONTRACT;

        @com.fasterxml.jackson.annotation.JsonValue
        public String json() { return EnumJson.camel(this); }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static Type fromJson(String v) { return EnumJson.parse(Type.class, v); }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_user_id", nullable = false)
    private User toUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // 'read' 는 SQL 예약어라 컬럼명을 is_read 로 둔다.
    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    // type별 참조 id (sub->대타요청, notice->공지, join/cert->staff ...)
    @Column(name = "ref_id")
    private Integer refId;
}
