package com.apimonitor.dashboard.controller

import com.apimonitor.dashboard.dto.CheckListResponse
import com.apimonitor.dashboard.dto.StatsResponse
import com.apimonitor.dashboard.dto.SummaryResponse
import com.apimonitor.dashboard.service.DashboardQueryService
import com.apimonitor.global.response.ApiResponse
import com.apimonitor.global.security.AuthMember
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@Tag(name = "대시보드", description = "요약, 응답 시간 그래프, 검사 기록 조회. 다른 회원의 모니터는 404 MONITOR_NOT_FOUND다.")
@RestController
@RequestMapping("/api/v1")
class DashboardController(
    private val dashboardQueryService: DashboardQueryService,
) {

    @Operation(summary = "대시보드 요약", description = "상태별 모니터 수, 24시간 가동률, 진행 중이거나 24시간 안에 복구된 장애 최대 5건")
    @GetMapping("/dashboard/summary")
    fun summary(@Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember): ApiResponse<SummaryResponse> =
        ApiResponse.ok(dashboardQueryService.getSummary(member.id))

    @Operation(
        summary = "응답 시간 그래프와 가동률",
        description = "range: 24h(원본 5분 단위), 7d(시간별 집계 1시간 단위), 30d(6시간 단위). 검사가 없던 구간은 점이 없다.",
    )
    @GetMapping("/monitors/{id}/stats")
    fun stats(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @PathVariable id: Long,
        @RequestParam(defaultValue = "24h") range: String,
    ): ApiResponse<StatsResponse> = ApiResponse.ok(dashboardQueryService.getStats(member.id, id, range))

    @Operation(summary = "검사 기록", description = "최신순, 커서 방식. 기본 기간은 최근 24시간")
    @GetMapping("/monitors/{id}/checks")
    fun checks(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @PathVariable id: Long,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
    ): ApiResponse<CheckListResponse> =
        ApiResponse.ok(dashboardQueryService.getChecks(member.id, id, from, to, cursor, limit))
}
