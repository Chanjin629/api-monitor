package com.apimonitor.incident.controller

import com.apimonitor.global.response.ApiResponse
import com.apimonitor.global.security.AuthMember
import com.apimonitor.incident.dto.IncidentListResponse
import com.apimonitor.incident.dto.IncidentStatus
import com.apimonitor.incident.service.IncidentQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "장애", description = "장애 이력. 최신 장애부터 커서 방식으로 내려 준다.")
@RestController
@RequestMapping("/api/v1")
class IncidentController(
    private val incidentQueryService: IncidentQueryService,
) {

    @Operation(summary = "전체 장애 이력", description = "내 모든 모니터의 장애. status를 생략하면 전체")
    @GetMapping("/incidents")
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @RequestParam(required = false) status: IncidentStatus?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): ApiResponse<IncidentListResponse> =
        ApiResponse.ok(incidentQueryService.getIncidents(member.id, null, status, cursor, limit))

    @Operation(summary = "모니터의 장애 이력", description = "실패: 404 MONITOR_NOT_FOUND")
    @GetMapping("/monitors/{id}/incidents")
    fun listByMonitor(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @PathVariable id: Long,
        @RequestParam(required = false) status: IncidentStatus?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): ApiResponse<IncidentListResponse> =
        ApiResponse.ok(incidentQueryService.getIncidents(member.id, id, status, cursor, limit))
}
