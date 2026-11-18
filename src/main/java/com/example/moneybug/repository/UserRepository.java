package com.example.moneybug.repository;

import com.example.moneybug.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {

    // [수정] AuthService 의 카카오 로그인(upsert)에서 사용
    Optional<User> findByKakaoId(String kakaoId);

    /** 사용자 단위 직렬화용 행 잠금(출퇴근: 열린 출근 기록 1개 보장). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Integer id);

    /** 보건증 등록·만료 알림 직렬화용 행 잠금 (서로의 변경을 덮어쓰지 않게). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockForCert(@Param("id") Integer id);
}
