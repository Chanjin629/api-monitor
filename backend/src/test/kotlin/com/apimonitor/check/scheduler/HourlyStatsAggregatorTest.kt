package com.apimonitor.check.scheduler

import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.service.MemberService
import com.apimonitor.monitor.dto.CreateMonitorRequest
import com.apimonitor.monitor.service.MonitorService
import org.assertj.core.api.Assertions.assertThat
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

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class HourlyStatsAggregatorTest(
    @Autowired private val aggregator: HourlyStatsAggregator,
    @Autowired private val monitorService: MonitorService,
    @Autowired private val memberService: MemberService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val currentHour: Instant = Instant.now().truncatedTo(ChronoUnit.HOURS)

    @Test
    fun `직전 시간과 진행 중인 시간을 합계와 건수로 집계하고, 다시 실행해도 같다`() {
        val monitorId = newMonitor()
        val previousHour = currentHour.minus(Duration.ofHours(1))
        insert(monitorId, previousHour.plusSeconds(60), "SUCCESS", 200, 100)
        insert(monitorId, previousHour.plusSeconds(120), "SUCCESS", 200, 300)
        insert(monitorId, previousHour.plusSeconds(180), "UNEXPECTED_STATUS", 503, 50)
        insert(monitorId, previousHour.plusSeconds(240), "TIMEOUT", null, null)
        insert(monitorId, previousHour.plusSeconds(300), "BLOCKED", null, null)
        insert(monitorId, currentHour.plusSeconds(10), "SUCCESS", 200, 120)

        aggregator.aggregate(currentHour.plusSeconds(30))
        val first = stats(monitorId)
        aggregator.aggregate(currentHour.plusSeconds(30))

        assertThat(stats(monitorId)).isEqualTo(first)
        assertThat(first).containsExactly(
            HourRow(previousHour, total = 5, success = 2, blocked = 1, sum = 450, count = 3, min = 50, max = 300),
            HourRow(currentHour, total = 1, success = 1, blocked = 0, sum = 120, count = 1, min = 120, max = 120),
        )
    }

    @Test
    fun `늦게 저장된 기록은 다음 실행에서 반영된다`() {
        val monitorId = newMonitor()
        insert(monitorId, currentHour.plusSeconds(60), "SUCCESS", 200, 100)
        aggregator.aggregate(currentHour.plusSeconds(90))

        insert(monitorId, currentHour.plusSeconds(120), "TIMEOUT", null, null)
        aggregator.aggregate(currentHour.plusSeconds(150))

        assertThat(stats(monitorId).single().total).isEqualTo(2)
    }

    @Test
    fun `365일이 지난 집계는 지운다`() {
        val monitorId = newMonitor()
        val old = currentHour.minus(Duration.ofDays(366))
        val recent = currentHour.minus(Duration.ofDays(364))
        listOf(old, recent).forEach { hour ->
            jdbcTemplate.update(
                """
                INSERT INTO monitor_hourly_stats (monitor_id, hour_start, total_count, success_count, blocked_count,
                    response_time_sum_ms, response_time_count) VALUES (?, ?, 1, 1, 0, 100, 1)
                """.trimIndent(),
                monitorId, utc(hour),
            )
        }

        aggregator.deleteExpired(currentHour)

        assertThat(stats(monitorId).map { it.hour }).containsExactly(recent)
    }

    private fun newMonitor(): Long {
        val id = System.nanoTime().toString()
        val memberId = requireNotNull(memberService.loginWithGithub(GithubUser(id, "user$id", null, null)).id)
        return monitorService.create(
            memberId,
            CreateMonitorRequest(name = "집계", url = "https://public.example/health", intervalMinutes = 1),
        ).id
    }

    private fun insert(monitorId: Long, at: Instant, result: String, statusCode: Int?, responseTimeMs: Int?) {
        jdbcTemplate.update(
            "INSERT INTO check_results (monitor_id, checked_at, result, status_code, response_time_ms) VALUES (?, ?, ?, ?, ?)",
            monitorId, utc(at), result, statusCode, responseTimeMs,
        )
    }

    private data class HourRow(
        val hour: Instant,
        val total: Int,
        val success: Int,
        val blocked: Int,
        val sum: Long,
        val count: Int,
        val min: Int?,
        val max: Int?,
    )

    private fun stats(monitorId: Long): List<HourRow> =
        jdbcTemplate.query(
            "SELECT * FROM monitor_hourly_stats WHERE monitor_id = ? ORDER BY hour_start",
            { rs, _ ->
                HourRow(
                    hour = rs.getObject("hour_start", LocalDateTime::class.java).toInstant(ZoneOffset.UTC),
                    total = rs.getInt("total_count"),
                    success = rs.getInt("success_count"),
                    blocked = rs.getInt("blocked_count"),
                    sum = rs.getLong("response_time_sum_ms"),
                    count = rs.getInt("response_time_count"),
                    min = rs.getObject("response_time_min_ms") as Int?,
                    max = rs.getObject("response_time_max_ms") as Int?,
                )
            },
            monitorId,
        )

    private fun utc(instant: Instant) = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
}
