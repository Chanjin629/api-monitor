package com.apimonitor.dashboard.dto

import com.apimonitor.incident.dto.IncidentStatus
import com.apimonitor.incident.dto.MonitorRef
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class SummaryResponse(
    val totalMonitors: Int,
    val upCount: Int,
    val downCount: Int,
    val pausedCount: Int,
    val unknownCount: Int,
    val blockedCount: Int,
    @field:Schema(description = "모든 모니터의 최근 24시간 가동률(%). 검사 기록이 없으면 null")
    val uptime24h: Double?,
    @field:Schema(description = "진행 중인 장애와 24시간 안에 복구된 장애. 진행 중인 것부터 최대 5건")
    val recentIncidents: List<RecentIncident>,
)

data class RecentIncident(
    val id: Long,
    val monitor: MonitorRef,
    val status: IncidentStatus,
    val startedAt: Instant,
    val cause: String,
    @field:Schema(description = "원인이 UNEXPECTED_STATUS일 때 \"HTTP 503\"처럼 보여 주기 위한 상태 코드")
    val lastStatusCode: Int?,
)

data class StatsResponse(
    @field:Schema(example = "24h")
    val range: String,
    @field:Schema(description = "그래프 점 하나가 묶은 시간(분). 24h=5, 7d=60, 30d=360")
    val bucketMinutes: Int,
    @field:Schema(description = "성공 ÷ (전체 - 차단). 검사 기록이 없으면 null")
    val uptimePercent: Double?,
    val averageResponseTimeMs: Int?,
    @field:Schema(description = "검사가 없던 구간은 점이 없다. 그래프에서 끊어서 보여 준다")
    val points: List<StatsPoint>,
)

data class StatsPoint(
    val time: Instant,
    val averageResponseTimeMs: Int?,
    val minResponseTimeMs: Int?,
    val maxResponseTimeMs: Int?,
    val totalCount: Int,
    val successCount: Int,
)

data class CheckItem(
    val checkedAt: Instant,
    @field:Schema(description = "SUCCESS, TIMEOUT 등")
    val result: String,
    val statusCode: Int?,
    val responseTimeMs: Int?,
)

data class CheckListResponse(
    val items: List<CheckItem>,
    @field:Schema(description = "다음 페이지 커서. 더 없으면 null")
    val nextCursor: String?,
)
