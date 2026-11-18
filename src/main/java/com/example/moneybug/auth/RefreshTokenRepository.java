package com.example.moneybug.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** refresh 회전용: 같은 토큰이 동시에 오면 뒤 요청은 앞 요청이 지운 뒤를 읽어 빈 결과를 받는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    void deleteByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from RefreshToken r where r.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("delete from RefreshToken r where r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
