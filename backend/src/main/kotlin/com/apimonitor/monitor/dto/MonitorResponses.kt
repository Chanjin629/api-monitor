package com.apimonitor.monitor.dto

import com.apimonitor.monitor.domain.Monitor
import com.apimonitor.monitor.domain.MonitorHttpMethod
import com.apimonitor.monitor.domain.MonitorState
import com.apimonitor.monitor.domain.MonitorStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class MonitorResponse(
    val id: Long,
    val name: String,
    val url: String,
    val method: MonitorHttpMethod,
    val intervalMinutes: Int,
    val timeoutSeconds: Int,
    @field:Schema(description = "빈 배열이면 2xx 전체")
    val expectedStatusCodes: List<Int>,
    @field:Schema(description = "값은 항상 ******** 로 가려서 내려간다")
    val headers: List<HeaderResponse>,
    val state: MonitorStateResponse,
    @field:Schema(description = "24시간 가동률(%). 집계 기능(14단계) 전까지는 null")
    val uptime24h: Double?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(monitor: Monitor, state: MonitorState, headerNames: List<String>): MonitorResponse = MonitorResponse(
            id = monitor.id!!,
            name = monitor.name,
            url = monitor.url,
            method = monitor.httpMethod,
            intervalMinutes = monitor.intervalMinutes,
            timeoutSeconds = monitor.timeoutSeconds,
            expectedStatusCodes = monitor.expectedStatusCodes
                ?.split(',')
                ?.map { it.toInt() }
                .orEmpty(),
            headers = headerNames.map { HeaderResponse(it, MASKED_VALUE) },
            state = MonitorStateResponse.from(state),
            uptime24h = null,
            createdAt = monitor.createdAt,
            updatedAt = monitor.updatedAt,
        )

        const val MASKED_VALUE = "********"
    }
}

data class HeaderResponse(
    val name: String,
    val value: String,
)

data class MonitorStateResponse(
    val status: MonitorStatus,
    val consecutiveFailures: Int,
    val lastCheckedAt: Instant?,
    val lastResult: String?,
    val lastStatusCode: Int?,
    val lastResponseTimeMs: Int?,
    val nextCheckAt: Instant?,
) {
    companion object {
        fun from(state: MonitorState): MonitorStateResponse = MonitorStateResponse(
            status = state.status,
            consecutiveFailures = state.consecutiveFailures,
            lastCheckedAt = state.lastCheckedAt,
            lastResult = state.lastResult,
            lastStatusCode = state.lastStatusCode,
            lastResponseTimeMs = state.lastResponseTimeMs,
            nextCheckAt = state.nextCheckAt,
        )
    }
}

data class MonitorListResponse(
    val items: List<MonitorResponse>,
    @field:Schema(description = "모니터가 최대 20개라 지금은 항상 null")
    val nextCursor: String?,
)
