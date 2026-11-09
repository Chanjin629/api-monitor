-- ERD Cloud 가져오기 전용 DDL
-- 실제 DB 스키마는 Flyway 마이그레이션과 docs/03-erd.md가 기준이다.
-- 다이어그램용으로 단순화한 부분:
--   1. check_results의 monitor_id FK는 관계선을 그리기 위한 것이다. 실제 테이블은 파티션 때문에 FK가 없다.
--   2. check_results의 날짜별 파티션은 넣지 않았다.
--   3. incidents.open_marker는 실제로는 PERSISTENT 생성 컬럼이다.

CREATE TABLE members (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '회원 ID',
  provider VARCHAR(20) NOT NULL COMMENT 'GITHUB, DEMO',
  provider_user_id VARCHAR(100) NOT NULL COMMENT 'GitHub 숫자 사용자 ID',
  username VARCHAR(100) NOT NULL COMMENT 'GitHub 로그인 이름',
  display_name VARCHAR(100) NULL COMMENT '표시 이름',
  avatar_url VARCHAR(500) NULL COMMENT '프로필 이미지',
  role VARCHAR(20) NOT NULL COMMENT 'USER, DEMO',
  last_login_at DATETIME(3) NULL COMMENT '마지막 로그인 시각',
  created_at DATETIME(3) NOT NULL COMMENT '생성 시각',
  updated_at DATETIME(3) NOT NULL COMMENT '수정 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_members_provider (provider, provider_user_id)
) COMMENT='회원';

CREATE TABLE refresh_tokens (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '토큰 ID',
  member_id BIGINT NOT NULL COMMENT '소유자',
  token_hash CHAR(64) NOT NULL COMMENT '토큰 SHA-256 해시',
  family_id CHAR(36) NOT NULL COMMENT '토큰 묶음 ID',
  expires_at DATETIME(3) NOT NULL COMMENT '만료 시각',
  used_at DATETIME(3) NULL COMMENT '재발급에 사용된 시각',
  revoked_at DATETIME(3) NULL COMMENT '무효화된 시각',
  created_at DATETIME(3) NOT NULL COMMENT '생성 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_refresh_tokens_hash (token_hash),
  KEY idx_refresh_tokens_member (member_id),
  KEY idx_refresh_tokens_family (family_id),
  KEY idx_refresh_tokens_expires (expires_at),
  CONSTRAINT fk_refresh_tokens_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE
) COMMENT='Refresh Token';

CREATE TABLE monitors (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '모니터 ID',
  member_id BIGINT NOT NULL COMMENT '소유자',
  name VARCHAR(100) NOT NULL COMMENT '모니터 이름',
  url VARCHAR(2048) NOT NULL COMMENT '검사할 URL',
  http_method VARCHAR(10) NOT NULL COMMENT 'GET, HEAD',
  interval_minutes SMALLINT NOT NULL COMMENT '검사 주기(분)',
  timeout_seconds SMALLINT NOT NULL DEFAULT 10 COMMENT 'Timeout(초)',
  expected_status_codes VARCHAR(50) NULL COMMENT '기대 상태 코드, NULL이면 2xx',
  request_headers_encrypted TEXT NULL COMMENT '암호화한 요청 헤더',
  created_at DATETIME(3) NOT NULL COMMENT '생성 시각',
  updated_at DATETIME(3) NOT NULL COMMENT '수정 시각',
  PRIMARY KEY (id),
  KEY idx_monitors_member (member_id),
  CONSTRAINT fk_monitors_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE
) COMMENT='모니터 설정';

CREATE TABLE monitor_states (
  monitor_id BIGINT NOT NULL COMMENT '모니터 ID',
  status VARCHAR(20) NOT NULL COMMENT 'UNKNOWN, UP, DOWN, PAUSED, BLOCKED',
  consecutive_failures INT NOT NULL DEFAULT 0 COMMENT '연속 실패 수',
  consecutive_successes INT NOT NULL DEFAULT 0 COMMENT '연속 성공 수',
  first_failure_at DATETIME(3) NULL COMMENT '연속 실패의 첫 시각',
  first_success_at DATETIME(3) NULL COMMENT '연속 성공의 첫 시각',
  last_checked_at DATETIME(3) NULL COMMENT '마지막 검사 시각',
  last_result VARCHAR(30) NULL COMMENT '마지막 결과',
  last_status_code SMALLINT NULL COMMENT '마지막 상태 코드',
  last_response_time_ms INT NULL COMMENT '마지막 응답 시간',
  next_check_at DATETIME(3) NULL COMMENT '다음 검사 시각',
  PRIMARY KEY (monitor_id),
  KEY idx_monitor_states_next (next_check_at),
  CONSTRAINT fk_monitor_states_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id) ON DELETE CASCADE
) COMMENT='모니터 실행 상태';

CREATE TABLE check_results (
  monitor_id BIGINT NOT NULL COMMENT '모니터 ID',
  checked_at DATETIME(3) NOT NULL COMMENT '검사 시작 시각',
  result VARCHAR(30) NOT NULL COMMENT '검사 결과',
  status_code SMALLINT NULL COMMENT '상태 코드',
  response_time_ms INT NULL COMMENT '응답 시간',
  PRIMARY KEY (monitor_id, checked_at),
  CONSTRAINT fk_check_results_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id)
) COMMENT='검사 원본 기록 (실제는 FK 없음, 날짜별 파티션)';

CREATE TABLE monitor_hourly_stats (
  monitor_id BIGINT NOT NULL COMMENT '모니터 ID',
  hour_start DATETIME NOT NULL COMMENT '시간의 시작',
  total_count INT NOT NULL COMMENT '전체 검사 수',
  success_count INT NOT NULL COMMENT '성공 수',
  blocked_count INT NOT NULL COMMENT 'SSRF 차단 수',
  response_time_sum_ms BIGINT NOT NULL COMMENT '응답 시간 합계',
  response_time_count INT NOT NULL COMMENT '응답 시간 건수',
  response_time_min_ms INT NULL COMMENT '최소 응답 시간',
  response_time_max_ms INT NULL COMMENT '최대 응답 시간',
  PRIMARY KEY (monitor_id, hour_start),
  CONSTRAINT fk_hourly_stats_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id) ON DELETE CASCADE
) COMMENT='시간별 집계';

CREATE TABLE incidents (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '장애 ID',
  monitor_id BIGINT NOT NULL COMMENT '모니터 ID',
  started_at DATETIME(3) NOT NULL COMMENT '첫 실패 시각',
  detected_at DATETIME(3) NOT NULL COMMENT '장애 판정 시각',
  resolved_at DATETIME(3) NULL COMMENT '복구 시각',
  duration_seconds INT NULL COMMENT '지속 시간(초)',
  cause VARCHAR(30) NOT NULL COMMENT '실패 종류',
  last_status_code SMALLINT NULL COMMENT '마지막 상태 코드',
  open_marker TINYINT NULL COMMENT '진행 중이면 1, 끝나면 NULL',
  PRIMARY KEY (id),
  UNIQUE KEY uk_incidents_open (monitor_id, open_marker),
  KEY idx_incidents_started (monitor_id, started_at),
  CONSTRAINT fk_incidents_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id) ON DELETE CASCADE
) COMMENT='장애 이력';

CREATE TABLE notification_channels (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '채널 ID',
  member_id BIGINT NOT NULL COMMENT '소유자',
  type VARCHAR(20) NOT NULL COMMENT 'DISCORD',
  name VARCHAR(100) NOT NULL COMMENT '채널 이름',
  webhook_url_encrypted TEXT NOT NULL COMMENT '암호화한 Webhook URL',
  enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '사용 여부',
  created_at DATETIME(3) NOT NULL COMMENT '생성 시각',
  updated_at DATETIME(3) NOT NULL COMMENT '수정 시각',
  PRIMARY KEY (id),
  KEY idx_channels_member (member_id),
  CONSTRAINT fk_channels_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE
) COMMENT='알림 채널';

CREATE TABLE notification_deliveries (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '발송 ID',
  incident_id BIGINT NOT NULL COMMENT '장애 ID',
  channel_id BIGINT NOT NULL COMMENT '채널 ID',
  event_type VARCHAR(20) NOT NULL COMMENT 'DOWN, RECOVERED',
  status VARCHAR(20) NOT NULL COMMENT 'PENDING, SENT, FAILED',
  attempt_count INT NOT NULL DEFAULT 0 COMMENT '시도 횟수',
  next_attempt_at DATETIME(3) NOT NULL COMMENT '다음 시도 시각',
  last_error_code VARCHAR(50) NULL COMMENT '마지막 오류 코드',
  sent_at DATETIME(3) NULL COMMENT '발송 완료 시각',
  created_at DATETIME(3) NOT NULL COMMENT '생성 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_deliveries_event (incident_id, channel_id, event_type),
  KEY idx_deliveries_status (status, next_attempt_at),
  CONSTRAINT fk_deliveries_incident FOREIGN KEY (incident_id) REFERENCES incidents (id) ON DELETE CASCADE,
  CONSTRAINT fk_deliveries_channel FOREIGN KEY (channel_id) REFERENCES notification_channels (id) ON DELETE CASCADE
) COMMENT='알림 예약 및 발송 이력';
