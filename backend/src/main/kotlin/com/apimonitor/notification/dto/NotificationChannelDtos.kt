package com.apimonitor.notification.dto

import com.apimonitor.notification.domain.ChannelType
import com.apimonitor.notification.domain.NotificationChannel
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateChannelRequest(
    @field:Schema(description = "지금은 DISCORD만 지원한다", example = "DISCORD")
    val type: ChannelType? = ChannelType.DISCORD,

    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(example = "운영 알림")
    val name: String?,

    @field:NotBlank
    @field:Size(max = 500)
    @field:Schema(description = "https://discord.com/api/webhooks/{id}/{token} 형식", example = "https://discord.com/api/webhooks/123/abcd")
    val webhookUrl: String?,
)

/** 보낸 필드만 바꾼다. */
data class UpdateChannelRequest(
    @field:Size(min = 1, max = 100)
    @field:Pattern(regexp = ".*\\S.*", message = "공백만 입력할 수 없습니다.")
    val name: String? = null,

    @field:Size(min = 1, max = 500)
    val webhookUrl: String? = null,

    val enabled: Boolean? = null,
)

data class ChannelResponse(
    val id: Long,
    val type: ChannelType,
    val name: String,
    @field:Schema(description = "끝 4자리만 보여 준다", example = "••••abcd")
    val webhookUrlMasked: String,
    val enabled: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun of(channel: NotificationChannel, webhookUrl: String) = ChannelResponse(
            id = channel.id!!,
            type = channel.type,
            name = channel.name,
            webhookUrlMasked = "••••" + webhookUrl.takeLast(4),
            enabled = channel.enabled,
            createdAt = channel.createdAt,
        )
    }
}

data class ChannelListResponse(
    val items: List<ChannelResponse>,
)

data class TestSendResponse(
    val delivered: Boolean,
)
