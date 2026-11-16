# 돈벌래(donBug) 백엔드 API 설계서

> 이 문서는 현재 프런트엔드 코드를 분석해서 **백엔드가 만들어야 할 API**를 역으로 정리한 것입니다.

## 0. 분석 범위와 전제

### 0.1 이 프로젝트에는 실제 HTTP 호출이 없다

요청하신 "HTML/JS/CSS"는 이 폴더에 없습니다. 이 프로젝트는 **Flutter 앱**(`lib/**/*.dart`)이고, `web/index.html`은 Flutter 부트스트랩 파일입니다. `http`·`dio`·`fetch` 호출도 하나도 없습니다.

프로토타입은 서버 대신 `AppStore`(`lib/data/app_store.dart`)가 **기기 안 JSON 한 덩어리**(SharedPreferences `donbug.state`)를 읽고 씁니다. 그래서 아래와 같이 대응시켜 API를 도출했습니다.

| 프런트 코드 | 이 문서에서의 의미 |
|---|---|
| `AppStore`의 액션 메서드(`clockIn`, `addShift`, `approveJoin` …) | **쓰기 API** (POST/PATCH/DELETE) |
| 화면 입력 폼(`TextEditingController`, `FieldBox`, 선택 위젯)과 검증 `showToast` | **요청 DTO**와 검증 규칙 |
| `AppStore`의 조회·파생값(`myShifts`, `payslip`, `laborTotal` …)과 화면 렌더 | **응답 DTO** (GET) |
| `_notify*()`가 만드는 `AppNotification` | 쓰기 API의 **부수효과(알림 생성)** |
| `lib/data/models.dart`의 모델·`toJson()` | **DTO/엔티티의 기준 스키마** |

> ⚠ **추정이 섞인 부분**: 엔드포인트 경로·인증 방식·페이지네이션·에러 코드는 코드에 없으므로 제가 설계한 **제안**입니다. 필드 이름·타입·검증 규칙·부수효과는 코드에서 읽은 **사실**입니다. 제안이 강한 곳은 `[제안]`으로 표시했습니다.

### 0.2 앱 구성

- 모바일 알바생 앱(`screens/employee`), 모바일 사장님 앱(`screens/employer`), 사장님 웹/POS(`screens/web`). 셋 다 같은 `AppStore`를 씁니다.
- 아직 안 만든 기능(AI 노무 도우미, 인건비 PDF 내보내기, 정산 확정)은 **API 범위에서 제외**하고 §9에 따로 적었습니다.

---

## 1. 공통 규약

### 1.1 Base URL · 버전
`/api/v1` `[제안]`

### 1.2 인증 `[제안]`
- 로그인은 **카카오 로그인**(`kakao_consent_screen.dart`)만 있습니다. 서버는 카카오 토큰을 검증하고 자체 JWT(access/refresh)를 발급합니다.
- 모든 API는 `Authorization: Bearer <accessToken>`.
- 프로토타입의 `Session.role`(알바생/사장님 전환)은 **서버가 들고 있을 상태가 아닙니다.** 역할은 관계로 정해집니다.
  - 사장님 = `Store.ownerPersonId == me`
  - 알바생 = 그 매장에 `Staff(status=active)`로 소속
  - 한 사람이 둘 다 될 수 있으므로, 클라이언트가 "지금 어느 역할/매장으로 보는지"를 들고 있고 API는 `storeId`를 경로에 명시합니다.

### 1.3 데이터 형식 규칙 (코드의 규칙을 그대로 따름)

| 항목 | 형식 | 비고 |
|---|---|---|
| 근무 날짜 `date` | `"yyyy-MM-dd"` 문자열 | 시간대 변환 없이 매장 현지 날짜 |
| 근무 시간 `startMin`/`endMin` | 정수, **하루 기준 분** (09:30 → 570) | `endMin <= startMin`이면 **야간(다음 날까지)**. `endAbs = endMin + 1440` |
| 시각(출근·생성·서명) | ISO 8601 문자열 | 서버 시각 기준 저장 권장 |
| 월 | `"yyyy-MM"` | 인건비 추이 키 |
| 금액 | 정수(원) | 소수 없음 |
| id | 문자열 | 프로토타입은 `'s12'` 같은 카운터. 서버는 UUID/ULID 등 자유 |

### 1.4 목록 응답 `[제안]`
```json
{ "items": [ ... ], "nextCursor": "opaque-or-null" }
```
프로토타입은 전체를 메모리에 두고 필터하므로 페이지네이션이 없습니다. 근무(`shifts`)·알림·출근 기록은 **기간(`from`/`to`)이나 커서**가 꼭 필요합니다.

### 1.5 에러 응답 `[제안]`
```json
{ "code": "WAGE_BELOW_MINIMUM", "message": "시급은 2026 최저임금 10,320원 이상이어야 해요", "details": { "min": 10320 } }
```

| HTTP | 쓰는 곳 |
|---|---|
| 400 | 검증 실패 (필수값 누락, 최저임금 미만, 종료일 ≤ 시작일 …) |
| 401/403 | 미인증 / 권한 없음 (사장님 전용 API를 알바생이 호출 등) |
| 404 | 대상 없음 (프런트의 `MissingScreen`에 해당) |
| 409 | 상태 충돌 (출근 기록이 있는 근무 삭제, 이미 가입한 매장, 근무 중 매장 전환 …) |

### 1.6 권한 매트릭스
각 API에 `권한`을 적었습니다. 표기: **사장님** = 해당 매장 소유자, **직원** = 해당 매장 재직자(`active`), **본인** = 해당 리소스의 당사자.

---

## 2. Enum

모든 enum은 JSON에서 아래 **영문 `name`** 으로 주고받습니다 (`models.dart`의 `toJson()`과 동일).

| Enum | 값 | 설명 |
|---|---|---|
| `Role` | `employee`, `employer` | 화면 역할 (서버 저장 안 함, §1.2) |
| `Deduction` | `biz33`(원천징수 3.3%), `insurance`(4대보험), `none` | 급여 공제 |
| `StaffStatus` | `pending`, `active`, `left`, `rejected` | 가입 신청 = `pending` |
| `StaffKind` | `manager`, `fulltime`, `partTimer`, `minor` | `manager`·`fulltime`은 **유동 근무**(`flex`): 파트 대신 `part:"직원"`으로 시간 직접 지정 |
| `ShiftKind` | `work`, `leave`, `off` | 연차는 유급, 휴무는 무급 |
| `SubStatus` | `open`, `accepted`, `approved`, `rejected`, `cancelled` | 대타 요청. `open`·`accepted`가 진행 중(`pending`) |
| `Tone` | `primary`, `accent`, `info`, `danger` | 직원 아바타 색(표시용) |
| `AttStatus` | `waiting`, `working`, `normal`, `late`, `absent` | **서버 계산값**. 저장 안 함 (§6) |
| `JoinResult` | `requested`, `alreadyMember`, `alreadyPending`, `notFound` | 매장 코드 가입 결과 |
| `NotificationType` | `sub`, `notice`, `cert`, `pay`, `join`, `contract` | 알림 종류 (현재 코드에선 문자열) |
| 공지 `category` | `일반`, `중요`, `노무` | 현재 코드에서 문자열 3종 고정 |

---

## 3. DTO (응답 스키마)

> 이 절은 `models.dart`를 기준으로 합니다. "서버가 계산해서 내려줄 값"은 `computed`로 표시했습니다.

### 3.1 `PersonDto`
```jsonc
{
  "id": "string",
  "name": "string",            // 짧은 이름 (아바타 글자 = 첫 글자)
  "fullName": "string",
  "certExpiry": "yyyy-MM-dd|null",  // 보건증 만료일. 보건증은 '사람' 단위
  "certAlertDays": [30, 14, 7, 0],  // 만료 며칠 전에 알릴지
  "certHasPhoto": false,
  "certDaysLeft": 12                // computed: 만료까지 남은 일(없으면 null, 지났으면 음수)
}
```
> ⚠ 모델에 **이메일·전화번호·프로필 사진 필드가 없습니다.** 카카오 동의 화면은 프로필(필수)·이메일(필수)·연락처(선택, "대타 알림 연락용")를 받는데 저장할 곳이 없으므로 백엔드 `User` 엔티티에 추가해야 합니다 (§10).

### 3.2 `StoreDto`
```jsonc
{
  "id": "string",
  "name": "string",
  "address": "string",
  "code": "CAFE-8K2D",           // 직원 가입용 매장 코드
  "ownerPersonId": "string",
  "isSmall": true,               // 상시 5인 미만 사업장. true면 가산수당 미적용
  "noticeRequiredDefault": true, // 공지 작성 시 '필독' 기본값
  "substitutePush": true,        // 대타 요청 시 동료에게 푸시
  "defaultDeduction": "biz33",   // 가입 승인 때 기본 공제
  "parts": [ { "name": "오픈", "startMin": 600, "endMin": 960, "need": 1 } ],
  "scheduleConfirmedAt": "ISO|null",
  "monthlyTotals": { "2026-03": 4210000 }  // 지난달 이전 인건비 추이(과거 월)
}
```
- `parts[].need` = 그 파트의 하루 필요 인원.
- `monthlyTotals`는 프로토타입에서 상수입니다. 서버는 **확정된 월별 인건비를 집계해서** 내려주는 것이 맞습니다 (`GET .../labor-cost` 참조).

### 3.3 `ContractDto`
```jsonc
{
  "start": "yyyy-MM-dd", "end": "yyyy-MM-dd",
  "wage": 10320, "weeklyHours": 20,
  "workDays": "월·수·금",     // 자유 텍스트 (UI 예시 '월·수·금')
  "duty": "홀 서빙",
  "ownerSignedAt": "ISO|null", "employeeSignedAt": "ISO|null",
  "signed": false              // computed: 양쪽 서명 완료
}
```

### 3.4 `StaffDto` (매장 소속 직원)
```jsonc
{
  "id": "string",
  "storeId": "string",
  "person": { /* PersonDto */ },   // 화면마다 person 조인이 필요하므로 포함 권장
  "part": "미들",                   // 유동 근무자는 "직원"
  "kind": "partTimer",
  "wage": 10320,
  "deduction": "biz33",
  "weeklyHolidayAuto": true,        // 주휴수당 자동 계산
  "premium": false,                 // 가산수당 적용(5인 이상 매장에서만 의미 있음)
  "tone": "primary",
  "status": "active",
  "joinedAt": "yyyy-MM-dd",
  "requestedAt": "ISO|null",        // 가입 신청 시각
  "contract": { /* ContractDto */ } | null
}
```

### 3.5 `ShiftDto`
```jsonc
{
  "id": "string", "storeId": "string", "staffId": "string",
  "date": "yyyy-MM-dd",
  "startMin": 840, "endMin": 1200,
  "part": "미들",
  "breakMin": 60,
  "kind": "work",
  "timeTbd": false,             // 시간 미정. 시각은 '추천값'
  "isSubstitute": false,
  "originalStaffId": "string|null", // 대타로 바뀐 경우 원래 근무자
  // computed
  "isWork": true,               // kind==work && !timeTbd  → 출퇴근·근태·급여·대타는 이것만 대상
  "scheduledMinutes": 360       // (endAbs - startMin - breakMin)
}
```
**중요한 조회 규칙** (코드 규칙):
- `workOnly=true`(= `isWork`만) → 출퇴근·근태·급여·대타 화면용
- 기본값(전부) → 근무표 표시용 (연차·휴무·시간 미정 포함)

### 3.6 `AttendanceDto`
```jsonc
{
  "id": "string", "storeId": "string", "shiftId": "string", "staffId": "string",
  "clockIn": "ISO", "clockOut": "ISO|null", "editReason": "string|null",
  // computed
  "status": "late",            // AttStatus
  "lateMinutes": 5,
  "workedMinutes": 355,        // 급여 기준 실근무 분 (§6.2)
  "overtimeMinutes": 0,
  "dayPay": 61100              // 그날 기본급(주휴·공제 제외)
}
```

### 3.7 `SubstituteRequestDto`
```jsonc
{
  "id": "string", "storeId": "string", "shiftId": "string",
  "requesterId": "string",       // Staff id
  "reason": "string",
  "notifyPeers": true,
  "status": "open",
  "applicantIds": ["staffId"],   // 수락한 직원들
  "acceptedId": "staffId|null",  // 대타 후보(첫 수락자 또는 사장님 직접 배정)
  "createdAt": "ISO", "notifiedAt": "ISO",  // notifiedAt = 마지막 재알림 시각
  "pending": true                // computed: open | accepted
}
```

### 3.8 `NoticeDto`
```jsonc
{
  "id": "string", "storeId": "string",
  "title": "string", "body": "string",
  "category": "일반", "required": false, "pinned": false,
  "createdAt": "ISO", "remindedAt": "ISO|null",
  "readBy": { "staffId": "ISO" },   // 사장님용 읽음 현황. 직원에겐 isRead만
  "isRead": true,                   // computed: 요청자 기준
  "unreadCount": 2                  // computed (사장님 화면)
}
```
> 정렬 규칙(코드): **고정(pinned) 먼저 → 최신순**.

### 3.9 `NotificationDto`
```jsonc
{
  "id": "string", "storeId": "string", "toPersonId": "string",
  "type": "sub", "title": "string", "body": "string",
  "createdAt": "ISO", "read": false,
  "refId": "string|null"     // type별 참조 id (sub→대타요청, notice→공지, join→staff, cert→staff …)
}
```
정렬: 최신순(같은 시각이면 나중에 생긴 것 먼저).

### 3.10 `PayslipDto`
```jsonc
{
  "staffId": "string", "year": 2026, "month": 5,
  "minutes": 4800,          // 그 달 실근무 분
  "base": 825600,           // 기본급
  "weeklyHoliday": 165120,  // 주휴수당
  "premium": 0,             // 가산수당(야간·8시간 초과분 0.5배)
  "gross": 990720,          // = base + weeklyHoliday + premium
  "deduction": 32693,
  "net": 958027,            // = gross - deduction
  "projected": false,       // true면 오늘 이후 예정 근무까지 포함한 '예상 급여'
  "entries": [ { "date": "yyyy-MM-dd", "start": "ISO", "end": "ISO",
                 "breakMin": 60, "isSubstitute": false, "planned": false,
                 "minutes": 360, "nightMinutes": 0 } ]
}
```

### 3.11 `SchedulePlanDto` (근무표 만들기 미리보기)
```jsonc
{
  "planId": "string",                  // [제안] 미리보기 → 적용을 이어주는 id
  "from": "yyyy-MM-dd", "to": "yyyy-MM-dd",
  "shifts": [ /* ShiftDto without id/final — 새로 추가될 근무 */ ],
  "unfilled": 3,                        // 사람이 모자라 못 채운 칸 수
  "minutesByStaff": { "staffId": 2400 },// 범위 내 총 근무 분(기존 + 새 근무)
  "cost": 1200000                       // 새 근무 예상 인건비(기본급 기준)
}
```

---

## 4. API 명세

> 경로의 `{storeId}`는 현재 보고 있는 매장입니다. 프로토타입의 `store`(= `session.storeId`)에 해당합니다.

### 4.1 인증 · 내 정보

| 메서드 | 경로 | 권한 | 프런트 근거 |
|---|---|---|---|
| POST | `/auth/kakao` | 공개 | `login_screen`, `kakao_consent_screen` |
| POST | `/auth/refresh` | 공개 | `[제안]` |
| POST | `/auth/logout` | 로그인 | `AppStore.logout` |
| GET | `/me` | 로그인 | `AppStore.me`, `myMemberships` |

**POST `/auth/kakao`**
```jsonc
// Request
{
  "kakaoAccessToken": "string",
  "consents": { "profile": true, "email": true, "phone": false }
}
// Response 200
{ "accessToken": "...", "refreshToken": "...", "me": { /* MeDto */ } }
```
검증: `consents.profile`, `consents.email`이 **둘 다 true**여야 함(아니면 400 `CONSENT_REQUIRED`) — 화면의 "필수 항목에 동의해 주세요". `phone`은 선택(대타 알림 연락용).

**GET `/me` → `MeDto`**
```jsonc
{
  "person": { /* PersonDto */ },
  "ownedStoreId": "string|null",            // 사장님이면 (1인 1매장)
  "memberships": [ { /* StaffDto, status=active|pending */ } ]  // 알바생 소속 매장들
}
```

### 4.2 매장

| 메서드 | 경로 | 권한 | 근거 |
|---|---|---|---|
| POST | `/stores` | 로그인 | `createStore` |
| GET | `/stores/{storeId}` | 사장님·직원 | `store` |
| PATCH | `/stores/{storeId}` | 사장님 | `createStore`(이름·주소·규모·기본공제 수정) |
| PATCH | `/stores/{storeId}/settings` | 사장님 | `updateStore` |
| PUT | `/stores/{storeId}/parts/needs` | 사장님 | `applyTemplate`, `applySchedulePlan`의 needs |
| POST | `/stores/join-requests` | 로그인 | `requestJoin` |
| GET | `/stores/{storeId}/code` | 사장님 | `store_code_screen`, 웹 직원 초대 |

**POST `/stores`** — `store_create_screen`
```jsonc
{ "name": "카페 커푸치노", "address": "도로명 주소", "isSmall": true, "defaultDeduction": "biz33" }
```
검증: `name`, `address` 비어 있으면 400 ("상호명과 주소를 입력해 주세요"). 서버는 `code`를 생성(대문자·숫자 + 하이픈, 예 `CAFE-8K2D`)하고 **기본 파트 템플릿**을 만들어야 합니다(오픈 10–16 · 미들 14–20 · 마감 18–22, need=1 — 시드 기준 `[제안]`).
> 프로토타입은 "사장님 1인 1매장"이라 이 호출이 **기존 매장을 덮어씁니다**(`shortcut` 주석). 실서비스에서는 신규 생성으로 바꾸고 1인 1매장을 제약으로 둘지 정해야 합니다.

**PATCH `/stores/{storeId}/settings`** — 모두 선택, 보낸 것만 반영
```jsonc
{ "isSmall": false, "noticeRequiredDefault": true, "substitutePush": true }
```

**PUT `/stores/{storeId}/parts/needs`**
```jsonc
{ "needs": [1, 2, 1] }   // store.parts 순서대로. 인덱스가 모자라면 무시
```
> 프로토타입은 인덱스 순서에 의존합니다. 서버에서는 `{ "partName": need }` 맵이 더 안전합니다 `[제안]`.

**POST `/stores/join-requests`** — `store_code_screen`(알바생)
```jsonc
// Request
{ "code": "cafe-8k2d" }          // 대소문자·하이픈 무시 (대문자+영숫자만 남겨 비교)
// Response 200 / 404 / 409
{ "result": "requested", "store": { "id": "...", "name": "..." } }
```
| `result` | HTTP | 화면 메시지 |
|---|---|---|
| `requested` | 201 | 가입 신청 → 대기 화면 |
| `notFound` | 404 | "매장 코드를 찾을 수 없어요" |
| `alreadyMember` | 409 | "이미 가입한 매장이에요" |
| `alreadyPending` | 409 | 신청 중 |

부수효과: `Staff(status=pending, part=첫 파트, wage=최저임금, requestedAt=now)` 생성 → **사장님에게 알림**(`type=join`, "OO님이 가입을 신청했어요", `refId=staffId`).

### 4.3 직원(Staff)

| 메서드 | 경로 | 권한 | 근거 |
|---|---|---|---|
| GET | `/stores/{storeId}/staff?status=active\|pending` | 사장님·직원 | `activeStaff`, `pendingStaff`, `flexStaff`, `partStaff` |
| GET | `/stores/{storeId}/staff/{staffId}` | 사장님, 본인 | 직원 상세 |
| POST | `/stores/{storeId}/staff/{staffId}/approve` | 사장님 | `approveJoin` |
| POST | `/stores/{storeId}/staff/{staffId}/reject` | 사장님 | `rejectJoin` |
| PATCH | `/stores/{storeId}/staff/{staffId}` | 사장님 | `updateStaff` |
| DELETE | `/stores/{storeId}/staff/{staffId}` | 사장님 | `removeStaff` (status → `left`) |
| POST | `/stores/{storeId}/staff/{staffId}/cert-renewal-requests` | 사장님 | `requestCertRenewal` |

**POST `.../approve`**
```jsonc
{ "wage": 10320, "deduction": "biz33" }   // deduction 생략 시 store.defaultDeduction
```
검증: `wage >= 10320`(2026 최저임금, 상수는 서버 설정으로). 부수효과: `status=active`, `joinedAt=오늘`, 직원에게 알림 `join` "OO 가입이 승인됐어요" (body "시급 …").

**POST `.../reject`** — 본문 없음. `status=rejected`, 직원에게 알림 "가입이 거절됐어요".
(알바생 "승인 전이에요" 화면은 `GET /me`로 status를 폴링합니다.)

**PATCH `.../staff/{staffId}`** — 모두 선택
```jsonc
{ "wage": 11000, "deduction": "insurance", "weeklyHolidayAuto": true,
  "premium": false, "part": "오픈", "kind": "fulltime" }
```
검증: `wage` 보낼 때 `>= 10320` (시급 편집 3곳 모두 동일 검증: 직원 상세 시트, 급여 설정, 시급 다이얼로그).

**DELETE `.../staff/{staffId}`** — 부수효과(코드 그대로):
1. `status=left`
2. **오늘 이후이면서 출근 기록 없는 근무 삭제**
3. 진행 중 대타: 이 직원이 **요청자**이면 `cancelled`; **지원자**이면 지원자에서 제거, **acceptedId**였다면 `acceptedId=null`, `status=open`으로 되돌림
4. 본인에게 알림 `join` "OO에서 퇴사 처리됐어요"

**POST `.../cert-renewal-requests`** — 본문 없음. 직원에게 알림 `cert`: 만료일 없으면 "보건증을 등록해 주세요", 있으면 "보건증 만료 D-n"(지났으면 "지남").

### 4.4 서류 (보건증 · 근로계약서)

| 메서드 | 경로 | 권한 | 근거 |
|---|---|---|---|
| PUT | `/me/health-cert` | 본인 | `uploadHealthCert`, `health_cert_register_screen` |
| PUT | `/stores/{storeId}/staff/{staffId}/contract` | 사장님 | `createContract` |
| POST | `/stores/{storeId}/staff/{staffId}/contract/sign` | 본인(직원) | `signContract` |

**PUT `/me/health-cert`** — `multipart/form-data`
```
expiry     : yyyy-MM-dd        (필수)
alertDays  : 30,14,7,0         (필수. 알림 시점 선택)
photo      : image file        (필수 — "보건증 사진을 올려 주세요")
```
부수효과: `certExpiry`, `certAlertDays`, `certHasPhoto=true` 저장. **내가 재직 중인 모든 매장의 사장님**에게 알림 `cert` "OO님이 보건증을 갱신했어요"(body "만료일 yyyy.MM.dd").
> 현재 프로토타입의 "사진 올리기"는 탭하면 성공하는 **모의 동작**이라 실제 업로드가 없습니다. 파일 저장소(S3 등)가 새로 필요합니다.
> 보건증 만료 임박 알림(`certAlertDays` 기준)은 코드에 발송 로직이 **없으므로** 서버 스케줄러가 필요합니다.

**PUT `.../contract`** — 사장님이 작성 + 서명해서 보냄
```jsonc
{ "start": "2026-06-01", "end": "2026-12-01", "wage": 10320,
  "weeklyHours": 20, "workDays": "월·수·금", "duty": "홀 서빙" }
```
검증(모바일·웹 공통):
- 직원 선택 필수
- `wage`, `weeklyHours`, `workDays`, `duty` **모두 필수** (모바일은 누락 항목을 "X, Y을(를) 입력해 주세요"로 안내)
- `wage >= 10320`
- `end` **>** `start`

부수효과: `ownerSignedAt=now`, `employeeSignedAt=null`로 저장(기존 계약서 덮어씀) → 직원에게 알림 `contract` "근로계약서 서명 요청".
> 웹 폼은 근무 요일·주 시간을 "앞으로 2주 근무에서 자동으로 채웁니다" — 이 값 산출은 클라이언트 쪽이므로 서버에 필요한 건 최종 값뿐입니다.

**POST `.../contract/sign`** — 본문 `{ "signature": "<이미지 base64 또는 업로드 id>" }` `[제안]`
검증: 계약서가 존재해야 함(없으면 404/409). 화면 규칙상 "내용 확인 체크" + "서명 입력" 둘 다 필요(클라이언트 검증). 부수효과: `employeeSignedAt=now`, 사장님에게 알림 `contract` "OO님이 근로계약서에 서명했어요".
> 프로토타입 `SignaturePad`(손가락 서명)의 결과는 **저장되지 않습니다**(시각만 기록). 법적 효력을 위해 서명 이미지·IP·약관 버전 저장이 필요합니다.

### 4.5 근무표 (Shift)

| 메서드 | 경로 | 권한 | 근거 |
|---|---|---|---|
| GET | `/stores/{storeId}/shifts?from=&to=&staffId=&workOnly=` | 사장님·직원 | `scheduleOn`, `scheduleOfStaff`, `shiftsOn`, `shiftsOfStaff` |
| GET | `/stores/{storeId}/shifts/{shiftId}` | 사장님·직원 | `shiftById` |
| POST | `/stores/{storeId}/shifts` | 사장님 | `addShift` |
| PATCH | `/stores/{storeId}/shifts/{shiftId}` | 사장님 | `updateShift` |
| DELETE | `/stores/{storeId}/shifts/{shiftId}` | 사장님 | `deleteShift` |
| POST | `/stores/{storeId}/schedule/auto-fill` | 사장님 | `autoFill` |
| POST | `/stores/{storeId}/schedule-plans` | 사장님 | `planSchedule` (미리보기, 저장 안 함) |
| POST | `/stores/{storeId}/schedule-plans/{planId}/apply` | 사장님 | `applySchedulePlan` |
| POST | `/stores/{storeId}/schedule/template` | 사장님 | `applyTemplate` |
| POST | `/stores/{storeId}/schedule/confirm` | 사장님 | `confirmSchedule` |
| GET | `/stores/{storeId}/schedule/tbd-count?year=&month=` | 사장님 | `tbdCount` |

**GET `/shifts`** — 알바생 화면은 `staffId=내 id&workOnly=true`, 사장님 근무표는 기간 전체. 결과는 **시작 시각순**(`date + startMin`).

**POST `/shifts`** — `web_shift_dialog`, `shift_add_screen`
```jsonc
{
  "date": "2026-05-20",
  "startMin": 840, "endMin": 1200,
  "part": "미들",
  "staffIds": ["m1", "m2"],        // 여러 명이면 같은 근무를 명수만큼 생성 (웹은 1명, 모바일은 다중)
  "breakMin": 60,                   // 생략 시 자동(아래)
  "repeatWeeks": 1,                 // >1이면 매주 같은 요일로 반복 생성
  "kind": "work",                   // work | leave | off
  "timeTbd": false
}
// Response 201: { "created": [ShiftDto, ...] }
```
- 휴게 기본값(서버가 계산): 근무 길이 **≥ 480분 → 60분, ≥ 240분 → 30분, 그 외 0**.
- 알림(첫 주만): `kind=work && !timeTbd`인 각 직원에게 `sub` "새 근무가 배정됐어요".
- 검증: `staffIds` 1명 이상("근로자를 한 명 이상 골라 주세요"). 겹침·지난 날짜 검증은 웹 드래그 이동에서 클라이언트가 "problem" 문구로 막습니다(`web_schedule_views.dart`). **서버에서도 같은 규칙으로 검증**하세요(§10). 겹침 판정 = 같은 직원의 *실제 근무*(`isWork`)와 시간 구간이 겹치는지.

**PATCH `/shifts/{shiftId}`** — 모두 선택
```jsonc
{ "staffId": "m2", "date": "2026-05-21", "startMin": 600, "endMin": 960,
  "part": "오픈", "breakMin": 30, "kind": "work", "timeTbd": false }
```
알림 규칙(코드 그대로):
- 근무였는데 **담당자가 바뀌었거나 근무가 아니게 됨** → 이전 담당자에게 "근무가 삭제됐어요"
- 근무인데 **담당자가 바뀌었거나 새로 근무가 됨** → 새 담당자에게 "새 근무가 배정됐어요"
- 같은 담당자인데 날짜·시간 라벨이 바뀜 → "근무가 수정됐어요"

**DELETE `/shifts/{shiftId}`**
- 출근 기록(`Attendance`)이 있으면 **409** — "출근 기록이 있는 근무는 삭제할 수 없어요"
- 존재하지 않으면 성공(멱등, `true` 반환)
- 부수효과: 진행 중 대타 요청을 `cancelled`로, 실제 근무였다면 담당자에게 "근무가 삭제됐어요"

**POST `/schedule/auto-fill`** — `{ "weekMonday": "2026-05-18" }` → `{ "added": 3 }`
규칙: 그 주 7일 중 **오늘 이후 날짜만**, 파트별 `need`가 모자란 칸을 채움. 후보 = `partStaff`(유동 근무자 제외) 중 그날 근무 없음 + 시간 안 겹침, 그 주 근무 분이 **적은 사람부터**. 채울 사람이 없으면 중단. `added=0`이면 변경 없음.

**POST `/schedule-plans`** (미리보기) — `web_schedule_create_dialog`
```jsonc
{
  "from": "2026-05-01", "to": "2026-05-31",
  "needs": [1, 2, 1],                       // 파트별 필요 인원 (store.parts 순서)
  "copyLastWeek": false,                    // 지난주 근무를 요일마다 반복한 뒤 빈칸 채움
  "excludeStaffIds": ["m3"],                // 이번 배정에서 뺄 직원
  "weeklyMaxMin": 2400                      // 주 최대 근무 분 (기본 40시간)
}
// Response: SchedulePlanDto
```
핵심 규칙: 시작은 `max(from, 오늘)`. 이미 있는 근무·겹침은 건드리지 않음. 빈칸은 **자기 파트 직원 먼저**, 다음으로 그 주 근무 적은 사람. 주 최대 초과자는 제외. 연차·휴무·시간 미정은 복사 대상 아님. 못 채우면 `unfilled`에 누적.

**POST `/schedule-plans/{planId}/apply`** — `{ "needs": [1,2,1] }` → `{ "added": 42 }`
미리보기 근무를 실제로 저장하고 `parts[].need`를 갱신, **직원별 1건 알림** `sub` "N월 근무가 추가됐어요"(body "n건 · …"). 하나의 되돌리기 단위로 묶음(§10).

**POST `/schedule/template`** (모바일 근무표 템플릿) — `{ "needs": [1,2,1], "copyLastWeek": true }` → `{ "copied": 12 }`
필요 인원 저장 + (선택) **오늘이 속한 주**로 지난주 근무 복사(같은 직원·같은 파트가 이미 있으면 건너뜀, 재직자만).

**POST `/schedule/confirm`** — `{ "year": 2026, "month": 5 }`
`store.scheduleConfirmedAt=now`, **재직 직원 전원**에게 알림 `sub` "N월 근무표가 확정됐어요". 서버 검증 추가 권장: 그 달 `timeTbd` 근무가 남아 있으면 **409**(프런트가 "시간 미정 n건을 먼저 정해 주세요"로 막음).

### 4.6 출퇴근 · 근태 (Attendance)

| 메서드 | 경로 | 권한 | 근거 |
|---|---|---|---|
| POST | `/stores/{storeId}/shifts/{shiftId}/clock-in` | 본인(직원) | `clockIn`, `qr_check_in_screen`, `employee_home_screen` |
| POST | `/stores/{storeId}/attendance/clock-out` | 본인(직원) | `clockOut` |
| GET | `/stores/{storeId}/attendance?date=` | 사장님 | `attendance_screen`, 대시보드 |
| GET | `/stores/{storeId}/attendance/{id}` | 사장님, 본인 | `attendance_detail_screen` |
| PATCH | `/stores/{storeId}/attendance/{id}` | 사장님 | `editAttendance` |
| POST | `/stores/{storeId}/shifts/{shiftId}/attendance` | 사장님 | `createAttendance` (미출근 처리) |
| GET | `/me/today` | 직원 | `myTodayShift`, `isWorking`, `myOpenAttendance` |

**POST `.../clock-in`** — 본문 `{ "qrToken": "string" }` `[제안]`
서버 검증(코드의 `clockInBlock` 그대로):
- 근무 종료 시각이 지났으면 **409** "근무 시간이 지나 출근할 수 없어요"
- 근무 시작 **30분 전 이전**이면 **409** "근무 시작 30분 전부터 출근할 수 있어요"
- `isWork`가 아닌 근무(연차·휴무·시간 미정)는 출근 불가
- 이미 출근 기록이 있으면 새로 만들지 않고 **기존 기록을 반환**(멱등)
- 본인 근무인지 확인

시각은 **서버 시각**으로 기록(클라이언트 시각 신뢰 금지). 
> QR은 프로토타입에서 "탭하면 성공하는 모의 동작"입니다. 매장 QR(웹 POS W8)을 누르면 그 매장의 *아직 출근 안 한 다음 근무자*가 출근하는 **데모**일 뿐입니다. 실서비스에서는 **매장별 회전 QR 토큰** 발급(`POST /stores/{storeId}/qr-tokens`)과 검증 설계가 필요합니다(§10).

**POST `.../clock-out`** — 본문 없음. "내 열린 출근 기록"(`clockOut==null`)을 찾아 `clockOut=now`. 없으면 404.

**PATCH `.../attendance/{id}`** (근태 수정)
```jsonc
{ "clockIn": "2026-05-14T14:05:00", "clockOut": "2026-05-14T20:02:00", "reason": "출근 태깅 누락" }
```
검증: `reason` 필수("수정 사유를 입력해 주세요"). `clockOut`은 선택. 부수효과: `editReason` 저장 + 직원에게 알림 `pay` "근태 기록이 수정됐어요"(body = 사유).

**POST `.../shifts/{shiftId}/attendance`** — 같은 본문. 사장님이 출근 기록을 새로 만듦(`editReason` 포함 저장). 알림은 만들지 않음(코드 기준).

**GET `/attendance?date=`** 응답은 AttendanceDto + 해당 `ShiftDto` + 직원 요약. 상태(`AttStatus`) 판정:
- 출근 기록 없음 → 근무 종료가 지났으면 `absent`, 아니면 `waiting`
- 출근했고 `clockOut==null` → `working`
- 퇴근 완료 → 지각분 > 0이면 `late`, 아니면 `normal`
- 지각 분 = `max(0, clockIn - 근무시작)`

### 4.7 대타 (Substitute)

| 메서드 | 경로 | 권한 | 근거 |
|---|---|---|---|
| POST | `/stores/{storeId}/shifts/{shiftId}/substitute-requests` | 근무 당사자 | `requestSubstitute`, `leave_request_screen` |
| GET | `/stores/{storeId}/substitute-requests?status=&mine=&available=` | 사장님·직원 | `storeSubs`, `pendingSubs`, `mySubs`, `subsAvailableToMe` |
| POST | `/.../substitute-requests/{id}/apply` | 직원 | `applySubstitute` |
| POST | `/.../substitute-requests/{id}/cancel` | 요청자 | `cancelSubstitute` |
| POST | `/.../substitute-requests/{id}/approve` | 사장님 | `approveSubstitute` |
| POST | `/.../substitute-requests/{id}/reject` | 사장님 | `rejectSubstitute` |
| POST | `/.../substitute-requests/{id}/resend` | 요청자, 사장님 | `resendSubstitute` |
| POST | `/.../substitute-requests/{id}/assign` | 사장님 | `assignSubstitute` |

**POST `.../substitute-requests`**
```jsonc
{ "reason": "병원 진료", "notifyPeers": true }
```
검증: `reason` 필수("사유를 입력해 주세요"). 대상은 **`isWork` 근무만**. 부수효과: 요청 생성(`open`) → 사장님에게 `sub` "OO님이 휴무·대타를 신청했어요"(body "M/d HH:mm–HH:mm") + `notifyPeers && store.substitutePush`이면 **다른 재직 직원 전원**에게 "OO님이 대타를 구해요".

**GET 필터**
- 기본: 매장 전체, **최신순(`createdAt` desc)**
- `status=pending`: `open|accepted`
- `mine=true`: 내가 요청자인 것
- `available=true`: 내가 지원 가능한 것 = `status=open` ∧ 요청자 ≠ 나 ∧ 이미 지원 안 함 ∧ 그 근무의 **시작이 아직 안 지남**

**POST `.../apply`** — 본문 없음 (사장님이 대신하는 데모는 `staffId` 선택 가능)
- 요청자 본인이거나 이미 지원했으면 무시
- `applicantIds`에 추가, **첫 지원자**면 `acceptedId=나`, `status=accepted`
- 알림: 요청자에게 "OO님이 대타를 수락했어요", 사장님에게 "…수락했어요"(body "승인하면 근무표에 반영돼요")
- 서버 권장 추가 검증: 지원자가 그 시간에 **다른 근무와 겹치지 않는지**(`overlappingShift`) — 데모 헬퍼에는 있으나 `applySubstitute` 자체에는 없음

**POST `.../approve`** — `acceptedId`가 없으면 무시(400 권장). 성공 시 `status=approved`, 근무 변경: `originalStaffId=기존 staffId`, `staffId=acceptedId`, `isSubstitute=true`. 알림: 요청자 "대타 요청이 승인됐어요"(body "라벨 · OO님이 근무해요"), 대타자 "대타 근무가 확정됐어요".

**POST `.../reject`** — `status=rejected`. 요청자(와 `acceptedId`가 있으면 그 직원)에게 "대타 요청이 거절됐어요".

**POST `.../cancel`** — `status=cancelled`. 지원자 전원 + 사장님에게 "…대타 요청을 취소했어요".

**POST `.../resend`** — `notifiedAt=now`, 동료에게 재알림(`substitutePush` 켜짐일 때만). **사장님 알림은 만들지 않음.**

**POST `.../assign`** — `{ "staffId": "m4" }` 사장님이 직접 대타자 지정 → 지원자에 추가 + `acceptedId` 설정 + 곧바로 `approve` 처리.

> `simulatePeerAccept`(동료 수락 데모)는 데모 전용이므로 **API 대상이 아닙니다.**

### 4.8 공지 (Notice)

| 메서드 | 경로 | 권한 | 근거 |
|---|---|---|---|
| GET | `/stores/{storeId}/notices` | 사장님·직원 | `storeNotices` |
| GET | `/stores/{storeId}/notices/{id}` | 사장님·직원 | `noticeById` |
| POST | `/stores/{storeId}/notices` | 사장님 | `publishNotice` |
| POST | `/stores/{storeId}/notices/{id}/read` | 직원 | `markNoticeRead` |
| POST | `/stores/{storeId}/notices/read-all` | 직원 | `markAllNoticesRead` |
| GET | `/stores/{storeId}/notices/{id}/read-status` | 사장님 | `notice_read_status_screen`, `unreadStaffOf` |
| POST | `/stores/{storeId}/notices/{id}/remind` | 사장님 | `remindUnread` |

**POST `/notices`**
```jsonc
{ "title": "마감 청소 방법 안내", "body": "…", "category": "일반", "required": true }
```
검증: `title`, `body` **trim 후 비면 400**("제목과 내용을 입력해 주세요"). `required` 생략 시 `store.noticeRequiredDefault`. 부수효과: **재직 직원 전원**에게 알림 `notice` — 필독이면 "새 필독 공지", 아니면 "새 공지"(body = 제목, `refId=noticeId`).
> `pinned`를 바꾸는 UI/액션은 코드에 **없습니다**(항상 false). 고정 기능이 필요하면 API를 추가해야 합니다.

**POST `/notices/{id}/read`** — 이미 읽었으면 변경 없음(멱등). `readBy[내 staffId]=now`.

**GET `/read-status`** → `{ "read": [{ "staffId", "readAt" }], "unread": [StaffDto] }` — 재직 직원 중 `readBy`에 없는 사람이 `unread`.

**POST `/remind`** → `{ "sent": 3 }` — 안 읽은 직원에게 "필독 공지를 확인해 주세요" 알림, `remindedAt=now`.

### 4.9 알림 (Notification)

| 메서드 | 경로 | 권한 | 근거 |
|---|---|---|---|
| GET | `/me/notifications?storeId=` | 본인 | `myNotifications` |
| GET | `/me/notifications/unread-count?storeId=` | 본인 | `unreadNotificationCount` (탭 배지) |
| POST | `/me/notifications/{id}/read` | 본인 | `markNotificationRead` |
| POST | `/me/notifications/read-all?storeId=` | 본인 | `markAllNotificationsRead` |

알림은 쓰기 API들의 부수효과로 **서버가 생성**합니다(§5에 전체 목록). 클라이언트가 직접 만드는 알림은 없습니다. 푸시(FCM/APNs)는 코드에 없으며 `store.substitutePush` 플래그 정도만 있습니다 `[제안]`.

### 4.10 급여 · 인건비

| 메서드 | 경로 | 권한 | 근거 |
|---|---|---|---|
| GET | `/stores/{storeId}/staff/{staffId}/payslips/{yyyy-MM}?projected=` | 사장님, 본인 | `payslip`, `employee_salary_screen`, `payslip_detail_screen`, `employer_payroll_detail_screen`, `web_payroll_screen` |
| GET | `/stores/{storeId}/labor-cost?year=&month=&projected=` | 사장님 | `laborTotal`, `employer_labor_cost_screen`, 웹 인건비 |
| GET | `/stores/{storeId}/labor-cost/trend?months=` | 사장님 | `monthlyTotals` |

**GET payslip** → `PayslipDto`. 계산 규칙(서버가 동일하게 구현해야 합니다 — `payroll.dart`):

1. **조회 범위**: 그 달 1일이 속한 주의 월요일부터 (그 달 말일이 속한 주 월요일 + 7일) 직전까지의 근무(주휴 계산 때문에 앞뒤 주를 포함).
2. **근무 항목**
   - 연차(`leave`): 소정 시간만큼 **유급**. 지난 연차는 확정, 앞으로의 연차는 `projected`일 때만.
   - `isWork` 근무: 퇴근 기록이 있으면 실근무, 없으면 `projected`이고 안 끝난 근무만 **예정**(`planned=true`)으로.
   - 휴무·시간 미정: 0원.
3. **실근무 분** = `max(0, (퇴근 − max(출근, 소정시작)) − 휴게분)`. **일찍 찍어도 소정 시작부터** 계산.
4. **기본급** = `round(분 × 시급 / 60)`.
5. **주휴수당**(`weeklyHolidayAuto=true`일 때): 그 주(월~일) **대타 제외** 근무가 **15시간 이상**이면 `round(min(주시간,40)/40 × 8 × 시급)`. 그 주의 **일요일이 속한 달**에 지급.
6. **가산수당**(`!store.isSmall && staff.premium`): 야간(22:00~06:00) 겹친 분의 시급 × 0.5, 하루 480분 초과분 × 0.5 (정수 나눗셈 `~/2`). 휴일가산 없음.
7. **공제**: `biz33` = `floor(총액×0.033)`, `insurance` = `floor(총액×0.094)`(근사 요율, 화면에 "예상" 표기), `none` = 0.
8. `gross = base + weeklyHoliday + premium`, `net = gross − deduction`.

**GET labor-cost** → `{ "year", "month", "total", "byStaff": [{ "staffId", "gross" }] }`. 과거 확정 월은 저장된 값(`monthlyTotals`), 그 외는 `status != pending`인 직원 전원의 `gross` 합.
> 프로토타입의 "2달 전 이전" 값은 **시드가 만든 상수**입니다. 서버에서는 월 마감(정산 확정) 시점에 스냅샷을 저장해야 합니다. 정산 확정 기능 자체가 아직 없습니다(§9).

### 4.11 대시보드 (집계 GET) `[제안]`

화면은 아래 값들을 `AppStore`에서 즉석 계산합니다. API 왕복을 줄이려면 집계 엔드포인트를 두는 것이 좋습니다.

`GET /stores/{storeId}/dashboard?date=` →
```jsonc
{
  "todayShifts": [ { "shift": {…}, "staff": {…}, "attendance": {…}|null, "status": "working" } ],
  "pendingSubstitutes": [ {…} ],
  "pendingJoinRequests": 1,
  "certAlerts": [ { "staffId", "daysLeft": 12 } ],     // 만료 임박·미등록
  "unsignedContracts": [ "staffId" ],
  "unreadNotificationCount": 3
}
```

---

## 5. 알림 생성 규칙 (서버 부수효과 전체표)

| 트리거 | 수신자 | type | 제목 |
|---|---|---|---|
| 가입 신청 | 사장님 | `join` | "{이름}님이 가입을 신청했어요" |
| 가입 승인 | 신청자 | `join` | "{매장} 가입이 승인됐어요" (body "시급 …") |
| 가입 거절 | 신청자 | `join` | "{매장} 가입이 거절됐어요" |
| 직원 내보내기 | 해당 직원 | `join` | "{매장}에서 퇴사 처리됐어요" |
| 근무 생성(첫 주, 실제 근무) | 담당 직원 | `sub` | "새 근무가 배정됐어요" |
| 근무 수정 | 이전/새/동일 담당자 | `sub` | "근무가 삭제됐어요" / "새 근무가 배정됐어요" / "근무가 수정됐어요" |
| 근무 삭제(실제 근무) | 담당 직원 | `sub` | "근무가 삭제됐어요" |
| 근무표 만들기 적용 | 직원별 1건 | `sub` | "{월}월 근무가 추가됐어요" |
| 근무표 확정 | 재직 직원 전원 | `sub` | "{월}월 근무표가 확정됐어요" |
| 대타 요청 | 사장님 / 동료(옵션) | `sub` | "{이름}님이 휴무·대타를 신청했어요" / "…대타를 구해요" |
| 대타 수락 | 요청자, 사장님 | `sub` | "…대타를 수락했어요" |
| 대타 취소 | 지원자 전원, 사장님 | `sub` | "…대타 요청을 취소했어요" |
| 대타 승인 | 요청자, 대타자 | `sub` | "대타 요청이 승인됐어요" / "대타 근무가 확정됐어요" |
| 대타 거절 | 요청자(+acceptedId) | `sub` | "대타 요청이 거절됐어요" |
| 대타 재알림 | 동료 | `sub` | "{이름}님 대타를 다시 구해요" |
| 공지 작성 | 재직 직원 전원 | `notice` | "새 필독 공지" / "새 공지" |
| 공지 재알림 | 안 읽은 직원 | `notice` | "필독 공지를 확인해 주세요" |
| 근태 수정 | 해당 직원 | `pay` | "근태 기록이 수정됐어요" |
| 보건증 갱신 | 소속 매장 사장님들 | `cert` | "{이름}님이 보건증을 갱신했어요" |
| 보건증 갱신 요청 | 해당 직원 | `cert` | "보건증 만료 D-n" / "…지남" / "보건증을 등록해 주세요" |
| 계약서 발송 | 직원 | `contract` | "근로계약서 서명 요청" |
| 계약서 서명 | 사장님 | `contract` | "{이름}님이 근로계약서에 서명했어요" |

---

## 6. 서버가 계산해서 내려줄 파생값

프로토타입은 화면이 `AppStore`에서 즉석 계산합니다. 백엔드로 옮길 때 **로직 중복을 피하려면 서버가 계산**하고, 클라이언트는 표시만 해야 합니다.

| 값 | 규칙 | 위치 |
|---|---|---|
| `isWork` | `kind==work && !timeTbd` | Shift |
| `scheduledMinutes` | `endAbs − startMin − breakMin` | Shift |
| `status`(AttStatus) | §4.6 | Attendance |
| `workedMinutes` | `max(출근, 소정시작)`부터 퇴근(또는 지금)까지, 퇴근 완료 시에만 휴게 차감 | Attendance |
| `certDaysLeft` | `certExpiry − 오늘`(일) | Person |
| `payslip` / `laborTotal` | §4.10 | Payroll |
| `unreadNoticeCount` | 매장 공지 중 내 `readBy`에 없는 수 | `/notices` 응답 |
| `unreadNotificationCount` | 내 알림 중 `read=false` | `/me/notifications` |

---

## 7. 검증 규칙 요약 (프런트에서 이미 막고 있는 것 → 서버도 똑같이 막기)

| 규칙 | 값 | 근거 |
|---|---|---|
| 최저임금 | 시급 `>= 10320` (2026) | 직원 승인·시급 변경·급여 설정·계약서 4곳 |
| 계약 기간 | `end > start` | 계약서 작성 |
| 필수 입력 | 상호명·주소 / 공지 제목·내용 / 대타 사유 / 근태 수정 사유 / 계약서 4항목 | 각 폼 |
| 출근 가능 시간 | 시작 30분 전 ~ 종료 | `clockInBlock` |
| 근무 중 매장 전환 불가 | 열린 출근 기록이 있으면 불가 | `switchStore` (클라이언트 상태지만 서버 정책으로 둘 수도 있음) |
| 출근 기록 있는 근무 삭제 불가 | 409 | `deleteShift` |
| 근무표 확정 | 시간 미정 근무가 0건일 때만 | 웹 확정 |
| 근무 겹침 | 같은 직원의 실제 근무끼리 시간 구간 겹침 금지 | 드래그 이동·자동 배정·대타 |
| 지난 날짜 | 지난 날짜로 이동 금지, 자동 채우기는 오늘 이후만 | 웹 근무표 |
| 필수 동의 | 프로필·이메일 | 카카오 동의 |
| 서명 | 확인 체크 + 서명 | 계약서 서명(클라이언트) |

---

## 8. 데이터베이스 개요 `[제안]`

`AppState`의 컬렉션이 곧 테이블입니다.

| 테이블 | 키 | 비고 |
|---|---|---|
| `users`(=Person) | id | + email, phone, kakaoId, profileImage 추가 필요 |
| `stores` | id | unique(`code`) |
| `store_parts` | (storeId, name) | name, startMin, endMin, need |
| `staff` | id | unique(personId, storeId) *단 left/rejected 이후 재가입 허용 여부 결정 필요* |
| `contracts` | staffId | staff당 1건(덮어씀) 또는 이력 보관 |
| `shifts` | id | index(storeId, date), index(staffId, date) |
| `attendance` | id | unique(shiftId) — 근무당 출근 기록 1건 |
| `substitute_requests` + `substitute_applicants` | id | applicantIds는 조인 테이블 |
| `notices` + `notice_reads` | id / (noticeId, staffId) | readBy = 조인 테이블 |
| `notifications` | id | index(toPersonId, storeId, createdAt) |
| `monthly_labor_snapshots` | (storeId, yyyy-MM) | 정산 확정 시 저장 |

---

## 9. 범위 밖 (프런트에 아직 없는 기능)

CLAUDE.md와 코드에서 확인한 "미구현" 항목입니다. 백엔드 1차 범위에서는 제외하고, 필요하면 별도 설계하세요.

- **AI 노무 도우미**(모바일 B6, 웹 W7): "추후 개발 예정" 안내만 있고 입력창(`web_ai_assistant_screen.dart`의 `_input`)도 전송하지 않음
- **인건비 PDF 내보내기 · 정산 확정**: 토스트만
- **근무표 "저장"**: 항상 자동 저장이라 비활성
- **일간 보기 블록 가장자리 드래그로 시간 조정**: 없음(수정 창에서만)
- **"휴게 시작" 지정**: "자동" 고정 → `breakMin`(분)만 저장
- **노무 체크리스트의 5인 미만 판정**: 현재 재직 인원으로 근사(월 평균 인원 아님)

---

## 10. 백엔드 개발 시 결정이 필요한 사항 / 프로토타입과의 차이

1. **실제 QR 출근**: 지금은 모의 동작. 매장 QR 토큰 발급·회전·검증, 위치/기기 검증 여부 결정 필요.
2. **되돌리기(undo/redo)**: 프로토타입은 **전체 상태 JSON 스냅샷 50단계를 메모리에** 저장. 서버에서는 (a) 클라이언트가 역연산을 호출, (b) `schedule_change_batches` 테이블로 배치 단위 롤백, (c) 기능 제거 중 선택. 근무표 만들기 적용은 "한 번에 취소"되어야 함.
3. **동시성**: 여러 사람이 같은 근무표를 동시에 수정 가능 → `Shift`에 `version`(낙관적 락) 권장. 대타 승인·출근은 중복 요청 멱등 처리 필수.
4. **사장님 1인 N매장 여부**: 프로토타입은 1인 1매장이며 `createStore`가 기존 매장을 덮어씀.
5. **알림 전달**: 푸시 연동, 보건증 만료 임박 알림 스케줄러, 알림 보관 기간.
6. **서버 시각 기준**: 모든 시각 판정(출근 가능 시간, 지각, 지난 날짜)은 서버 시각으로. 프로토타입은 주입 가능한 `now`를 씀.
7. **최저임금 상수**: 연도별로 바뀌므로 설정 테이블로 분리(현재 `minimumWage2026 = 10320`, 4대보험 근사 요율 `0.094`).
8. **서명 저장**: 서명 이미지·시각·IP 보관 방식과 계약서 PDF 생성.
9. **보건증·서명 이미지**: 파일 저장소 + 접근 권한(본인·소속 사장님만).
10. **한글 상수 enum**: 공지 `category`(`일반/중요/노무`), 유동 근무자 `part:"직원"`은 코드에 문자열로 박혀 있음. 서버에서는 enum/코드값으로 정리 후 표시 문구는 클라이언트에 둘지 결정.
11. **데모 전용 기능 제외**: 역할 전환, 데모 데이터 초기화(`resetDemo`), "동료 수락 (데모)", "사장님이 승인한 것으로 (데모)", POS QR 데모 출근은 **API로 만들지 않음**. 단 알바생 앱의 "승인 대기" 화면은 `GET /me`/`staff` 폴링 또는 푸시로 대체.
12. **데이터 이관**: 프로토타입 저장본은 `AppState.version = 3`이며 서버와 호환되지 않음. 이관 없이 새로 시작.
