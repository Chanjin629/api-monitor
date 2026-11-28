package com.apimonitor.check.service

import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.check.dto.CheckResult
import com.apimonitor.check.dto.CheckResultType
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.service.MemberService
import com.apimonitor.monitor.domain.MonitorStatus
import com.apimonitor.monitor.dto.CreateMonitorRequest
import com.apimonitor.monitor.service.MonitorService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 검사 결과 처리가 원본 기록, 상태, 장애, 알림 예약을 한 번에 올바르게 저장하는지 실제 MariaDB로 확인한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class CheckResultProcessorTest(
    @Autowired private val processor: CheckResultProcessor,
    @Autowired private val monitorService: MonitorService,
    @Autowired private val memberService: MemberService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val t0: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(3600)

    @Test
    fun `연속 3회 실패하면 장애를 만들고 활성 채널에만 DOWN 알림을 예약한다`() {
        val (memberId, monitorId) = newMonitor()
        val enabledChannel = insertChannel(memberId, enabled = true)
        insertChannel(memberId, enabled = false)

        processor.process(fail(monitorId, t0))
        processor.process(fail(monitorId, t0.plusSeconds(30)))
        assertThat(incidents(monitorId)).isEmpty()
        processor.process(fail(monitorId, t0.plusSeconds(60), CheckResultType.UNEXPECTED_STATUS, 503))

        val incident = incidents(monitorId).single()
        assertThat(incident.startedAt).isEqualTo(t0)
        assertThat(incident.detectedAt).isEqualTo(t0.plusSeconds(60))
        assertThat(incident.cause).isEqualTo("UNEXPECTED_STATUS")
        assertThat(incident.lastStatusCode).isEqualTo(503)
        assertThat(incident.resolvedAt).isNull()
        assertThat(deliveries(monitorId)).containsExactly(Delivery(enabledChannel, "DOWN", "PENDING"))
        assertThat(state(memberId, monitorId).status).isEqualTo(MonitorStatus.DOWN)
        assertThat(checkResultCount(monitorId)).isEqualTo(3)
    }

    @Test
    fun `장애 중 2회 연속 성공하면 첫 성공 시각으로 복구하고 RECOVERED 알림을 예약한다`() {
        val (memberId, monitorId) = newMonitor()
        val channel = insertChannel(memberId, enabled = true)
        repeat(3) { processor.process(fail(monitorId, t0.plusSeconds(it * 30L))) }

        val firstSuccess = t0.plusSeconds(300)
        processor.process(success(monitorId, firstSuccess))
        assertThat(incidents(monitorId).single().resolvedAt).isNull()
        processor.process(success(monitorId, firstSuccess.plusSeconds(30)))

        val incident = incidents(monitorId).single()
        assertThat(incident.resolvedAt).isEqualTo(firstSuccess)
        assertThat(incident.durationSeconds).isEqualTo(300)
        assertThat(deliveries(monitorId)).containsExactlyInAnyOrder(
            Delivery(channel, "DOWN", "PENDING"),
            Delivery(channel, "RECOVERED", "PENDING"),
        )
        assertThat(state(memberId, monitorId).status).isEqualTo(MonitorStatus.UP)
    }

    @Test
    fun `같은 결과를 두 번 처리해도 한 번만 반영한다`() {
        val (memberId, monitorId) = newMonitor()
        val result = fail(monitorId, t0)

        processor.process(result)
        processor.process(result)

        assertThat(checkResultCount(monitorId)).isEqualTo(1)
        assertThat(state(memberId, monitorId).consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `늦게 도착한 오래된 결과는 원본만 저장하고 상태를 바꾸지 않는다`() {
        val (memberId, monitorId) = newMonitor()
        processor.process(success(monitorId, t0.plusSeconds(60)))

        processor.process(fail(monitorId, t0))

        assertThat(checkResultCount(monitorId)).isEqualTo(2)
        val state = state(memberId, monitorId)
        assertThat(state.status).isEqualTo(MonitorStatus.UP)
        assertThat(state.consecutiveFailures).isZero()
        assertThat(state.lastResult).isEqualTo("SUCCESS")
    }

    @Test
    fun `BLOCKED는 연속 실패에 넣지 않아 장애를 만들지 않는다`() {
        val (memberId, monitorId) = newMonitor()

        processor.process(fail(monitorId, t0))
        processor.process(fail(monitorId, t0.plusSeconds(30)))
        processor.process(CheckResult(monitorId, t0.plusSeconds(60), CheckResultType.BLOCKED))
        processor.process(fail(monitorId, t0.plusSeconds(120)))

        assertThat(incidents(monitorId)).isEmpty()
        assertThat(state(memberId, monitorId).consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `검사 중에 삭제된 모니터의 결과는 저장하지 않는다`() {
        val (memberId, monitorId) = newMonitor()
        monitorService.delete(memberId, monitorId)

        processor.process(fail(monitorId, t0))

        assertThat(checkResultCount(monitorId)).isZero()
    }

    @Test
    fun `같은 모니터의 결과 두 개가 동시에 처리되어도 장애와 알림 예약은 한 번만 만든다`() {
        // 경합은 우연에 달려 있으므로 모니터 5개로 여러 번 시도한다.
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(5) {
                val (memberId, monitorId) = newMonitor()
                val channel = insertChannel(memberId, enabled = true)
                processor.process(fail(monitorId, t0))
                processor.process(fail(monitorId, t0.plusSeconds(30)))

                // 세 번째 실패 결과 두 개(예: 재검사와 정기 검사가 겹침)가 두 스레드에서 동시에 도착한다.
                val start = CountDownLatch(1)
                val futures = listOf(t0.plusSeconds(60), t0.plusSeconds(61)).map { at ->
                    pool.submit {
                        start.await(5, TimeUnit.SECONDS)
                        processor.process(fail(monitorId, at))
                    }
                }
                start.countDown()
                futures.forEach { it.get(10, TimeUnit.SECONDS) }

                assertThat(incidents(monitorId)).hasSize(1)
                assertThat(deliveries(monitorId)).containsExactly(Delivery(channel, "DOWN", "PENDING"))
                assertThat(checkResultCount(monitorId)).isEqualTo(4)
                // 61초 결과가 먼저 반영되면 60초 결과는 "늦게 온 오래된 결과"라 원본만 저장되고 상태는 그대로다.
                assertThat(state(memberId, monitorId).consecutiveFailures).isIn(3, 4)
            }
        } finally {
            pool.shutdown()
        }
    }

    private fun success(monitorId: Long, at: Instant) = CheckResult(monitorId, at, CheckResultType.SUCCESS, 200, 120)

    private fun fail(monitorId: Long, at: Instant, type: CheckResultType = CheckResultType.TIMEOUT, statusCode: Int? = null) =
        CheckResult(monitorId, at, type, statusCode)

    private fun newMonitor(): Pair<Long, Long> {
        val id = System.nanoTime().toString()
        val memberId = requireNotNull(memberService.loginWithGithub(GithubUser(id, "user$id", null, null)).id)
        val monitorId = monitorService.create(
            memberId,
            CreateMonitorRequest(name = "판정 테스트", url = "https://public.example/health", intervalMinutes = 1),
        ).id
        return memberId to monitorId
    }

    private fun insertChannel(memberId: Long, enabled: Boolean): Long {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        jdbcTemplate.update(
            """
            INSERT INTO notification_channels (member_id, type, name, webhook_url_encrypted, enabled, created_at, updated_at)
            VALUES (?, 'DISCORD', '테스트 채널', 'encrypted', ?, ?, ?)
            """.trimIndent(),
            memberId, enabled, now, now,
        )
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM notification_channels", Long::class.java)!!
    }

    private fun state(memberId: Long, monitorId: Long) = monitorService.getMonitor(memberId, monitorId).state

    private fun checkResultCount(monitorId: Long): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM check_results WHERE monitor_id = ?", Int::class.java, monitorId)!!

    private fun incidents(monitorId: Long): List<IncidentRow> =
        jdbcTemplate.query(
            """
            SELECT started_at, detected_at, resolved_at, duration_seconds, cause, last_status_code
            FROM incidents WHERE monitor_id = ? ORDER BY id
            """.trimIndent(),
            { rs, _ ->
                IncidentRow(
                    startedAt = rs.utc("started_at")!!,
                    detectedAt = rs.utc("detected_at")!!,
                    resolvedAt = rs.utc("resolved_at"),
                    durationSeconds = rs.getObject("duration_seconds") as Int?,
                    cause = rs.getString("cause"),
                    lastStatusCode = (rs.getObject("last_status_code") as Number?)?.toInt(),
                )
            },
            monitorId,
        )

    private fun deliveries(monitorId: Long): List<Delivery> =
        jdbcTemplate.query(
            """
            SELECT d.channel_id, d.event_type, d.status FROM notification_deliveries d
            JOIN incidents i ON i.id = d.incident_id WHERE i.monitor_id = ?
            """.trimIndent(),
            { rs, _ -> Delivery(rs.getLong("channel_id"), rs.getString("event_type"), rs.getString("status")) },
            monitorId,
        )

    private fun ResultSet.utc(column: String): Instant? =
        getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

    private data class IncidentRow(
        val startedAt: Instant,
        val detectedAt: Instant,
        val resolvedAt: Instant?,
        val durationSeconds: Int?,
        val cause: String,
        val lastStatusCode: Int?,
    )

    private data class Delivery(val channelId: Long, val eventType: String, val status: String)
}
