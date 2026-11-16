package com.example.moneybug.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 사용자(설계서의 Person).
 * [변경] 설계서 §1.2: 역할(사장님/알바생)은 서버에 저장하지 않고 관계로 결정한다.
 *   - 사장님 = Store.employer == me, 알바생 = StoreWorker(status=ACTIVE) 소속
 *   그래서 role 컬럼/enum 을 제거했다.
 * [변경] 설계서 §3.1/§8: email, profileImage, fullName, 보건증(사람 단위) 필드를 추가했다.
 * [변경] 테이블명 user -> users (설계서 §8, 타 DB 예약어 충돌 방지).
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Integer id;

    @Column(name = "kakao_id", nullable = false, unique = true, length = 100)
    private String kakaoId;

    // 짧은 이름 (아바타 글자 = 첫 글자)
    @Column(nullable = false, length = 50)
    private String name;

    // [추가] 전체 이름
    @Column(name = "full_name", length = 50)
    private String fullName;

    // [추가] 카카오 필수 동의 항목
    @Column(length = 100)
    private String email;

    // [추가] 프로필 사진 URL (카카오 프로필 이미지)
    @Column(name = "profile_image", length = 500)
    private String profileImage;

    // [변경] NOT NULL -> NULL 허용. 연락처는 카카오 동의 화면에서 '선택' 항목이다.
    @Column(length = 20)
    private String contact;

    // [추가] 연락처 제공 동의 여부 (대타 알림 연락용, 선택)
    @Column(name = "phone_consent", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean phoneConsent = false;

    // ---- [추가] 보건증 (설계서 §3.1: 보건증은 '사람' 단위) ----

    @Column(name = "cert_expiry")
    private LocalDate certExpiry;

    // 만료 며칠 전에 알릴지, 쉼표 구분 문자열 (예: "30,14,7,0")
    @Column(name = "cert_alert_days", length = 50)
    private String certAlertDays;

    // 보건증 사진 저장 경로/URL (certHasPhoto 는 이 값의 존재 여부로 계산)
    @Column(name = "cert_photo_url", length = 500)
    private String certPhotoUrl;
}
