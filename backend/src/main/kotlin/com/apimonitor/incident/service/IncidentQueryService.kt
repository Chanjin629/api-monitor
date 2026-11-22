package com.apimonitor.incident.service

import com.apimonitor.global.error.BusinessException
import com.apimonitor.global.error.ErrorCode
import com.apimonitor.global.response.Cursor
import com.apimonitor.incident.dto.IncidentListResponse
import com.apimonitor.incident.dto.IncidentResponse
import com.apimonitor.incident.dto.IncidentStatus
import com.apimonitor.incident.dto.MonitorRef
import com.apimonitor.monitor.repository.MonitorRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 장애 이력 조회. 장애를 만들고 복구하는 [IncidentService]와 달리 읽기만 한다.
 * 최신 장애부터 ID 내림차순으로 커서 방식 페이지를 만든다.
 */
@Service
class IncidentQueryService(
    private val jdbcTemplate: JdbcTemplate,
    private val monitorRepository: MonitorRepository,
) {

    /** @param monitorId 지정하면 그 모니터의 장애만. 다른 회원의 모니터면 404 */
    fun getIncidents(memberId: Long, monitorId: Long?, status: IncidentStatus?, cursor: String?, limit: Int): IncidentListResponse {
        if (monitorId != null && monitorRepository.findByIdAndMemberId(monitorId, memberId) == null) {
            throw BusinessException(ErrorCode.MONITOR_NOT_FOUND)
        }

        val conditions = mutableListOf("m.member_id = ?")
        val params = mutableListOf<Any>(memberId)
        monitorId?.let { conditions += "i.monitor_id = ?"; params += it }
        when (status) {
            IncidentStatus.OPEN -> conditions += "i.resolved_at IS NULL"
            IncidentStatus.RESOLVED -> conditions += "i.resolved_at IS NOT NULL"
            null -> Unit
        }
        cursor?.let { conditions += "i.id < ?"; params += Cursor.toId(it) }
        params += limit + 1

        val rows = jdbcTemplate.query(
            """
            SELECT i.id, i.monitor_id, m.name, i.started_at, i.detected_at, i.resolved_at,
                   i.duration_seconds, i.cause, i.last_status_code
            FROM incidents i
            JOIN monitors m ON m.id = i.monitor_id
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY i.id DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                val resolvedAt = rs.getObject("resolved_at", LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
                IncidentResponse(
                    id = rs.getLong("id"),
                    monitor = MonitorRef(rs.getLong("monitor_id"), rs.getString("name")),
                    status = if (resolvedAt == null) IncidentStatus.OPEN else IncidentStatus.RESOLVED,
                    startedAt = rs.getObject("started_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
                    detectedAt = rs.getObject("detected_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
                    resolvedAt = resolvedAt,
                    durationSeconds = rs.getObject("duration_seconds") as Int?,
                    cause = rs.getString("cause"),
                    lastStatusCode = (rs.getObject("last_status_code") as Number?)?.toInt(),
                )
            },
            *params.toTypedArray(),
        )

        val items = rows.take(limit)
        val nextCursor = if (rows.size > limit) Cursor.ofId(items.last().id) else null
        return IncidentListResponse(items, nextCursor)
    }
}
