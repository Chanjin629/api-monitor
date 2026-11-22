package com.apimonitor.incident.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

enum class IncidentStatus {
    /** 진행 중 */
    OPEN,

    /** 복구됨 */
    RESOLVED,
}

data class MonitorRef(
    val id: Long,
    val name: String,
)

data class IncidentResponse(
    val id: Long,
    val monitor: MonitorRef,
    val status: IncidentStatus,
    @field:Schema(description = "첫 번째 실패 시각")
    val startedAt: Instant,
    @field:Schema(description = "장애로 판정한 시각 (3번째 연속 실패)")
    val detectedAt: Instant,
    @field:Schema(description = "복구 시각 (연속 성공의 첫 시각). 진행 중이면 null")
    val resolvedAt: Instant?,
    @field:Schema(description = "진행 중이면 null. 화면에서 현재 시각 - startedAt으로 계산한다")
    val durationSeconds: Int?,
    @field:Schema(description = "TIMEOUT, UNEXPECTED_STATUS 등")
    val cause: String,
    val lastStatusCode: Int?,
)

data class IncidentListResponse(
    val items: List<IncidentResponse>,
    @field:Schema(description = "다음 페이지 커서. 더 없으면 null")
    val nextCursor: String?,
)
