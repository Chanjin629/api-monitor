package com.apimonitor.notification.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
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

    /**
     * 보낼 차례가 된 예약을 잠그고 다음 시도 시각을 [lease] 뒤로 옮긴다. 트랜잭션 안에서 호출한다.
     * 보내는 도중 서버가 죽어도 lease가 지나면 다시 시도되므로 "발송 중" 상태가 필요 없다.
     * SKIP LOCKED로 다른 서버가 잡은 예약은 건너뛴다.
     */
    fun claimDue(now: Instant, limit: Int, lease: Duration): List<Long> {
        val ids = jdbcTemplate.queryForList(
            """
            SELECT id FROM notification_deliveries
            WHERE status = 'PENDING' AND next_attempt_at <= ?
            ORDER BY next_attempt_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            Long::class.java,
            utc(now),
            limit,
        ).filterNotNull()
        if (ids.isNotEmpty()) {
            jdbcTemplate.update(
                "UPDATE notification_deliveries SET next_attempt_at = ? WHERE id IN (${ids.joinToString(",") { "?" }})",
                utc(now.plus(lease)),
                *ids.toTypedArray(),
            )
        }
        return ids
    }

    /** 발송에 필요한 값을 함께 읽는다. 잠그지 않는 일반 조회다. */
    fun findDetails(ids: List<Long>): List<DueDelivery> {
        if (ids.isEmpty()) return emptyList()
        return jdbcTemplate.query(
            """
            SELECT d.id, d.event_type, d.attempt_count, c.webhook_url_encrypted, c.enabled,
                   m.name AS monitor_name, m.url, i.started_at, i.resolved_at, i.cause, i.last_status_code
            FROM notification_deliveries d
            JOIN notification_channels c ON c.id = d.channel_id
            JOIN incidents i ON i.id = d.incident_id
            JOIN monitors m ON m.id = i.monitor_id
            WHERE d.id IN (${ids.joinToString(",") { "?" }})
            ORDER BY d.id
            """.trimIndent(),
            { rs, _ ->
                DueDelivery(
                    id = rs.getLong("id"),
                    eventType = NotificationEventType.valueOf(rs.getString("event_type")),
                    attemptCount = rs.getInt("attempt_count"),
                    webhookUrlEncrypted = rs.getString("webhook_url_encrypted"),
                    channelEnabled = rs.getBoolean("enabled"),
                    monitorName = rs.getString("monitor_name"),
                    monitorUrl = rs.getString("url"),
                    startedAt = rs.getObject("started_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
                    resolvedAt = rs.getObject("resolved_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC),
                    cause = rs.getString("cause"),
                    lastStatusCode = (rs.getObject("last_status_code") as Number?)?.toInt(),
                )
            },
            *ids.toTypedArray(),
        )
    }

    fun markSent(id: Long, attemptCount: Int, sentAt: Instant) {
        jdbcTemplate.update(
            "UPDATE notification_deliveries SET status = 'SENT', attempt_count = ?, sent_at = ?, last_error_code = NULL WHERE id = ?",
            attemptCount, utc(sentAt), id,
        )
    }

    fun markRetry(id: Long, attemptCount: Int, nextAttemptAt: Instant, errorCode: String) {
        jdbcTemplate.update(
            "UPDATE notification_deliveries SET attempt_count = ?, next_attempt_at = ?, last_error_code = ? WHERE id = ?",
            attemptCount, utc(nextAttemptAt), errorCode, id,
        )
    }

    fun markFailed(id: Long, attemptCount: Int, errorCode: String) {
        jdbcTemplate.update(
            "UPDATE notification_deliveries SET status = 'FAILED', attempt_count = ?, last_error_code = ? WHERE id = ?",
            attemptCount, errorCode, id,
        )
    }

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
}

/** 발송할 알림 하나. Webhook URL은 아직 암호화된 상태다. */
data class DueDelivery(
    val id: Long,
    val eventType: NotificationEventType,
    val attemptCount: Int,
    val webhookUrlEncrypted: String,
    val channelEnabled: Boolean,
    val monitorName: String,
    val monitorUrl: String,
    val startedAt: Instant,
    val resolvedAt: Instant?,
    val cause: String,
    val lastStatusCode: Int?,
) {
    override fun toString(): String = "DueDelivery(id=$id, eventType=$eventType, attemptCount=$attemptCount)"
}

enum class NotificationEventType {
    DOWN,
    RECOVERED,
}
