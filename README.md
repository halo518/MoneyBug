# MoneyBug

MoneyBug 백엔드 (Spring Boot). API / DB 설계는 [`api_design.md`](api_design.md) 참고.

## 기술 스택

- Java 17, Spring Boot 4.1 (Gradle Wrapper)
- Spring Data JPA + MySQL
- Spring Security + JWT (jjwt), 카카오 로그인
- Swagger UI (springdoc-openapi)

## 사전 준비

| 항목 | 버전 / 비고 |
| --- | --- |
| JDK | 17 |
| MySQL | 로컬 `localhost:3306` 에 실행 중이어야 함 |
| IDE | IntelliJ IDEA 권장 (Lombok 플러그인, Annotation Processing 활성화) |

## 환경 설정

### 1. 프로젝트 받기

```bash
git clone https://github.com/halo518/MoneyBug.git
cd MoneyBug
```

### 2. DB 생성

MySQL 에 `moneybug` 데이터베이스만 만들어 두면 된다. 테이블은 앱 실행 시 엔티티 기준으로 자동 생성된다 (`ddl-auto: update`).

```sql
CREATE DATABASE moneybug DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 3. 비밀 설정 파일 만들기

DB 접속 정보, JWT 시크릿, 카카오 키 같은 민감한 값은 `application-secret.yml` 에 둔다.
이 파일은 `.gitignore` 대상이라 깃허브에 올라가지 않으므로, 템플릿을 복사해서 직접 만든다.

```powershell
# Windows PowerShell
Copy-Item src/main/resources/application-secret.sample.yml src/main/resources/application-secret.yml
```

```bash
# macOS / Linux
cp src/main/resources/application-secret.sample.yml src/main/resources/application-secret.yml
```

복사한 `application-secret.yml` 에서 아래 값을 실제 값으로 바꾼다.

| 키 | 설명 |
| --- | --- |
| `spring.datasource.url` | MySQL 접속 URL (기본값 그대로 써도 됨) |
| `spring.datasource.username` / `password` | 본인 로컬 MySQL 계정 |
| `jwt.secret` | 32바이트 이상의 랜덤 문자열 (짧으면 앱이 시작되지 않음) |
| `kakao.rest-api-key` / `client-secret` | 카카오 디벨로퍼스 앱의 REST API 키 / Client Secret |

> 각 값은 `${환경변수:기본값}` 형태라서, 환경변수(`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `KAKAO_REST_API_KEY`, `KAKAO_CLIENT_SECRET`)를 설정하면 파일 값보다 환경변수가 우선한다.

`jwt.secret` 용 랜덤 문자열 생성 예시:

```powershell
# Windows PowerShell
$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

```bash
# macOS / Linux
openssl rand -base64 48
```

### 4. (선택) 그 외 환경변수

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `KAKAO_REDIRECT_URI` | `http://localhost:8080/api/auth/kakao/callback` | 웹 카카오 로그인 콜백 주소 |
| `KAKAO_APP_ID` | (없음) | 설정하면 다른 카카오 앱에서 발급된 모바일 토큰을 거부 |
| `DEV_LOGIN_ENABLED` | `true` | 개발용 로그인 우회. **운영 환경에서는 반드시 `false`** |

## 실행

```powershell
# Windows
.\gradlew.bat bootRun
```

```bash
# macOS / Linux
./gradlew bootRun
```

IntelliJ 에서는 `MoneyBugApplication` 을 바로 실행해도 된다.

- 서버: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html

## 개발용 로그인

`DEV_LOGIN_ENABLED` 가 `true` (기본값) 이면, 카카오 인증 없이 테스트 사용자로 로그인할 수 있다.

```
POST /api/auth/kakao/token
{ "accessToken": "test_token" }
```

응답으로 받은 access token 을 Swagger UI 의 **Authorize** 버튼에 넣으면 인증이 필요한 API 를 호출할 수 있다.

## 테스트

```powershell
.\gradlew.bat test
```

## 설정 파일 구조

```
src/main/resources/
├── application.yml                  # 공통 설정 (커밋됨), spring.profiles.include: secret
├── application-secret.yml           # 실제 비밀 값 (커밋 금지, .gitignore)
└── application-secret.sample.yml    # 비밀 설정 템플릿 (커밋됨)
```
