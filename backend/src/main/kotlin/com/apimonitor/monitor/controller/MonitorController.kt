package com.apimonitor.monitor.controller

import com.apimonitor.global.response.ApiResponse
import com.apimonitor.global.security.AuthMember
import com.apimonitor.monitor.dto.CreateMonitorRequest
import com.apimonitor.monitor.dto.MonitorListResponse
import com.apimonitor.monitor.dto.MonitorResponse
import com.apimonitor.monitor.dto.UpdateMonitorRequest
import com.apimonitor.monitor.service.MonitorService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "모니터", description = "다른 회원의 모니터는 404 MONITOR_NOT_FOUND로 응답한다. 데모 계정은 조회만 할 수 있다.")
@RestController
@RequestMapping("/api/v1/monitors")
class MonitorController(
    private val monitorService: MonitorService,
) {

    @Operation(summary = "모니터 목록")
    @GetMapping
    fun list(@Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember): ApiResponse<MonitorListResponse> =
        ApiResponse.ok(monitorService.getMonitors(member.id))

    @Operation(
        summary = "모니터 등록",
        description = "URL은 SSRF 검증을 거친다. 실패: 400 VALIDATION_FAILED, 409 MONITOR_LIMIT_EXCEEDED, " +
            "422 URL_BLOCKED, 422 HEADER_NOT_ALLOWED",
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @Valid @RequestBody request: CreateMonitorRequest,
    ): ApiResponse<MonitorResponse> = ApiResponse.ok(monitorService.create(member.id, request))

    @Operation(summary = "모니터 상세", description = "실패: 404 MONITOR_NOT_FOUND")
    @GetMapping("/{id}")
    fun get(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @PathVariable id: Long,
    ): ApiResponse<MonitorResponse> = ApiResponse.ok(monitorService.getMonitor(member.id, id))

    @Operation(
        summary = "모니터 수정",
        description = "보낸 필드만 바꾼다. headers는 보내면 전체를 교체한다. 값이 ******** 인 헤더는 400으로 거부한다. " +
            "URL을 바꾸면 SSRF 검증을 다시 하고 연속 카운터를 초기화한다.",
    )
    @PatchMapping("/{id}")
    fun update(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateMonitorRequest,
    ): ApiResponse<MonitorResponse> = ApiResponse.ok(monitorService.update(member.id, id, request))

    @Operation(summary = "모니터 삭제", description = "상태, 집계, 장애 이력도 함께 삭제한다.")
    @DeleteMapping("/{id}")
    fun delete(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @PathVariable id: Long,
    ): ApiResponse<Nothing> {
        monitorService.delete(member.id, id)
        return ApiResponse.ok()
    }

    @Operation(summary = "일시 정지", description = "실패: 409 MONITOR_ALREADY_PAUSED")
    @PostMapping("/{id}/pause")
    fun pause(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @PathVariable id: Long,
    ): ApiResponse<MonitorResponse> = ApiResponse.ok(monitorService.pause(member.id, id))

    @Operation(
        summary = "재개",
        description = "진행 중인 장애가 있으면 DOWN, 없으면 UNKNOWN에서 다시 판정한다. 실패: 409 MONITOR_NOT_PAUSED",
    )
    @PostMapping("/{id}/resume")
    fun resume(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @PathVariable id: Long,
    ): ApiResponse<MonitorResponse> = ApiResponse.ok(monitorService.resume(member.id, id))
}
