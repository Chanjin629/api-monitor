package com.apimonitor.notification.controller

import com.apimonitor.global.response.ApiResponse
import com.apimonitor.global.security.AuthMember
import com.apimonitor.notification.dto.ChannelListResponse
import com.apimonitor.notification.dto.ChannelResponse
import com.apimonitor.notification.dto.CreateChannelRequest
import com.apimonitor.notification.dto.TestSendResponse
import com.apimonitor.notification.dto.UpdateChannelRequest
import com.apimonitor.notification.service.NotificationChannelService
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

@Tag(
    name = "알림 채널",
    description = "장애·복구 알림을 받을 Discord Webhook. Webhook URL은 응답에서 끝 4자리만 보여 준다. 다른 회원의 채널은 404 CHANNEL_NOT_FOUND다.",
)
@RestController
@RequestMapping("/api/v1/notification-channels")
class NotificationChannelController(
    private val channelService: NotificationChannelService,
) {

    @Operation(summary = "알림 채널 목록")
    @GetMapping
    fun list(@Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember): ApiResponse<ChannelListResponse> =
        ApiResponse.ok(channelService.getChannels(member.id))

    @Operation(
        summary = "알림 채널 등록",
        description = "https://discord.com/api/webhooks/{id}/{token} 형식만 받는다. 실패: 409 CHANNEL_LIMIT_EXCEEDED, 422 WEBHOOK_URL_INVALID",
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @Valid @RequestBody request: CreateChannelRequest,
    ): ApiResponse<ChannelResponse> = ApiResponse.ok(channelService.create(member.id, request))

    @Operation(summary = "알림 채널 수정", description = "name, webhookUrl, enabled 중 보낸 필드만 바꾼다.")
    @PatchMapping("/{id}")
    fun update(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateChannelRequest,
    ): ApiResponse<ChannelResponse> = ApiResponse.ok(channelService.update(member.id, id, request))

    @Operation(summary = "알림 채널 삭제")
    @DeleteMapping("/{id}")
    fun delete(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @PathVariable id: Long,
    ): ApiResponse<Nothing> {
        channelService.delete(member.id, id)
        return ApiResponse.ok()
    }

    @Operation(summary = "테스트 발송", description = "테스트 메시지를 바로 보낸다. 실패: 502 NOTIFICATION_SEND_FAILED")
    @PostMapping("/{id}/test")
    fun test(
        @Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember,
        @PathVariable id: Long,
    ): ApiResponse<TestSendResponse> {
        channelService.sendTest(member.id, id)
        return ApiResponse.ok(TestSendResponse(delivered = true))
    }
}
