-- 첫 스키마. 설계 문서: docs/03-erd.md
-- 모든 시각은 UTC로 저장한다.

CREATE TABLE members (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    provider         VARCHAR(20)  NOT NULL COMMENT 'GITHUB, DEMO',
    provider_user_id VARCHAR(100) NOT NULL COMMENT '제공자의 사용자 ID',
    username         VARCHAR(100) NOT NULL,
    display_name     VARCHAR(100) NULL,
    avatar_url       VARCHAR(500) NULL,
    role             VARCHAR(20)  NOT NULL COMMENT 'USER, DEMO',
    last_login_at    DATETIME(3)  NULL,
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_members_provider (provider, provider_user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '회원';

CREATE TABLE refresh_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,
    token_hash CHAR(64)    NOT NULL COMMENT '토큰의 SHA-256 해시',
    family_id  CHAR(36)    NOT NULL COMMENT '같은 로그인에서 교체되어 온 토큰 묶음',
    expires_at DATETIME(3) NOT NULL,
    used_at    DATETIME(3) NULL COMMENT '재발급에 사용된 시각',
    revoked_at DATETIME(3) NULL COMMENT '로그아웃 또는 재사용 감지로 무효화된 시각',
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_hash (token_hash),
    KEY idx_refresh_tokens_member (member_id),
    KEY idx_refresh_tokens_family (family_id),
    KEY idx_refresh_tokens_expires (expires_at),
    CONSTRAINT fk_refresh_tokens_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Refresh Token';

CREATE TABLE monitors (
    id                        BIGINT        NOT NULL AUTO_INCREMENT,
    member_id                 BIGINT        NOT NULL,
    name                      VARCHAR(100)  NOT NULL,
    url                       VARCHAR(2048) NOT NULL,
    http_method               VARCHAR(10)   NOT NULL COMMENT 'GET, HEAD',
    interval_minutes          SMALLINT      NOT NULL COMMENT '1, 5, 10, 30, 60',
    timeout_seconds           SMALLINT      NOT NULL DEFAULT 10 COMMENT '1~30',
    expected_status_codes     VARCHAR(50)   NULL COMMENT '쉼표로 구분, NULL이면 2xx',
    request_headers_encrypted TEXT          NULL COMMENT '암호화한 헤더 JSON',
    created_at                DATETIME(3)   NOT NULL,
    updated_at                DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_monitors_member (member_id),
    CONSTRAINT fk_monitors_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '모니터 설정';

CREATE TABLE monitor_states (
    monitor_id            BIGINT      NOT NULL,
    status                VARCHAR(20) NOT NULL COMMENT 'UNKNOWN, UP, DOWN, PAUSED, BLOCKED',
    consecutive_failures  INT         NOT NULL DEFAULT 0,
    consecutive_successes INT         NOT NULL DEFAULT 0,
    first_failure_at      DATETIME(3) NULL COMMENT '연속 실패의 첫 시각',
    first_success_at      DATETIME(3) NULL COMMENT '연속 성공의 첫 시각',
    last_checked_at       DATETIME(3) NULL,
    last_result           VARCHAR(30) NULL,
    last_status_code      SMALLINT    NULL,
    last_response_time_ms INT         NULL,
    next_check_at         DATETIME(3) NULL COMMENT 'NULL이면 검사 대상이 아니다',
    PRIMARY KEY (monitor_id),
    KEY idx_monitor_states_next (next_check_at),
    CONSTRAINT fk_monitor_states_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '모니터 실행 상태';

-- 검사 원본 기록. 날짜별 RANGE 파티션으로 나누고, 보관 기간(14일)이 지난 파티션을 통째로 삭제한다.
-- 파티션 테이블은 외래 키를 지원하지 않으므로 monitors에 대한 FK가 없다.
-- 날짜별 파티션은 12단계의 관리 작업이 p_future를 나눠 만든다.
CREATE TABLE check_results (
    monitor_id       BIGINT      NOT NULL,
    checked_at       DATETIME(3) NOT NULL COMMENT '검사 시작 시각',
    result           VARCHAR(30) NOT NULL,
    status_code      SMALLINT    NULL,
    response_time_ms INT         NULL,
    PRIMARY KEY (monitor_id, checked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '검사 원본 기록'
PARTITION BY RANGE (TO_DAYS(checked_at)) (
    PARTITION p_initial VALUES LESS THAN (TO_DAYS('2026-11-14')),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);

CREATE TABLE monitor_hourly_stats (
    monitor_id           BIGINT   NOT NULL,
    hour_start           DATETIME NOT NULL,
    total_count          INT      NOT NULL,
    success_count        INT      NOT NULL,
    blocked_count        INT      NOT NULL,
    response_time_sum_ms BIGINT   NOT NULL,
    response_time_count  INT      NOT NULL,
    response_time_min_ms INT      NULL,
    response_time_max_ms INT      NULL,
    PRIMARY KEY (monitor_id, hour_start),
    CONSTRAINT fk_hourly_stats_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '시간별 집계';

-- open_marker는 진행 중인 장애(resolved_at IS NULL)일 때만 1이 되는 생성 컬럼이다.
-- 유니크 인덱스는 NULL끼리 중복을 허용하므로, 진행 중인 장애는 모니터당 하나만 저장된다.
CREATE TABLE incidents (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    monitor_id       BIGINT      NOT NULL,
    started_at       DATETIME(3) NOT NULL COMMENT '첫 번째 실패 시각',
    detected_at      DATETIME(3) NOT NULL COMMENT '장애로 판정한 시각',
    resolved_at      DATETIME(3) NULL COMMENT 'NULL이면 진행 중',
    duration_seconds INT         NULL,
    cause            VARCHAR(30) NOT NULL,
    last_status_code SMALLINT    NULL,
    open_marker      TINYINT AS (IF(resolved_at IS NULL, 1, NULL)) PERSISTENT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_incidents_open (monitor_id, open_marker),
    KEY idx_incidents_started (monitor_id, started_at),
    CONSTRAINT fk_incidents_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '장애 이력';

CREATE TABLE notification_channels (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    member_id             BIGINT       NOT NULL,
    type                  VARCHAR(20)  NOT NULL COMMENT 'DISCORD',
    name                  VARCHAR(100) NOT NULL,
    webhook_url_encrypted TEXT         NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_channels_member (member_id),
    CONSTRAINT fk_channels_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '알림 채널';

CREATE TABLE notification_deliveries (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    incident_id     BIGINT      NOT NULL,
    channel_id      BIGINT      NOT NULL,
    event_type      VARCHAR(20) NOT NULL COMMENT 'DOWN, RECOVERED',
    status          VARCHAR(20) NOT NULL COMMENT 'PENDING, SENT, FAILED',
    attempt_count   INT         NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    last_error_code VARCHAR(50) NULL,
    sent_at         DATETIME(3) NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_deliveries_event (incident_id, channel_id, event_type),
    KEY idx_deliveries_status (status, next_attempt_at),
    CONSTRAINT fk_deliveries_incident FOREIGN KEY (incident_id) REFERENCES incidents (id) ON DELETE CASCADE,
    CONSTRAINT fk_deliveries_channel FOREIGN KEY (channel_id) REFERENCES notification_channels (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '알림 예약 및 발송 이력';
