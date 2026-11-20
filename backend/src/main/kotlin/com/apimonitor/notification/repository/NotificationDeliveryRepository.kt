package com.apimonitor.notification.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 보낼 알림을 예약한다 (Outbox). 실제 발송은 13단계의 발송기가 이 행을 읽어 처리한다.
 * 장애 기록과 같은 트랜잭션에서 저장하므로, 서버가 중간에 죽어도 알림이 사라지지 않는다.
 */
@Repository
class NotificationDeliveryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    /**
     * 모니터 주인의 활성 채널마다 예약 행을 하나씩 만든다. 만든 개수를 돌려준다.
     * (incident_id, channel_id, event_type) 유니크 키 때문에 같은 알림은 두 번 예약되지 않는다.
     */
    fun reserve(incidentId: Long, monitorId: Long, eventType: NotificationEventType, now: Instant): Int {
        val utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
        return jdbcTemplate.update(
            """
            INSERT IGNORE INTO notification_deliveries
                (incident_id, channel_id, event_type, status, attempt_count, next_attempt_at, created_at)
            SELECT ?, c.id, ?, 'PENDING', 0, ?, ?
            FROM notification_channels c
            JOIN monitors m ON m.member_id = c.member_id
            WHERE m.id = ? AND c.enabled = TRUE
            """.trimIndent(),
            incidentId,
            eventType.name,
            utcNow,
            utcNow,
            monitorId,
        )
    }
}

enum class NotificationEventType {
    DOWN,
    RECOVERED,
}
