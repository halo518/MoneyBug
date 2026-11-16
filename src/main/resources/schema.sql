-- 데이터베이스 생성 및 선택 (한글 깨짐 방지 설정 포함)
CREATE DATABASE IF NOT EXISTS moneybug DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE moneybug;

-- =====================================================================
-- [변경 이력] api_design.md(§3, §8)에 맞춰 엔티티 점검 후 수정
--   * 이 파일은 '신규 DB 생성'용 전체 스키마(1~15번)이다. 앱은 ddl-auto=update 로도 동작한다.
--   * 기존 DB를 업그레이드하려면 맨 아래 '기존 DB 마이그레이션' 구역을 먼저 실행할 것.
--   * [변경]=기존 테이블 수정, [신규]=새 테이블, [추가]=새 컬럼
-- =====================================================================

-- 1. 사용자 테이블
-- [변경] 테이블명 user -> users (설계서 §8, 예약어 충돌 방지)
-- [변경] role 컬럼 제거: 역할은 서버에 저장하지 않고 Store.employer / store_worker 관계로 결정(설계서 §1.2)
-- [변경] contact NOT NULL -> NULL 허용 (카카오 동의 화면에서 선택 항목)
CREATE TABLE IF NOT EXISTS users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    kakao_id VARCHAR(100) UNIQUE NOT NULL COMMENT '소셜 로그인 식별자',
    name VARCHAR(50) NOT NULL COMMENT '짧은 이름(아바타 글자 = 첫 글자)',
    full_name VARCHAR(50) COMMENT '[추가] 전체 이름',
    email VARCHAR(100) COMMENT '[추가] 이메일(카카오 필수 동의)',
    profile_image VARCHAR(500) COMMENT '[추가] 프로필 사진 URL',
    contact VARCHAR(20) COMMENT '연락처(선택)',
    phone_consent BOOLEAN NOT NULL DEFAULT FALSE COMMENT '[추가] 연락처 제공 동의 여부',
    cert_expiry DATE COMMENT '[추가] 보건증 만료일(사람 단위)',
    cert_alert_days VARCHAR(50) COMMENT '[추가] 만료 며칠 전 알림, 쉼표 구분(예: 30,14,7,0)',
    cert_photo_url VARCHAR(500) COMMENT '[추가] 보건증 사진 경로'
);

-- 2. 매장 테이블
-- [추가] address, code, notice_required_default, substitute_push, default_deduction, schedule_confirmed_at
CREATE TABLE IF NOT EXISTS store (
    store_id INT AUTO_INCREMENT PRIMARY KEY,
    employer_id INT NOT NULL COMMENT '고용주(User) 참조',
    store_name VARCHAR(100) NOT NULL COMMENT '상호명',
    address VARCHAR(255) COMMENT '[추가] 도로명 주소',
    code VARCHAR(20) UNIQUE COMMENT '[추가] 직원 가입용 매장 코드(예: CAFE-8K2D)',
    is_under_5 BOOLEAN NOT NULL DEFAULT TRUE COMMENT '5인 미만 여부(true면 가산수당 미적용)',
    notice_required_default BOOLEAN NOT NULL DEFAULT TRUE COMMENT '[추가] 공지 작성 시 필독 기본값',
    substitute_push BOOLEAN NOT NULL DEFAULT TRUE COMMENT '[추가] 대타 요청 시 동료 푸시',
    default_deduction VARCHAR(20) DEFAULT 'BUSINESS_TAX' COMMENT '[추가] 가입 승인 시 기본 공제(BUSINESS_TAX/4_INSURANCE/NONE)',
    schedule_confirmed_at DATETIME COMMENT '[추가] 근무표 확정 시각',
    FOREIGN KEY (employer_id) REFERENCES users(user_id) ON DELETE CASCADE
);

-- 3. 매장-직원 연결 테이블 (N:M 해소) = 설계서 Staff
-- [변경] status 를 enum 값(PENDING/ACTIVE/LEFT/REJECTED)으로 제한. (store_id, worker_id) unique 는 재가입 허용을 위해 두지 않음
-- [추가] part, kind, weekly_holiday_auto, premium, tone, joined_at, requested_at
CREATE TABLE IF NOT EXISTS store_worker (
    mapping_id INT AUTO_INCREMENT PRIMARY KEY,
    store_id INT NOT NULL,
    worker_id INT NOT NULL,
    hourly_wage INT NOT NULL COMMENT '적용 시급',
    tax_type ENUM('BUSINESS_TAX', '4_INSURANCE', 'NONE') NOT NULL DEFAULT 'NONE' COMMENT '공제 유형',
    status ENUM('PENDING', 'ACTIVE', 'LEFT', 'REJECTED') NOT NULL DEFAULT 'ACTIVE' COMMENT '근무 상태(가입 신청=PENDING)',
    part VARCHAR(50) COMMENT '[추가] 파트명(유동 근무자는 "직원")',
    kind ENUM('MANAGER', 'FULLTIME', 'PART_TIMER', 'MINOR') DEFAULT 'PART_TIMER' COMMENT '[추가] 직원 구분(MANAGER/FULLTIME은 유동 근무)',
    weekly_holiday_auto BOOLEAN NOT NULL DEFAULT TRUE COMMENT '[추가] 주휴수당 자동 계산',
    premium BOOLEAN NOT NULL DEFAULT FALSE COMMENT '[추가] 가산수당 적용',
    tone ENUM('PRIMARY', 'ACCENT', 'INFO', 'DANGER') DEFAULT 'PRIMARY' COMMENT '[추가] 아바타 색(표시용)',
    joined_at DATE COMMENT '[추가] 입사일(승인일)',
    requested_at DATETIME COMMENT '[추가] 가입 신청 시각',
    FOREIGN KEY (store_id) REFERENCES store(store_id) ON DELETE CASCADE,
    FOREIGN KEY (worker_id) REFERENCES users(user_id) ON DELETE CASCADE
);

-- 4. 근무표 테이블 = 설계서 Shift
-- [변경] start_time/end_time(TIME) -> start_min/end_min(INT, 하루 기준 분). end_min <= start_min 이면 야간(다음 날까지)
-- [추가] part, break_min, kind, time_tbd, original_mapping_id, version(낙관적 락)
CREATE TABLE IF NOT EXISTS schedule (
    schedule_id INT AUTO_INCREMENT PRIMARY KEY,
    mapping_id INT NOT NULL COMMENT '직원 매핑 참조',
    work_date DATE NOT NULL COMMENT '근무 일자',
    start_min INT NOT NULL COMMENT '예정 출근(하루 기준 분, 09:30=570)',
    end_min INT NOT NULL COMMENT '예정 퇴근(하루 기준 분). start_min 이하이면 다음 날',
    part VARCHAR(50) COMMENT '[추가] 파트명',
    break_min INT NOT NULL DEFAULT 0 COMMENT '[추가] 휴게(분)',
    kind ENUM('WORK', 'LEAVE', 'OFF') DEFAULT 'WORK' COMMENT '[추가] 근무/연차(유급)/휴무(무급)',
    time_tbd BOOLEAN NOT NULL DEFAULT FALSE COMMENT '[추가] 시간 미정(시각은 추천값)',
    is_substitute BOOLEAN NOT NULL DEFAULT FALSE COMMENT '대타 근무 여부(주휴수당 제외)',
    original_mapping_id INT COMMENT '[추가] 대타로 바뀐 경우 원래 근무자',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '[추가] 낙관적 락',
    INDEX idx_schedule_mapping_date (mapping_id, work_date),
    FOREIGN KEY (mapping_id) REFERENCES store_worker(mapping_id) ON DELETE CASCADE,
    FOREIGN KEY (original_mapping_id) REFERENCES store_worker(mapping_id) ON DELETE SET NULL
);

-- 5. 출퇴근 기록 테이블
-- [변경] schedule_id UNIQUE: 근무당 출근 기록 1건 (설계서 §8)
-- [변경] check_method 에 MANUAL 추가 (사장님 수동 생성)
-- [추가] edit_reason
CREATE TABLE IF NOT EXISTS attendance (
    attendance_id INT AUTO_INCREMENT PRIMARY KEY,
    schedule_id INT NOT NULL UNIQUE,
    actual_start DATETIME COMMENT '실제 출근 시간',
    actual_end DATETIME COMMENT '실제 퇴근 시간',
    overtime_minutes INT DEFAULT 0 COMMENT '연장 근무 시간(분)',
    check_method ENUM('QR', 'MANUAL') NOT NULL COMMENT '인증 방식',
    holiday_pay_rate INT DEFAULT 0 COMMENT '휴일 근로 수당 가산율(%)',
    edit_reason TEXT COMMENT '[추가] 근태 수정 사유',
    FOREIGN KEY (schedule_id) REFERENCES schedule(schedule_id) ON DELETE CASCADE
);

-- 6. 대타/휴무 신청 테이블
-- [변경] status: PENDING/ACCEPTED/CANCELED -> OPEN/ACCEPTED/APPROVED/REJECTED/CANCELLED (설계서 SubStatus)
-- [추가] notify_peers, created_at, notified_at
CREATE TABLE IF NOT EXISTS substitute_request (
    request_id INT AUTO_INCREMENT PRIMARY KEY,
    schedule_id INT NOT NULL,
    requester_id INT NOT NULL COMMENT '신청자 ID',
    taker_id INT COMMENT '대타 후보 ID (수락 전엔 NULL)',
    reason TEXT COMMENT '휴무 사유',
    status ENUM('OPEN', 'ACCEPTED', 'APPROVED', 'REJECTED', 'CANCELLED') NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACCEPTED=진행 중',
    notify_peers BOOLEAN NOT NULL DEFAULT TRUE COMMENT '[추가] 동료 알림 여부',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '[추가]',
    notified_at DATETIME COMMENT '[추가] 마지막 재알림 시각',
    FOREIGN KEY (schedule_id) REFERENCES schedule(schedule_id) ON DELETE CASCADE,
    FOREIGN KEY (requester_id) REFERENCES users(user_id) ON DELETE CASCADE,
    FOREIGN KEY (taker_id) REFERENCES users(user_id) ON DELETE SET NULL
);

-- 7. 급여 정산 테이블 (오류 수정: year_month -> payroll_month)
-- [추가] base_pay(기본급), gross_pay(총액), (mapping_id, payroll_month) UNIQUE
-- final_salary 는 실수령액(net) = gross_pay - deduction_amount
CREATE TABLE IF NOT EXISTS payroll (
    payroll_id INT AUTO_INCREMENT PRIMARY KEY,
    mapping_id INT NOT NULL,
    payroll_month VARCHAR(7) NOT NULL COMMENT '정산 연월 (예: 2026-10)',
    total_work_time INT NOT NULL COMMENT '총 근무 시간(분 단위 권장)',
    base_pay INT DEFAULT 0 COMMENT '[추가] 기본급',
    total_holiday_allowance INT DEFAULT 0 COMMENT '휴일 가산 수당 총액',
    total_overtime_allowance INT DEFAULT 0 COMMENT '연장 근로 수당 총액',
    weekly_holiday_allowance INT DEFAULT 0 COMMENT '주휴수당 금액',
    gross_pay INT DEFAULT 0 COMMENT '[추가] 총액(공제 전)',
    deduction_amount INT DEFAULT 0 COMMENT '공제 금액(세금/4대보험)',
    final_salary INT NOT NULL COMMENT '실수령액',
    UNIQUE KEY uk_payroll_mapping_month (mapping_id, payroll_month),
    FOREIGN KEY (mapping_id) REFERENCES store_worker(mapping_id) ON DELETE CASCADE
);

-- 8. (삭제) 노무 서류 테이블 compliance
-- [변경] 보건증 만료일 -> users.cert_expiry, 계약서 경로 -> contract.doc_url 로 이전하여 테이블 제거
--        (기존 DB는 아래 마이그레이션 참고)

-- 9. 공지사항 테이블
-- [추가] title, category, is_required, is_pinned, reminded_at
CREATE TABLE IF NOT EXISTS notice (
    notice_id INT AUTO_INCREMENT PRIMARY KEY,
    store_id INT NOT NULL,
    title VARCHAR(200) COMMENT '[추가] 제목',
    content TEXT NOT NULL,
    category ENUM('GENERAL', 'IMPORTANT', 'LABOR') DEFAULT 'GENERAL' COMMENT '[추가] 일반/중요/노무',
    is_required BOOLEAN NOT NULL DEFAULT FALSE COMMENT '[추가] 필독',
    is_pinned BOOLEAN NOT NULL DEFAULT FALSE COMMENT '[추가] 고정',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    reminded_at DATETIME COMMENT '[추가] 마지막 재알림 시각',
    FOREIGN KEY (store_id) REFERENCES store(store_id) ON DELETE CASCADE
);

-- 10. [신규] 알림 테이블 (설계서 §3.9, §5)
CREATE TABLE IF NOT EXISTS notification (
    notification_id INT AUTO_INCREMENT PRIMARY KEY,
    store_id INT NOT NULL,
    to_user_id INT NOT NULL COMMENT '수신자',
    type ENUM('SUB', 'NOTICE', 'CERT', 'PAY', 'JOIN', 'CONTRACT') NOT NULL COMMENT '알림 종류',
    title VARCHAR(200) NOT NULL,
    body TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    is_read BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'read 는 예약어라 is_read',
    ref_id INT COMMENT 'type별 참조 id',
    INDEX idx_notification_to_store_created (to_user_id, store_id, created_at),
    FOREIGN KEY (store_id) REFERENCES store(store_id) ON DELETE CASCADE,
    FOREIGN KEY (to_user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

-- 11. [신규] 근로계약서 테이블 (설계서 §3.3). 직원당 여러 건 이력 보관, 최신 건이 현재 계약서
CREATE TABLE IF NOT EXISTS contract (
    contract_id INT AUTO_INCREMENT PRIMARY KEY,
    mapping_id INT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    wage INT NOT NULL COMMENT '시급(최저임금 이상)',
    weekly_hours INT NOT NULL,
    work_days VARCHAR(50) NOT NULL COMMENT '자유 텍스트(예: 월·수·금)',
    duty VARCHAR(100) NOT NULL COMMENT '업무 내용',
    owner_signed_at DATETIME,
    employee_signed_at DATETIME,
    employee_signature_url VARCHAR(500) COMMENT '직원 서명 이미지',
    employee_signed_ip VARCHAR(45) COMMENT '서명 IP',
    terms_version VARCHAR(20) COMMENT '약관 버전',
    doc_url VARCHAR(255) COMMENT '계약서 PDF 경로(기존 compliance.contract_doc_url 대체)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (mapping_id) REFERENCES store_worker(mapping_id) ON DELETE CASCADE
);

-- 12. [신규] 매장 파트 테이블 (설계서 §3.2 parts[])
CREATE TABLE IF NOT EXISTS store_part (
    part_id INT AUTO_INCREMENT PRIMARY KEY,
    store_id INT NOT NULL,
    name VARCHAR(50) NOT NULL COMMENT '파트명(오픈/미들/마감 등)',
    start_min INT NOT NULL,
    end_min INT NOT NULL,
    need INT NOT NULL DEFAULT 1 COMMENT '하루 필요 인원',
    UNIQUE KEY uk_store_part_name (store_id, name),
    FOREIGN KEY (store_id) REFERENCES store(store_id) ON DELETE CASCADE
);

-- 13. [신규] 대타 지원자 조인 테이블 (설계서 §3.7 applicantIds)
CREATE TABLE IF NOT EXISTS substitute_applicant (
    applicant_row_id INT AUTO_INCREMENT PRIMARY KEY,
    request_id INT NOT NULL,
    applicant_id INT NOT NULL,
    applied_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_substitute_applicant (request_id, applicant_id),
    FOREIGN KEY (request_id) REFERENCES substitute_request(request_id) ON DELETE CASCADE,
    FOREIGN KEY (applicant_id) REFERENCES users(user_id) ON DELETE CASCADE
);

-- 14. [신규] 공지 읽음 테이블 (설계서 §3.8 readBy)
CREATE TABLE IF NOT EXISTS notice_read (
    read_id INT AUTO_INCREMENT PRIMARY KEY,
    notice_id INT NOT NULL,
    mapping_id INT NOT NULL COMMENT '읽은 직원',
    read_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_notice_read (notice_id, mapping_id),
    FOREIGN KEY (notice_id) REFERENCES notice(notice_id) ON DELETE CASCADE,
    FOREIGN KEY (mapping_id) REFERENCES store_worker(mapping_id) ON DELETE CASCADE
);

-- =====================================================================
-- 기존 DB 마이그레이션 (이미 user/schedule 등이 있는 DB에서만, 위에서부터 순서대로 실행)
-- ddl-auto=update 는 컬럼 삭제/타입 변경/ENUM 변경/테이블 개명을 하지 않으므로 수동 처리가 필요하다.
-- 신규 DB(위 CREATE 문으로 만든 경우)에서는 실행하지 말 것. 실행 전 반드시 백업.
-- =====================================================================
-- (a) user -> users 개명, role 제거, contact NULL 허용
-- RENAME TABLE user TO users;
-- ALTER TABLE users DROP COLUMN role;
-- ALTER TABLE users MODIFY contact VARCHAR(20) NULL;
--
-- (b) 근무 시간을 분 단위로 변환 후 TIME 컬럼 제거 (앱을 한 번 띄워 start_min/end_min 이 생긴 뒤 실행)
-- UPDATE schedule SET start_min = HOUR(start_time) * 60 + MINUTE(start_time),
--                     end_min   = HOUR(end_time)   * 60 + MINUTE(end_time);
-- ALTER TABLE schedule DROP COLUMN start_time, DROP COLUMN end_time;
--
-- (c) ENUM 값 변경 (기존 데이터 값 먼저 치환)
-- ALTER TABLE substitute_request MODIFY status VARCHAR(20) NOT NULL DEFAULT 'OPEN';
-- UPDATE substitute_request SET status = 'OPEN' WHERE status = 'PENDING';
-- UPDATE substitute_request SET status = 'CANCELLED' WHERE status = 'CANCELED';
-- ALTER TABLE substitute_request MODIFY status ENUM('OPEN','ACCEPTED','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'OPEN';
-- ALTER TABLE attendance MODIFY check_method ENUM('QR','MANUAL') NOT NULL;
-- UPDATE store_worker SET status = 'ACTIVE' WHERE status NOT IN ('PENDING','ACTIVE','LEFT','REJECTED');
--
-- (d) compliance 데이터 이전 후 테이블 제거
-- UPDATE users u JOIN (SELECT sw.worker_id, MAX(c.health_cert_expiry) AS expiry
--                      FROM compliance c JOIN store_worker sw ON sw.mapping_id = c.mapping_id
--                      GROUP BY sw.worker_id) x ON x.worker_id = u.user_id
--    SET u.cert_expiry = x.expiry;
-- DROP TABLE compliance;
