# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

MoneyBug backend (Spring Boot, Gradle wrapper; on Windows PowerShell use `.\gradlew.bat`). `api_design.md` in the project root is the API and DB design reference.

## Runtime configuration

Config is `src/main/resources/application.yml`, which pulls in `application-secret.yml` via `spring.profiles.include: secret`. The secret file (DB url/username/password, `jwt.secret`, Kakao key/secret) is gitignored; `application-secret.sample.yml` is the committed template to copy from. Secret values are `${ENV_VAR:default}`, so env vars still override them. The datasource points at a local MySQL: `jdbc:mysql://localhost:3306/moneybug` (timezone `Asia/Seoul`, user `root`). A MySQL instance with a `moneybug` database must be running, otherwise the app and the `@SpringBootTest` context-load test fail at startup (the test has no DB override or embedded DB).

`spring.jpa.hibernate.ddl-auto=update` means entity classes drive the schema; there are no migration scripts. SQL logging is on (`show-sql`, `format_sql`).

## Auth architecture

Kakao login for web and mobile converges in `auth/AuthService`: get a Kakao access token -> `KakaoClient.fetchUser` -> upsert `entity/User` by `kakaoId` -> issue our own JWT access token (`auth/jwt/JwtProvider`, stateless `SecurityConfig` + `JwtAuthenticationFilter`) and an opaque refresh token (only its SHA-256 is stored in `RefreshToken`; rotated on `/api/auth/refresh`).
- Mobile: `POST /api/auth/kakao/token` with the Kakao SDK access token.
- Web: `GET /api/auth/kakao/login` -> Kakao -> `GET /api/auth/kakao/callback` (state kept in an HttpOnly cookie).

Secrets come from `application-secret.yml` (or the env vars it references): `JWT_SECRET` (>= 32 bytes; the app won't start without a `jwt.secret`), `KAKAO_REST_API_KEY`, `KAKAO_CLIENT_SECRET`. Non-secret env vars in `application.yml`: `KAKAO_REDIRECT_URI`, optional `KAKAO_APP_ID` (rejects mobile tokens from other Kakao apps). jjwt uses the `orgjson` serializer on purpose (Boot 4 ships Jackson 3).
