package com.apimonitor.notification.scheduler

import com.apimonitor.global.crypto.AesGcmEncryptor
import com.apimonitor.notification.discord.DiscordMessageFormatter
import com.apimonitor.notification.discord.DiscordSendResult
import com.apimonitor.notification.discord.DiscordWebhookClient
import com.apimonitor.notification.repository.DueDelivery
import com.apimonitor.notification.repository.NotificationDeliveryRepository
import com.apimonitor.notification.repository.NotificationEventType
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

/**
 * 알림 발송기 설정.
 * @property senderEnabled false면 주기 실행을 하지 않는다. 테스트에서는 꺼 두고 [NotificationSender.sendDue]를 직접 부른다.
 */
@ConfigurationProperties("notification")
data class NotificationProperties(
    val senderEnabled: Boolean = true,
)

/**
 * 예약된 알림(notification_deliveries)을 Discord로 보낸다. (docs/02-architecture.md 4.3, F-N2, F-N3)
 *
 * - 2xx: SENT
 * - 429: Discord가 알려 준 대기 시간 뒤 재시도
 * - 5xx, 시간 초과, 연결 실패: 1분, 5분, 15분, 1시간 간격으로 재시도하고 5번째 실패면 FAILED
 * - 400, 401, 403, 404(Webhook 삭제 등): 다시 보내도 소용없으므로 바로 FAILED
 */
@Component
class NotificationSender(
    private val deliveryRepository: NotificationDeliveryRepository,
    private val discordWebhookClient: DiscordWebhookClient,
    private val messageFormatter: DiscordMessageFormatter,
    private val encryptor: AesGcmEncryptor,
    private val transactionTemplate: TransactionTemplate,
    private val properties: NotificationProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${notification.send-interval-ms:5000}", initialDelayString = "\${notification.send-interval-ms:5000}")
    fun scheduledSend() {
        if (!properties.senderEnabled) return
        try {
            sendDue()
        } catch (e: Exception) {
            // DB 일시 장애 등. 예약은 DB에 남아 있으므로 다음 주기에 다시 시도한다.
            log.error("Notification send failed", e)
        }
    }

    /** 보낼 차례가 된 알림을 보내고, 처리한 개수를 돌려준다. */
    fun sendDue(): Int {
        val now = clock.instant()
        val ids = transactionTemplate.execute { deliveryRepository.claimDue(now, BATCH_SIZE, LEASE) }.orEmpty()
        val deliveries = deliveryRepository.findDetails(ids)
        deliveries.forEach(::send)
        return deliveries.size
    }

    private fun send(delivery: DueDelivery) {
        val attempts = delivery.attemptCount + 1
        // 예약한 뒤 사용자가 채널을 껐다.
        if (!delivery.channelEnabled) {
            deliveryRepository.markFailed(delivery.id, delivery.attemptCount, "CHANNEL_DISABLED")
            return
        }

        val result = try {
            discordWebhookClient.send(encryptor.decrypt(delivery.webhookUrlEncrypted), message(delivery))
        } catch (e: Exception) {
            log.error("Failed to prepare notification deliveryId={}", delivery.id, e)
            DiscordSendResult.Failed("INTERNAL_ERROR", permanent = false)
        }

        val now = clock.instant()
        when (result) {
            DiscordSendResult.Sent -> deliveryRepository.markSent(delivery.id, attempts, now)
            is DiscordSendResult.RateLimited -> retryOrFail(delivery.id, attempts, now.plus(result.retryAfter), "RATE_LIMITED")
            is DiscordSendResult.Failed ->
                if (result.permanent) {
                    deliveryRepository.markFailed(delivery.id, attempts, result.errorCode)
                } else {
                    retryOrFail(delivery.id, attempts, now.plus(backoff(attempts)), result.errorCode)
                }
        }
        if (result != DiscordSendResult.Sent) {
            log.warn("Notification not delivered deliveryId={} attempts={} result={}", delivery.id, attempts, result)
        }
    }

    private fun retryOrFail(id: Long, attempts: Int, nextAttemptAt: java.time.Instant, errorCode: String) {
        if (attempts >= MAX_ATTEMPTS) {
            deliveryRepository.markFailed(id, attempts, errorCode)
        } else {
            deliveryRepository.markRetry(id, attempts, nextAttemptAt, errorCode)
        }
    }

    private fun message(delivery: DueDelivery): String = when (delivery.eventType) {
        NotificationEventType.DOWN -> messageFormatter.incidentStarted(
            monitorName = delivery.monitorName,
            url = delivery.monitorUrl,
            cause = delivery.cause,
            statusCode = delivery.lastStatusCode,
            startedAt = delivery.startedAt,
        )
        NotificationEventType.RECOVERED -> messageFormatter.recovered(
            monitorName = delivery.monitorName,
            startedAt = delivery.startedAt,
            resolvedAt = delivery.resolvedAt ?: clock.instant(),
        )
    }

    /** n번째 실패 뒤 다시 보낼 때까지의 간격 */
    private fun backoff(attempts: Int): Duration = BACKOFF[(attempts - 1).coerceIn(0, BACKOFF.lastIndex)]

    companion object {
        private const val BATCH_SIZE = 50
        const val MAX_ATTEMPTS = 5
        private val LEASE: Duration = Duration.ofMinutes(2)
        val BACKOFF: List<Duration> = listOf(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofHours(1),
        )
    }
}
