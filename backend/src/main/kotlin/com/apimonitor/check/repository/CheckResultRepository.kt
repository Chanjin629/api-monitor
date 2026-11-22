package com.apimonitor.check.repository

import com.apimonitor.check.dto.CheckResult
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 검사 원본 기록(check_results) 저장. 복합 키에 쓰기 전용이라 JPA 대신 JdbcTemplate을 쓴다.
 * 날짜별 파티션은 [com.apimonitor.check.scheduler.PartitionManager]가 관리한다.
 */
@Repository
class CheckResultRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    /**
     * 저장하고, 새로 저장했으면 true를 돌려준다.
     * 같은 (monitor_id, checked_at)이 이미 있으면 무시한다. 재시도나 중복 전달로 같은 결과가 다시 와도 한 번만 반영된다.
     */
    fun insertIfAbsent(result: CheckResult): Boolean =
        jdbcTemplate.update(
            """
            INSERT IGNORE INTO check_results (monitor_id, checked_at, result, status_code, response_time_ms)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            result.monitorId,
            LocalDateTime.ofInstant(result.checkedAt, ZoneOffset.UTC),
            result.type.name,
            result.statusCode,
            result.responseTimeMs,
        ) > 0

    /**
     * 모니터별 가동률(%)을 돌려준다. 성공 수 ÷ (전체 수 - 차단 수)이며, 차단만 있거나 기록이 없는 모니터는 결과에 없다.
     * 기본 키 (monitor_id, checked_at)로 모니터별 기간을 바로 찾는다.
     */
    fun uptimeSince(monitorIds: Collection<Long>, since: Instant): Map<Long, Double> {
        if (monitorIds.isEmpty()) return emptyMap()
        return jdbcTemplate.query(
            """
            SELECT monitor_id, SUM(result = 'SUCCESS') AS success, COUNT(*) - SUM(result = 'BLOCKED') AS counted
            FROM check_results
            WHERE monitor_id IN (${monitorIds.joinToString(",") { "?" }}) AND checked_at >= ?
            GROUP BY monitor_id
            """.trimIndent(),
            { rs, _ -> Triple(rs.getLong("monitor_id"), rs.getLong("success"), rs.getLong("counted")) },
            *monitorIds.toTypedArray(),
            LocalDateTime.ofInstant(since, ZoneOffset.UTC),
        ).filter { it.third > 0 }.associate { (id, success, counted) -> id to Uptime.percent(success, counted)!! }
    }
}

/** 가동률 계산을 한곳에 둔다. */
object Uptime {
    /** 소수 둘째 자리까지의 백분율. 분모가 0이면 null */
    fun percent(success: Long, counted: Long): Double? =
        if (counted <= 0) null else Math.round(success * 10000.0 / counted) / 100.0
}
