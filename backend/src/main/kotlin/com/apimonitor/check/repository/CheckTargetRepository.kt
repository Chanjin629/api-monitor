package com.apimonitor.check.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 검사 대상 선점. monitor의 JPA 엔티티를 쓰지 않고 필요한 컬럼만 SQL로 다룬다.
 * DB 시각은 JPA 설정과 같은 UTC 기준으로 읽고 쓴다.
 */
@Repository
class CheckTargetRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    /**
     * 검사 시각이 지난 모니터를 잠그고 가져온다. 트랜잭션 안에서 호출해야 커밋할 때까지 잠금이 유지된다.
     * SKIP LOCKED: 다른 트랜잭션이 이미 잠근 행은 기다리지 않고 건너뛴다. 서버가 여러 대여도 같은 모니터를 중복 검사하지 않는다.
     * 일시 정지한 모니터는 next_check_at이 NULL이라 조회되지 않는다.
     */
    fun findDueForUpdate(now: Instant, limit: Int): List<DueTarget> =
        jdbcTemplate.query(
            """
            SELECT s.monitor_id, m.url, m.http_method, m.interval_minutes, m.timeout_seconds,
                   m.expected_status_codes, m.request_headers_encrypted
            FROM monitor_states s
            JOIN monitors m ON m.id = s.monitor_id
            WHERE s.next_check_at <= ?
            ORDER BY s.next_check_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            { rs, _ ->
                DueTarget(
                    monitorId = rs.getLong("monitor_id"),
                    url = rs.getString("url"),
                    httpMethod = rs.getString("http_method"),
                    intervalMinutes = rs.getInt("interval_minutes"),
                    timeoutSeconds = rs.getInt("timeout_seconds"),
                    expectedStatusCodes = rs.getString("expected_status_codes"),
                    requestHeadersEncrypted = rs.getString("request_headers_encrypted"),
                )
            },
            utc(now),
            limit,
        )

    /** 실행 전에 다음 검사 시각을 옮긴다. 검사 중 서버가 죽어도 "실행 중" 상태가 남지 않는다. */
    fun moveNextCheckAt(nextCheckAts: Map<Long, Instant>) {
        if (nextCheckAts.isEmpty()) return
        jdbcTemplate.batchUpdate(
            "UPDATE monitor_states SET next_check_at = ? WHERE monitor_id = ?",
            nextCheckAts.map { (monitorId, nextCheckAt) -> arrayOf<Any>(utc(nextCheckAt), monitorId) },
        )
    }

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
}

/** 선점한 검사 대상. 헤더는 아직 암호화된 상태다. */
data class DueTarget(
    val monitorId: Long,
    val url: String,
    val httpMethod: String,
    val intervalMinutes: Int,
    val timeoutSeconds: Int,
    /** "200,301" 형식. null이면 2xx */
    val expectedStatusCodes: String?,
    val requestHeadersEncrypted: String?,
) {
    override fun toString(): String = "DueTarget(monitorId=$monitorId)"
}
