# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

MoneyBug (돈벌래) backend: Spring Boot 4 + Jackson 3 (`tools.jackson.*`; annotations are still `com.fasterxml.jackson.annotation`), Java 17, Gradle wrapper (`bash ./gradlew` on Linux, `.\gradlew.bat` on Windows), MySQL, JWT. `api_design.md` is the original API/DB design; the code has moved on from it (see "FE contract").

Clients: the Flutter app/web github.com/ruraeru/donBug (`donBug_FE`). Deployment: github.com/ruraeru/donBug_deploy (Docker: Caddy + this app + MySQL 8.4 at `https://donbug.ruraeru.xyz`); see its README.md for env vars and ops.

## FE contract (most important rule)

The Flutter models (`donBug_FE/lib/data/models.dart`) are the contract; the backend adapts to them, not the other way round.
- `GET /api/v1/me/state` (`StateService`, `AppStateDto`) returns the FE `AppState` JSON as-is: `{v, nextId, session, persons, stores, staff, shifts, attendance, subs, notices, notifications, schedules, templates}`. `AppStateDto.FE_STATE_VERSION` must equal FE `AppState.version` (now 4); bump both together or old apps refuse the state.
- Ids are strings in JSON: `config/FeIdJsonModule` serializes numeric properties named `id`/`*Id`/`*Ids` as strings (except `nextId`). Requests accept numbers.
- Enums serialize as the Dart `.name` (lower camel, `entity/EnumJson.camel`) and parse case/underscore-insensitively (`EnumJson.parse`). Query params too (`config/EnumQueryConverter`).
- DTO field names follow the FE via `@JsonProperty` (e.g. `hourlyWage` → `wage`, `workDate` → `date`). `dto/FeContractJsonTest` checks the shapes.

## Runtime configuration

`application.yml` includes `application-secret.yml` (`spring.profiles.include: secret`, gitignored; copy `application-secret.sample.yml`). Every value is `${ENV_VAR:default}`; Docker sets env vars.
- Secrets: `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, `JWT_SECRET` (≥ 32 bytes, required), `KAKAO_REST_API_KEY`, `KAKAO_CLIENT_SECRET`.
- `application.yml`: `KAKAO_REDIRECT_URI`, `KAKAO_APP_ID`, `FRONTEND_URL` (web redirect after Kakao login), `FRONTEND_ORIGIN` (CORS), `DEV_LOGIN_ENABLED` (default **false**; set true for local dev), `SWAGGER_ENABLED` (default false), `SHOW_SQL`/`SQL_BIND_LOG=TRACE` (SQL logging, off by default), `TEST_LOGIN_TOKEN`, `ANTHROPIC_API_KEY`/`AI_MODEL`, `LLM_BASE_URL`/`LLM_MODEL`/`LLM_API_KEY`, `app.upload-dir` (env `APP_UPLOADDIR`).
- `spring.jpa.hibernate.ddl-auto=update`: entities drive the schema, no migrations. New columns must be nullable or have DB defaults, and code must read null as the default (existing rows). MySQL tables use `utf8mb4_unicode_ci`.

## Auth

`auth/AuthService`: Kakao access token → `KakaoClient.fetchUser` → upsert `User` by `kakaoId` → our JWT access token (`auth/jwt`, stateless `SecurityConfig` + `JwtAuthenticationFilter`) + opaque refresh token (only SHA-256 stored, rotated on `/api/auth/refresh`).
- Web: `GET /api/auth/kakao/login` → Kakao → `GET /api/auth/kakao/callback` (state in an HttpOnly cookie) → 302 to `{FRONTEND_URL}/#/auth?access=..&refresh=..` (or `?error=`).
- App: same flow with `?app=true` (cookie `kakao_app`) → 302 to `donbug://auth?access=..&refresh=..`.
- `POST /api/auth/kakao/token {accessToken}`: Kakao SDK token. Also two bypasses: `test_token` when `DEV_LOGIN_ENABLED` (local only), and a secret `TEST_LOGIN_TOKEN` (16+ chars, constant-time compare) → test user "테스트알바".
- jjwt uses the `orgjson` serializer on purpose (Boot 4 ships Jackson 3).

## Structure (`src/main/java/com/example/moneybug`)

- `controller/` thin REST controllers under `/api/v1` (store-scoped ones under `/api/v1/stores/{storeId}`); `ApiExceptionHandler` maps `service/ApiException` to `{code, message, details}`.
- `service/` business logic. Highlights:
  - `StoreAccess` (owner/staff checks), `StateService` (aggregate state), `StoreService` (settings, parts CRUD with rename propagation, join by 8-char code `XXXX-XXXX`), `StaffService` (`fixedParts`/`workHours` via `StaffPrefs` JSON columns).
  - Schedules: `ShiftService` (single shifts, legacy plans/auto-fill used by the mobile app), `MonthScheduleService` (monthly schedules, bulk save `PUT /schedules/{month}/shifts`, autofill, confirm, delete, templates, AI generation job), `FixedShiftPlanner` (part-timer fixed parts, employee work hours, legal breaks), `ScheduleAiService` (Ollama-compatible prompt/sanitize, ids only, no names), `ScheduleJobStore`, `TemplatePattern`, `OverlapChecker`, `ScheduleDeleter`, `util/Holidays` (2026 only).
  - Attendance: `AttendanceService` + `QrTokenService` (30 s rotating HMAC token, clock-in requires it).
  - Documents: `DocumentService` + `FileStorage` (health cert photos, signature images; path-escape checks).
  - `AiLaborService` (Anthropic Messages API via `RestClient.builder()`, 20/h per user, 503 `AI_DISABLED` without key).
  - Live refresh: `StoreChangeHub` + `config/StoreChangeInterceptor` → `GET /api/v1/me/changes?since=` long poll (`DeferredResult`, 25 s). Successful non-GET requests on `/stores/{storeId}/**` bump that store; services bump directly for work outside a request (AI job, join request).
- `entity/`, `repository/`, `dto/` as usual.

## Gotchas

- **Check the app actually starts** after changing beans or config: unit tests don't load the context, and a startup failure makes every `/api` call 502 in production. No MySQL here, so run the jar against a dummy DB:
  ```sh
  bash ./gradlew bootJar -x test -q && JWT_SECRET=0123456789012345678901234567890123456789 timeout 60 java -jar build/libs/MoneyBug-0.0.1-SNAPSHOT.jar --spring.config.additional-location=file:src/main/resources/application-secret.sample.yml --server.port=18099 --spring.datasource.url='jdbc:mysql://127.0.0.1:1/x' --spring.datasource.username=x --spring.datasource.password=x --spring.sql.init.mode=never --spring.jpa.hibernate.ddl-auto=none --spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false --spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect --spring.datasource.hikari.initialization-fail-timeout=-1 2>&1 | grep -E "Started |APPLICATION FAILED|Caused by"
  ```
  It must print `Started MoneyBugApplication`.
- Boot 4 has no `RestClient.Builder` bean (needs `spring-boot-restclient`); build clients with `RestClient.builder()`. A bean with two constructors needs `@Autowired` on the main one.
- `DeferredResult` endpoints need `DispatcherType.ASYNC` permitted in `SecurityConfig` (already done).
- Bulk month save receives every shift of the month, including past shifts of staff who left; keep them if the worker is unchanged. Shifts with attendance are never deleted or moved (`skipped`).
- Kakao/AI/LLM keys and prompts must not be logged.

## Tests

No DB in tests: plain JUnit + Mockito, or `@WebMvcTest` slices with `@MockitoBean` repositories (e.g. `controller/MeChangesLongPollTest`, `controller/SaveShiftsBodyTest`). There is no `@SpringBootTest` context test (it would need MySQL). Run `bash ./gradlew test`.

## Git

Feature branch → PR to `develop` → merge commit, then delete the feature branch. Release: PR `develop` → `main` → `deploy`. Only `develop`, `main`, `deploy` stay. donBug_deploy `develop` pins this repo's `develop` (test deploys), its `main` pins `deploy` (production); bump the pointer there after merging.
