package com.apimonitor.notification.scheduler

import com.apimonitor.MutableClock
import com.apimonitor.TestDiscordClient
import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.check.dto.CheckResult
import com.apimonitor.check.dto.CheckResultType
import com.apimonitor.check.service.CheckResultProcessor
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.service.MemberService
import com.apimonitor.monitor.dto.CreateMonitorRequest
import com.apimonitor.monitor.service.MonitorService
import com.apimonitor.notification.discord.DiscordSendResult
import com.apimonitor.notification.dto.CreateChannelRequest
import com.apimonitor.notification.dto.UpdateChannelRequest
import com.apimonitor.notification.service.NotificationChannelService
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 장애가 생기면 예약된 알림이 발송되고, 실패하면 규칙대로 재시도되는지 실제 MariaDB로 확인한다.
 * 다른 테스트가 남긴 예약도 같은 DB에 있으므로, 이 테스트에서 만든 모니터의 알림만 골라 확인한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class NotificationSenderTest(
    @Autowired private val sender: NotificationSender,
    @Autowired private val processor: CheckResultProcessor,
    @Autowired private val monitorService: MonitorService,
    @Autowired private val memberService: MemberService,
    @Autowired private val channelService: NotificationChannelService,
    @Autowired private val discordClient: TestDiscordClient,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val meterRegistry: MeterRegistry,
) {
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @BeforeEach
    fun fixClock() = clock.fixAt(now)

    @AfterEach
    fun resetClock() = clock.reset()

    @Test
    fun `장애가 시작되면 Discord로 장애 메시지를 보내고 SENT로 바꾼다`() {
        val fixture = monitorWithChannel()
        val sentBefore = outcomeCount("sent")

        startIncident(fixture.monitorId)
        sender.sendDue()

        val delivery = delivery(fixture.monitorId, "DOWN")
        assertThat(delivery.status).isEqualTo("SENT")
        // 다른 테스트가 남긴 예약도 함께 처리될 수 있어 증가량으로 확인한다.
        assertThat(outcomeCount("sent") - sentBefore).isGreaterThanOrEqualTo(1.0)
        assertThat(delivery.attemptCount).isEqualTo(1)
        val message = discordClient.messagesTo(fixture.webhookUrl).single()
        assertThat(message).contains("🔴 장애 감지: 알림 테스트", "https://public.example/health", "원인: 시간 초과")
        // URL에 넣어 둔 API 키는 Discord로 보내지 않는다.
        assertThat(message).doesNotContain("secret")
    }

    @Test
    fun `복구되면 장애 시간을 담은 복구 메시지를 보낸다`() {
        val fixture = monitorWithChannel()
        startIncident(fixture.monitorId)
        processor.process(CheckResult(fixture.monitorId, now.minusSeconds(60), CheckResultType.SUCCESS, 200, 100))
        processor.process(CheckResult(fixture.monitorId, now.minusSeconds(30), CheckResultType.SUCCESS, 200, 100))

        sender.sendDue()

        assertThat(delivery(fixture.monitorId, "RECOVERED").status).isEqualTo("SENT")
        val recovered = discordClient.messagesTo(fixture.webhookUrl).single { it.startsWith("🟢") }
        // 첫 실패(now-10분)부터 첫 성공(now-1분)까지
        assertThat(recovered).contains("🟢 복구: 알림 테스트", "장애 시간: 9분")
    }

    @Test
    fun `일시적인 실패는 1분, 5분, 15분, 1시간 간격으로 재시도하고 5번째 실패면 FAILED다`() {
        val fixture = monitorWithChannel()
        discordClient.willReturn(fixture.webhookUrl, *Array(5) { DiscordSendResult.Failed("HTTP_503", permanent = false) })
        startIncident(fixture.monitorId)

        val expectedWaits = listOf(1L, 5L, 15L, 60L)
        expectedWaits.forEachIndexed { index, minutes ->
            makeDueNow(fixture.monitorId)
            sender.sendDue()
            val delivery = delivery(fixture.monitorId, "DOWN")
            assertThat(delivery.status).isEqualTo("PENDING")
            assertThat(delivery.attemptCount).isEqualTo(index + 1)
            assertThat(delivery.lastErrorCode).isEqualTo("HTTP_503")
            assertThat(delivery.nextAttemptAt).isEqualTo(now.plus(Duration.ofMinutes(minutes)))
        }

        makeDueNow(fixture.monitorId)
        sender.sendDue()

        val failed = delivery(fixture.monitorId, "DOWN")
        assertThat(failed.status).isEqualTo("FAILED")
        assertThat(failed.attemptCount).isEqualTo(5)
    }

    @Test
    fun `429면 Discord가 알려 준 시간 뒤에 다시 보낸다`() {
        val fixture = monitorWithChannel()
        discordClient.willReturn(fixture.webhookUrl, DiscordSendResult.RateLimited(Duration.ofSeconds(30)))
        startIncident(fixture.monitorId)

        sender.sendDue()

        val delivery = delivery(fixture.monitorId, "DOWN")
        assertThat(delivery.status).isEqualTo("PENDING")
        assertThat(delivery.lastErrorCode).isEqualTo("RATE_LIMITED")
        assertThat(delivery.nextAttemptAt).isEqualTo(now.plusSeconds(30))
    }

    @Test
    fun `Webhook이 삭제되었으면 재시도하지 않고 바로 FAILED다`() {
        val fixture = monitorWithChannel()
        discordClient.willReturn(fixture.webhookUrl, DiscordSendResult.Failed("HTTP_404", permanent = true))
        startIncident(fixture.monitorId)

        sender.sendDue()

        val delivery = delivery(fixture.monitorId, "DOWN")
        assertThat(delivery.status).isEqualTo("FAILED")
        assertThat(delivery.lastErrorCode).isEqualTo("HTTP_404")
    }

    @Test
    fun `예약 뒤 채널을 끄면 보내지 않는다`() {
        val fixture = monitorWithChannel()
        startIncident(fixture.monitorId)
        channelService.update(fixture.memberId, fixture.channelId, UpdateChannelRequest(enabled = false))

        sender.sendDue()

        val delivery = delivery(fixture.monitorId, "DOWN")
        assertThat(delivery.status).isEqualTo("FAILED")
        assertThat(delivery.lastErrorCode).isEqualTo("CHANNEL_DISABLED")
        assertThat(discordClient.messagesTo(fixture.webhookUrl)).isEmpty()
    }

    @Test
    fun `보내는 중인 예약은 다른 발송기가 다시 가져가지 않는다`() {
        val fixture = monitorWithChannel()
        startIncident(fixture.monitorId)

        // 먼저 가져간 발송기가 보내기 전에 죽은 상황: 다음 시도 시각만 2분 뒤로 옮겨져 있다.
        val claimed = jdbcTemplate.queryForObject(
            """
            SELECT d.id FROM notification_deliveries d JOIN incidents i ON i.id = d.incident_id
            WHERE i.monitor_id = ? AND d.event_type = 'DOWN'
            """.trimIndent(),
            Long::class.java,
            fixture.monitorId,
        )!!
        jdbcTemplate.update(
            "UPDATE notification_deliveries SET next_attempt_at = ? WHERE id = ?",
            LocalDateTime.ofInstant(now.plus(Duration.ofMinutes(2)), ZoneOffset.UTC),
            claimed,
        )

        sender.sendDue()
        assertThat(discordClient.messagesTo(fixture.webhookUrl)).isEmpty()

        clock.fixAt(now.plus(Duration.ofMinutes(2)))
        sender.sendDue()
        assertThat(discordClient.messagesTo(fixture.webhookUrl)).hasSize(1)
    }

    private fun outcomeCount(outcome: String): Double =
        meterRegistry.get("apimonitor.notification.deliveries").tag("outcome", outcome).counter().count()

    private data class Fixture(val memberId: Long, val monitorId: Long, val channelId: Long, val webhookUrl: String)

    private fun monitorWithChannel(): Fixture {
        val id = System.nanoTime().toString()
        val memberId = requireNotNull(memberService.loginWithGithub(GithubUser(id, "user$id", null, null)).id)
        val monitorId = monitorService.create(
            memberId,
            CreateMonitorRequest(name = "알림 테스트", url = "https://public.example/health?key=secret", intervalMinutes = 1),
        ).id
        val webhookUrl = "https://discord.com/api/webhooks/$id/sender-test"
        val channelId = channelService.create(memberId, CreateChannelRequest(name = "운영 알림", webhookUrl = webhookUrl)).id
        return Fixture(memberId, monitorId, channelId, webhookUrl)
    }

    /** 10분 전부터 3회 연속 실패해 장애가 시작된 상황을 만든다. */
    private fun startIncident(monitorId: Long) {
        listOf(600L, 570L, 540L).forEach { secondsAgo ->
            processor.process(CheckResult(monitorId, now.minusSeconds(secondsAgo), CheckResultType.TIMEOUT))
        }
    }

    private fun makeDueNow(monitorId: Long) {
        jdbcTemplate.update(
            """
            UPDATE notification_deliveries d JOIN incidents i ON i.id = d.incident_id
            SET d.next_attempt_at = ? WHERE i.monitor_id = ?
            """.trimIndent(),
            LocalDateTime.ofInstant(now, ZoneOffset.UTC),
            monitorId,
        )
    }

    private data class DeliveryRow(
        val status: String,
        val attemptCount: Int,
        val nextAttemptAt: Instant,
        val lastErrorCode: String?,
    )

    private fun delivery(monitorId: Long, eventType: String): DeliveryRow =
        jdbcTemplate.queryForObject(
            """
            SELECT d.status, d.attempt_count, d.next_attempt_at, d.last_error_code
            FROM notification_deliveries d JOIN incidents i ON i.id = d.incident_id
            WHERE i.monitor_id = ? AND d.event_type = ?
            """.trimIndent(),
            { rs, _ ->
                DeliveryRow(
                    status = rs.getString("status"),
                    attemptCount = rs.getInt("attempt_count"),
                    nextAttemptAt = rs.getObject("next_attempt_at", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
                    lastErrorCode = rs.getString("last_error_code"),
                )
            },
            monitorId,
            eventType,
        )!!
}
