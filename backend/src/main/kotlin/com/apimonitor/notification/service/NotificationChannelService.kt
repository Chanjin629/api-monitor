package com.apimonitor.notification.service

import com.apimonitor.global.crypto.AesGcmEncryptor
import com.apimonitor.global.error.BusinessException
import com.apimonitor.global.error.ErrorCode
import com.apimonitor.member.repository.MemberRepository
import com.apimonitor.notification.discord.DiscordMessageFormatter
import com.apimonitor.notification.discord.DiscordSendResult
import com.apimonitor.notification.discord.DiscordWebhookClient
import com.apimonitor.notification.domain.ChannelType
import com.apimonitor.notification.domain.NotificationChannel
import com.apimonitor.notification.dto.ChannelListResponse
import com.apimonitor.notification.dto.ChannelResponse
import com.apimonitor.notification.dto.CreateChannelRequest
import com.apimonitor.notification.dto.UpdateChannelRequest
import com.apimonitor.notification.repository.NotificationChannelRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(readOnly = true)
class NotificationChannelService(
    private val channelRepository: NotificationChannelRepository,
    private val memberRepository: MemberRepository,
    private val encryptor: AesGcmEncryptor,
    private val discordWebhookClient: DiscordWebhookClient,
    private val messageFormatter: DiscordMessageFormatter,
    private val clock: Clock,
) {

    fun getChannels(memberId: Long): ChannelListResponse =
        ChannelListResponse(channelRepository.findAllByMemberIdOrderByIdAsc(memberId).map(::toResponse))

    @Transactional
    fun create(memberId: Long, request: CreateChannelRequest): ChannelResponse {
        val webhookUrl = validateWebhookUrl(request.webhookUrl!!)

        // 회원 행을 잠가 동시에 등록해도 개수 제한을 넘지 않게 한다.
        memberRepository.findByIdForUpdate(memberId) ?: throw BusinessException(ErrorCode.AUTH_TOKEN_INVALID)
        if (channelRepository.countByMemberId(memberId) >= CHANNEL_LIMIT) {
            throw BusinessException(ErrorCode.CHANNEL_LIMIT_EXCEEDED)
        }

        val channel = channelRepository.save(
            NotificationChannel(
                memberId = memberId,
                type = request.type ?: ChannelType.DISCORD,
                name = request.name!!.trim(),
                webhookUrlEncrypted = encryptor.encrypt(webhookUrl),
                createdAt = clock.instant(),
            ),
        )
        return toResponse(channel)
    }

    @Transactional
    fun update(memberId: Long, channelId: Long, request: UpdateChannelRequest): ChannelResponse {
        val newWebhookUrl = request.webhookUrl?.let(::validateWebhookUrl)
        val channel = findOwned(memberId, channelId)

        request.name?.let { channel.rename(it.trim()) }
        newWebhookUrl?.let { channel.changeWebhookUrl(encryptor.encrypt(it)) }
        request.enabled?.let { channel.changeEnabled(it) }
        channel.touch(clock.instant())
        return toResponse(channel)
    }

    /** 예약되어 있던 이 채널의 알림도 DB의 ON DELETE CASCADE로 함께 지워진다. */
    @Transactional
    fun delete(memberId: Long, channelId: Long) {
        channelRepository.delete(findOwned(memberId, channelId))
    }

    /**
     * 테스트 메시지를 바로 보낸다. Discord 응답을 최대 15초 기다리므로, 그동안 DB 연결을 붙잡지 않도록 트랜잭션 밖에서 실행한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun sendTest(memberId: Long, channelId: Long) {
        val channel = findOwned(memberId, channelId)
        val result = discordWebhookClient.send(encryptor.decrypt(channel.webhookUrlEncrypted), messageFormatter.test(channel.name))
        if (result != DiscordSendResult.Sent) throw BusinessException(ErrorCode.NOTIFICATION_SEND_FAILED)
    }

    private fun findOwned(memberId: Long, channelId: Long): NotificationChannel =
        channelRepository.findByIdAndMemberId(channelId, memberId)
            ?: throw BusinessException(ErrorCode.CHANNEL_NOT_FOUND)

    /**
     * Discord Webhook 주소만 받는다 (N-S6). 도메인을 discord.com으로 고정해, 이 입력이 내부망을 호출하는 통로(SSRF)가 되지 않게 한다.
     */
    private fun validateWebhookUrl(raw: String): String {
        val url = raw.trim()
        if (!WEBHOOK_URL.matches(url)) throw BusinessException(ErrorCode.WEBHOOK_URL_INVALID)
        return url
    }

    private fun toResponse(channel: NotificationChannel) =
        ChannelResponse.of(channel, encryptor.decrypt(channel.webhookUrlEncrypted))

    companion object {
        const val CHANNEL_LIMIT = 3
        private val WEBHOOK_URL = Regex("^https://discord\\.com/api/webhooks/[0-9]{1,30}/[A-Za-z0-9_-]{1,200}$")
    }
}
