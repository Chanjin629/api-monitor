package com.apimonitor.check.repository

import com.apimonitor.check.dto.CheckResult
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
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
}
