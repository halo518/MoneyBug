# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

MoneyBug backend (Spring Boot, Gradle wrapper; on Windows PowerShell use `.\gradlew.bat`). `api_design.md` in the project root is the API and DB design reference.

## Runtime configuration

`src/main/resources/application.properties` points at a local MySQL: `jdbc:mysql://localhost:3306/moneybug` (timezone `Asia/Seoul`, user `root`). A MySQL instance with a `moneybug` database must be running, otherwise the app and the `@SpringBootTest` context-load test fail at startup (the test has no DB override or embedded DB).

`spring.jpa.hibernate.ddl-auto=update` means entity classes drive the schema; there are no migration scripts. SQL logging is on (`show-sql`, `format_sql`).

## Auth architecture

Kakao login for web and mobile converges in `auth/AuthService`: get a Kakao access token -> `KakaoClient.fetchUser` -> upsert `entity/User` by `kakaoId` -> issue our own JWT access token (`auth/jwt/JwtProvider`, stateless `SecurityConfig` + `JwtAuthenticationFilter`) and an opaque refresh token (only its SHA-256 is stored in `RefreshToken`; rotated on `/api/auth/refresh`).
- Mobile: `POST /api/auth/kakao/token` with the Kakao SDK access token.
- Web: `GET /api/auth/kakao/login` -> Kakao -> `GET /api/auth/kakao/callback` (state kept in an HttpOnly cookie).

Required env vars: `JWT_SECRET` (>= 32 bytes; the app won't start without it), `KAKAO_REST_API_KEY`, `KAKAO_CLIENT_SECRET`, `KAKAO_REDIRECT_URI`, optional `KAKAO_APP_ID` (rejects mobile tokens from other Kakao apps). `@SpringBootTest` tests therefore also need `JWT_SECRET` set. jjwt uses the `orgjson` serializer on purpose (Boot 4 ships Jackson 3).

The comments in `application.properties` are Korean and appear mojibake'd (`?`) in the file; preserve the file's encoding when editing it.
