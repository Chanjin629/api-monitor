package com.apimonitor.check.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 시간별 집계(monitor_hourly_stats). 7일·30일 그래프와 가동률은 원본 기록 대신 이 집계에서 읽는다. (docs/03-erd.md 3.1)
 * 평균 대신 합계와 건수를 저장해, 시간을 일 단위로 묶어도 평균이 정확하다.
 */
@Repository
class HourlyStatsRepository(
    private val jdbcTemplate: JdbcTemplate,
) {

    /**
     * [from, to) 구간의 원본 기록을 모니터·시간별로 다시 계산해 덮어쓴다. 영향받은 행 수를 돌려준다.
     * 여러 번 실행해도 결과가 같다. 삭제된 모니터의 원본 기록은 집계하지 않는다(집계 테이블은 모니터에 FK가 있다).
     */
    fun aggregate(from: Instant, to: Instant): Int =
        jdbcTemplate.update(
            """
            INSERT INTO monitor_hourly_stats
                (monitor_id, hour_start, total_count, success_count, blocked_count,
                 response_time_sum_ms, response_time_count, response_time_min_ms, response_time_max_ms)
            SELECT c.monitor_id,
                   DATE_FORMAT(c.checked_at, '%Y-%m-%d %H:00:00') AS hour_start,
                   COUNT(*),
                   SUM(c.result = 'SUCCESS'),
                   SUM(c.result = 'BLOCKED'),
                   COALESCE(SUM(c.response_time_ms), 0),
                   COUNT(c.response_time_ms),
                   MIN(c.response_time_ms),
                   MAX(c.response_time_ms)
            FROM check_results c
            JOIN monitors m ON m.id = c.monitor_id
            WHERE c.checked_at >= ? AND c.checked_at < ?
            GROUP BY c.monitor_id, hour_start
            ON DUPLICATE KEY UPDATE
                total_count = VALUES(total_count),
                success_count = VALUES(success_count),
                blocked_count = VALUES(blocked_count),
                response_time_sum_ms = VALUES(response_time_sum_ms),
                response_time_count = VALUES(response_time_count),
                response_time_min_ms = VALUES(response_time_min_ms),
                response_time_max_ms = VALUES(response_time_max_ms)
            """.trimIndent(),
            utc(from),
            utc(to),
        )

    /** 보관 기간(365일)이 지난 집계를 지운다. 지운 행 수를 돌려준다. */
    fun deleteBefore(cutoff: Instant): Int =
        jdbcTemplate.update("DELETE FROM monitor_hourly_stats WHERE hour_start < ?", utc(cutoff))

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
}
