package com.apimonitor.dashboard.service

import com.apimonitor.check.repository.Uptime
import com.apimonitor.dashboard.dto.CheckItem
import com.apimonitor.dashboard.dto.CheckListResponse
import com.apimonitor.dashboard.dto.RecentIncident
import com.apimonitor.dashboard.dto.StatsPoint
import com.apimonitor.dashboard.dto.StatsResponse
import com.apimonitor.dashboard.dto.SummaryResponse
import com.apimonitor.global.error.BusinessException
import com.apimonitor.global.error.ErrorCode
import com.apimonitor.global.response.Cursor
import com.apimonitor.global.response.ErrorDetail
import com.apimonitor.incident.dto.IncidentStatus
import com.apimonitor.incident.dto.MonitorRef
import com.apimonitor.monitor.domain.MonitorStatus
import com.apimonitor.monitor.repository.MonitorRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 대시보드와 모니터 상세 화면의 조회 전용 쿼리.
 * DB 시각은 UTC 기준이고, 시간 묶음은 DB의 시간대 설정에 영향받지 않도록 기준 시각과의 차이로 계산한다.
 */
@Service
class DashboardQueryService(
    private val jdbcTemplate: JdbcTemplate,
    private val monitorRepository: MonitorRepository,
    private val clock: Clock,
) {

    fun getSummary(memberId: Long): SummaryResponse {
        val counts = jdbcTemplate.query(
            """
            SELECT s.status, COUNT(*) AS cnt FROM monitor_states s
            JOIN monitors m ON m.id = s.monitor_id
            WHERE m.member_id = ? GROUP BY s.status
            """.trimIndent(),
            { rs, _ -> MonitorStatus.valueOf(rs.getString("status")) to rs.getInt("cnt") },
            memberId,
        ).toMap()

        val now = clock.instant()
        val monitorIds = monitorRepository.findAllByMemberIdOrderByIdAsc(memberId).map { it.id!! }
        val uptime = if (monitorIds.isEmpty()) {
            null
        } else {
            jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(result = 'SUCCESS'), 0) AS success, COUNT(*) - COALESCE(SUM(result = 'BLOCKED'), 0) AS counted
                FROM check_results
                WHERE monitor_id IN (${monitorIds.joinToString(",") { "?" }}) AND checked_at >= ?
                """.trimIndent(),
                { rs, _ -> Uptime.percent(rs.getLong("success"), rs.getLong("counted")) },
                *monitorIds.toTypedArray(),
                utc(now.minus(DAY)),
            )
        }

        val recentIncidents = jdbcTemplate.query(
            """
            SELECT i.id, i.monitor_id, m.name, i.started_at, i.resolved_at, i.cause, i.last_status_code
            FROM incidents i
            JOIN monitors m ON m.id = i.monitor_id
            WHERE m.member_id = ? AND (i.resolved_at IS NULL OR i.resolved_at >= ?)
            ORDER BY (i.resolved_at IS NULL) DESC, i.started_at DESC
            LIMIT $RECENT_INCIDENT_LIMIT
            """.trimIndent(),
            { rs, _ ->
                RecentIncident(
                    id = rs.getLong("id"),
                    monitor = MonitorRef(rs.getLong("monitor_id"), rs.getString("name")),
                    status = if (rs.getObject("resolved_at") == null) IncidentStatus.OPEN else IncidentStatus.RESOLVED,
                    startedAt = rs.instant("started_at")!!,
                    cause = rs.getString("cause"),
                    lastStatusCode = (rs.getObject("last_status_code") as Number?)?.toInt(),
                )
            },
            memberId,
            utc(now.minus(DAY)),
        )

        return SummaryResponse(
            totalMonitors = counts.values.sum(),
            upCount = counts[MonitorStatus.UP] ?: 0,
            downCount = counts[MonitorStatus.DOWN] ?: 0,
            pausedCount = counts[MonitorStatus.PAUSED] ?: 0,
            unknownCount = counts[MonitorStatus.UNKNOWN] ?: 0,
            blockedCount = counts[MonitorStatus.BLOCKED] ?: 0,
            uptime24h = uptime,
            recentIncidents = recentIncidents,
        )
    }

    /**
     * 응답 시간 그래프와 가동률. 24시간은 원본 기록을 5분씩, 7일과 30일은 시간별 집계를 1시간·6시간씩 묶는다.
     * 검사가 없던 구간은 점을 만들지 않는다.
     */
    fun getStats(memberId: Long, monitorId: Long, range: String): StatsResponse {
        verifyOwner(memberId, monitorId)
        val now = clock.instant()
        val buckets = when (range) {
            "24h" -> {
                val from = alignDown(now.minus(DAY), FIVE_MINUTES)
                queryRawBuckets(monitorId, from, now).toPoints(from, FIVE_MINUTES)
            }
            "7d" -> {
                val from = alignDown(now.minus(Duration.ofDays(7)), Duration.ofHours(1))
                queryHourlyBuckets(monitorId, from, now, hoursPerBucket = 1).toPoints(from, Duration.ofHours(1))
            }
            "30d" -> {
                val from = alignDown(now.minus(Duration.ofDays(30)), Duration.ofHours(6))
                queryHourlyBuckets(monitorId, from, now, hoursPerBucket = 6).toPoints(from, Duration.ofHours(6))
            }
            else -> throw BusinessException(
                ErrorCode.VALIDATION_FAILED,
                details = listOf(ErrorDetail("range", "24h, 7d, 30d 중 하나여야 합니다.")),
            )
        }

        val (sums, points) = buckets
        return StatsResponse(
            range = range,
            bucketMinutes = when (range) {
                "24h" -> 5
                "7d" -> 60
                else -> 360
            },
            uptimePercent = Uptime.percent(sums.success, sums.total - sums.blocked),
            averageResponseTimeMs = sums.averageResponseTime(),
            points = points,
        )
    }

    /** 최근 검사 기록. 최신순이며 [cursor]보다 이전 기록을 돌려준다. 기본 기간은 최근 24시간이다. */
    fun getChecks(memberId: Long, monitorId: Long, from: Instant?, to: Instant?, cursor: String?, limit: Int): CheckListResponse {
        verifyOwner(memberId, monitorId)
        val now = clock.instant()
        val lower = from ?: now.minus(DAY)
        val upper = listOfNotNull(to ?: now.plusSeconds(1), cursor?.let(Cursor::toInstant)).min()

        val rows = jdbcTemplate.query(
            """
            SELECT checked_at, result, status_code, response_time_ms FROM check_results
            WHERE monitor_id = ? AND checked_at >= ? AND checked_at < ?
            ORDER BY checked_at DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                CheckItem(
                    checkedAt = rs.instant("checked_at")!!,
                    result = rs.getString("result"),
                    statusCode = (rs.getObject("status_code") as Number?)?.toInt(),
                    responseTimeMs = rs.getObject("response_time_ms") as Int?,
                )
            },
            monitorId,
            utc(lower),
            utc(upper),
            limit + 1,
        )

        val items = rows.take(limit)
        val nextCursor = if (rows.size > limit) Cursor.ofInstant(items.last().checkedAt) else null
        return CheckListResponse(items, nextCursor)
    }

    private fun queryRawBuckets(monitorId: Long, from: Instant, to: Instant): List<Bucket> =
        jdbcTemplate.query(
            """
            SELECT TIMESTAMPDIFF(MINUTE, ?, checked_at) DIV 5 AS bucket,
                   COUNT(*) AS total, SUM(result = 'SUCCESS') AS success, SUM(result = 'BLOCKED') AS blocked,
                   COALESCE(SUM(response_time_ms), 0) AS rt_sum, COUNT(response_time_ms) AS rt_count,
                   MIN(response_time_ms) AS rt_min, MAX(response_time_ms) AS rt_max
            FROM check_results
            WHERE monitor_id = ? AND checked_at >= ? AND checked_at < ?
            GROUP BY bucket ORDER BY bucket
            """.trimIndent(),
            { rs, _ -> rs.toBucket() },
            utc(from), monitorId, utc(from), utc(to),
        )

    private fun queryHourlyBuckets(monitorId: Long, from: Instant, to: Instant, hoursPerBucket: Int): List<Bucket> =
        jdbcTemplate.query(
            """
            SELECT TIMESTAMPDIFF(HOUR, ?, hour_start) DIV ? AS bucket,
                   SUM(total_count) AS total, SUM(success_count) AS success, SUM(blocked_count) AS blocked,
                   SUM(response_time_sum_ms) AS rt_sum, SUM(response_time_count) AS rt_count,
                   MIN(response_time_min_ms) AS rt_min, MAX(response_time_max_ms) AS rt_max
            FROM monitor_hourly_stats
            WHERE monitor_id = ? AND hour_start >= ? AND hour_start < ?
            GROUP BY bucket ORDER BY bucket
            """.trimIndent(),
            { rs, _ -> rs.toBucket() },
            utc(from), hoursPerBucket, monitorId, utc(from), utc(to),
        )

    private fun verifyOwner(memberId: Long, monitorId: Long) {
        monitorRepository.findByIdAndMemberId(monitorId, memberId) ?: throw BusinessException(ErrorCode.MONITOR_NOT_FOUND)
    }

    /** 묶음 하나의 합계. 평균은 합계 ÷ 건수로 계산해 여러 묶음을 합쳐도 정확하다. */
    private data class Bucket(
        val index: Long,
        val total: Long,
        val success: Long,
        val blocked: Long,
        val responseTimeSum: Long,
        val responseTimeCount: Long,
        val min: Int?,
        val max: Int?,
    ) {
        fun averageResponseTime(): Int? = if (responseTimeCount == 0L) null else (responseTimeSum / responseTimeCount).toInt()
    }

    private fun List<Bucket>.toPoints(from: Instant, size: Duration): Pair<Bucket, List<StatsPoint>> {
        val sums = Bucket(
            index = 0,
            total = sumOf { it.total },
            success = sumOf { it.success },
            blocked = sumOf { it.blocked },
            responseTimeSum = sumOf { it.responseTimeSum },
            responseTimeCount = sumOf { it.responseTimeCount },
            min = null,
            max = null,
        )
        val points = map {
            StatsPoint(
                time = from.plus(size.multipliedBy(it.index)),
                averageResponseTimeMs = it.averageResponseTime(),
                minResponseTimeMs = it.min,
                maxResponseTimeMs = it.max,
                totalCount = it.total.toInt(),
                successCount = it.success.toInt(),
            )
        }
        return sums to points
    }

    private fun ResultSet.toBucket() = Bucket(
        index = getLong("bucket"),
        total = getLong("total"),
        success = getLong("success"),
        blocked = getLong("blocked"),
        responseTimeSum = getLong("rt_sum"),
        responseTimeCount = getLong("rt_count"),
        min = (getObject("rt_min") as Number?)?.toInt(),
        max = (getObject("rt_max") as Number?)?.toInt(),
    )

    private fun ResultSet.instant(column: String): Instant? =
        getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

    /** 묶음 경계(UTC 기준 5분, 1시간, 6시간)에 맞춰 내린다. */
    private fun alignDown(instant: Instant, size: Duration): Instant =
        Instant.ofEpochSecond(instant.epochSecond - Math.floorMod(instant.epochSecond, size.seconds))

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    companion object {
        private val DAY: Duration = Duration.ofHours(24)
        private val FIVE_MINUTES: Duration = Duration.ofMinutes(5)
        private const val RECENT_INCIDENT_LIMIT = 5
    }
}
