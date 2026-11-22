package com.apimonitor.dashboard.controller

import com.apimonitor.MutableClock
import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.global.security.JwtTokenProvider
import com.apimonitor.member.domain.Role
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.service.MemberService
import com.apimonitor.monitor.dto.CreateMonitorRequest
import com.apimonitor.monitor.service.MonitorService
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** 대시보드 요약, 응답 시간 그래프, 검사 기록 API를 실제 DB로 확인한다. 시각은 고정해 묶음 경계를 예측 가능하게 한다. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class DashboardApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val memberService: MemberService,
    @Autowired private val monitorService: MonitorService,
    @Autowired private val jwtTokenProvider: JwtTokenProvider,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val clock: MutableClock,
) {
    /** 5분·1시간 경계에서 30분 지난 시각 */
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofMinutes(30))

    @BeforeEach
    fun fixClock() = clock.fixAt(now)

    @AfterEach
    fun resetClock() = clock.reset()

    @Test
    fun `24시간 그래프는 원본을 5분씩 묶고, 검사가 없던 구간은 점을 만들지 않는다`() {
        val (memberId, token) = newMember()
        val monitorId = newMonitor(memberId)
        result(monitorId, now.minus(Duration.ofHours(2)), "SUCCESS", 200)
        result(monitorId, now.minusSeconds(600), "SUCCESS", 100)
        result(monitorId, now.minusSeconds(540), "SUCCESS", 300)
        result(monitorId, now.minusSeconds(480), "BLOCKED", null)
        result(monitorId, now.minusSeconds(180), "TIMEOUT", null)

        mockMvc.get("/api/v1/monitors/$monitorId/stats?range=24h") { bearer(token) }.andExpect {
            status { isOk() }
            jsonPath("$.data.bucketMinutes") { value(5) }
            // 성공 3 ÷ (전체 5 - 차단 1)
            jsonPath("$.data.uptimePercent") { value(75.0) }
            jsonPath("$.data.averageResponseTimeMs") { value(200) }
            jsonPath("$.data.points", hasSize<Any>(3))
            jsonPath("$.data.points[0].time") { value(now.minus(Duration.ofHours(2)).toString()) }
            jsonPath("$.data.points[1].time") { value(now.minusSeconds(600).toString()) }
            jsonPath("$.data.points[1].totalCount") { value(3) }
            jsonPath("$.data.points[1].successCount") { value(2) }
            jsonPath("$.data.points[1].minResponseTimeMs") { value(100) }
            jsonPath("$.data.points[1].maxResponseTimeMs") { value(300) }
            jsonPath("$.data.points[2].averageResponseTimeMs") { value(nullValue()) }
        }
    }

    @Test
    fun `7일 그래프는 시간별 집계를 1시간씩, 30일은 6시간씩 묶는다`() {
        val (memberId, token) = newMember()
        val monitorId = newMonitor(memberId)
        val currentHour = now.truncatedTo(ChronoUnit.HOURS)
        hourly(monitorId, currentHour.minus(Duration.ofHours(3)), total = 60, success = 60, sum = 6000, count = 60)
        hourly(monitorId, currentHour.minus(Duration.ofHours(2)), total = 60, success = 30, sum = 18000, count = 60)

        mockMvc.get("/api/v1/monitors/$monitorId/stats?range=7d") { bearer(token) }.andExpect {
            jsonPath("$.data.bucketMinutes") { value(60) }
            jsonPath("$.data.points", hasSize<Any>(2))
            jsonPath("$.data.points[0].time") { value(currentHour.minus(Duration.ofHours(3)).toString()) }
            jsonPath("$.data.points[1].averageResponseTimeMs") { value(300) }
            jsonPath("$.data.uptimePercent") { value(75.0) }
            jsonPath("$.data.averageResponseTimeMs") { value(200) }
        }

        val sixHourStart = Instant.ofEpochSecond(currentHour.minus(Duration.ofHours(2)).epochSecond / 21600 * 21600)
        val sameBlock = currentHour.minus(Duration.ofHours(3)).epochSecond / 21600 == currentHour.minus(Duration.ofHours(2)).epochSecond / 21600
        mockMvc.get("/api/v1/monitors/$monitorId/stats?range=30d") { bearer(token) }.andExpect {
            jsonPath("$.data.bucketMinutes") { value(360) }
            jsonPath("$.data.points", hasSize<Any>(if (sameBlock) 1 else 2))
            jsonPath("$.data.points[-1].time") { value(sixHourStart.toString()) }
        }
    }

    @Test
    fun `잘못된 기간은 400이고, 다른 회원의 모니터는 404다`() {
        val (memberId, token) = newMember()
        val monitorId = newMonitor(memberId)
        val (_, otherToken) = newMember()

        mockMvc.get("/api/v1/monitors/$monitorId/stats?range=1y") { bearer(token) }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.details[0].field") { value("range") }
        }
        mockMvc.get("/api/v1/monitors/$monitorId/stats") { bearer(otherToken) }.andExpect {
            jsonPath("$.error.code") { value("MONITOR_NOT_FOUND") }
        }
        mockMvc.get("/api/v1/monitors/$monitorId/checks") { bearer(otherToken) }.andExpect {
            jsonPath("$.error.code") { value("MONITOR_NOT_FOUND") }
        }
    }

    @Test
    fun `검사 기록은 최신순으로 커서를 따라 끝까지 읽을 수 있다`() {
        val (memberId, token) = newMember()
        val monitorId = newMonitor(memberId)
        (1..5).forEach { result(monitorId, now.minusSeconds(it * 60L), "SUCCESS", 100 + it) }

        val first = checks(token, monitorId, "limit=2")
        val second = checks(token, monitorId, "limit=2&cursor=${first.nextCursor}")
        val third = checks(token, monitorId, "limit=2&cursor=${second.nextCursor}")

        val times = (first.times + second.times + third.times)
        org.assertj.core.api.Assertions.assertThat(times).containsExactly(*(1..5).map { now.minusSeconds(it * 60L) }.toTypedArray())
        org.assertj.core.api.Assertions.assertThat(third.nextCursor).isNull()

        mockMvc.get("/api/v1/monitors/$monitorId/checks?cursor=broken") { bearer(token) }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.details[0].field") { value("cursor") }
        }
    }

    @Test
    fun `요약은 상태별 수, 24시간 가동률, 진행 중이거나 최근 복구된 장애를 보여 준다`() {
        val (memberId, token) = newMember()
        val up = newMonitor(memberId)
        val down = newMonitor(memberId)
        val paused = newMonitor(memberId)
        setStatus(up, "UP")
        setStatus(down, "DOWN")
        monitorService.pause(memberId, paused)
        result(up, now.minusSeconds(60), "SUCCESS", 100)
        result(down, now.minusSeconds(60), "TIMEOUT", null)
        result(down, now.minus(Duration.ofHours(30)), "SUCCESS", 100) // 24시간 밖
        incident(down, startedAt = now.minusSeconds(600), resolvedAt = null)
        incident(up, startedAt = now.minus(Duration.ofHours(5)), resolvedAt = now.minus(Duration.ofHours(4)))
        incident(up, startedAt = now.minus(Duration.ofDays(3)), resolvedAt = now.minus(Duration.ofDays(3)).plusSeconds(60))

        mockMvc.get("/api/v1/dashboard/summary") { bearer(token) }.andExpect {
            status { isOk() }
            jsonPath("$.data.totalMonitors") { value(3) }
            jsonPath("$.data.upCount") { value(1) }
            jsonPath("$.data.downCount") { value(1) }
            jsonPath("$.data.pausedCount") { value(1) }
            jsonPath("$.data.uptime24h") { value(50.0) }
            jsonPath("$.data.recentIncidents", hasSize<Any>(2))
            jsonPath("$.data.recentIncidents[0].status") { value("OPEN") }
            jsonPath("$.data.recentIncidents[0].monitor.id") { value(down) }
            jsonPath("$.data.recentIncidents[1].status") { value("RESOLVED") }
        }
        mockMvc.get("/api/v1/monitors") { bearer(token) }.andExpect {
            jsonPath("$.data.items[0].uptime24h") { value(100.0) }
            jsonPath("$.data.items[1].uptime24h") { value(0.0) }
            jsonPath("$.data.items[2].uptime24h") { value(nullValue()) }
        }
    }

    @Test
    fun `모니터가 없으면 요약은 0과 null이다`() {
        val (_, token) = newMember()

        mockMvc.get("/api/v1/dashboard/summary") { bearer(token) }.andExpect {
            jsonPath("$.data.totalMonitors") { value(0) }
            jsonPath("$.data.uptime24h") { value(nullValue()) }
            jsonPath("$.data.recentIncidents") { isEmpty() }
        }
    }

    private data class CheckPage(val times: List<Instant>, val nextCursor: String?)

    private fun checks(token: String, monitorId: Long, query: String): CheckPage {
        val body = mockMvc.get("/api/v1/monitors/$monitorId/checks?$query") { bearer(token) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        val data = jsonMapper.readTree(body).path("data")
        val cursor = data.path("nextCursor").takeUnless { it.isNull }?.asString()
        return CheckPage(data.path("items").toList().map { Instant.parse(it.path("checkedAt").asString()) }, cursor)
    }

    private fun newMember(): Pair<Long, String> {
        val id = System.nanoTime().toString()
        val memberId = requireNotNull(memberService.loginWithGithub(GithubUser(id, "user$id", null, null)).id)
        return memberId to jwtTokenProvider.createAccessToken(memberId, Role.USER)
    }

    private fun newMonitor(memberId: Long): Long = monitorService.create(
        memberId,
        CreateMonitorRequest(name = "대시보드", url = "https://public.example/health", intervalMinutes = 1),
    ).id

    private fun result(monitorId: Long, at: Instant, result: String, responseTimeMs: Int?) {
        jdbcTemplate.update(
            "INSERT INTO check_results (monitor_id, checked_at, result, status_code, response_time_ms) VALUES (?, ?, ?, ?, ?)",
            monitorId, utc(at), result, if (responseTimeMs != null) 200 else null, responseTimeMs,
        )
    }

    private fun hourly(monitorId: Long, hour: Instant, total: Int, success: Int, sum: Long, count: Int) {
        jdbcTemplate.update(
            """
            INSERT INTO monitor_hourly_stats (monitor_id, hour_start, total_count, success_count, blocked_count,
                response_time_sum_ms, response_time_count, response_time_min_ms, response_time_max_ms)
            VALUES (?, ?, ?, ?, 0, ?, ?, 50, 900)
            """.trimIndent(),
            monitorId, utc(hour), total, success, sum, count,
        )
    }

    private fun setStatus(monitorId: Long, status: String) {
        jdbcTemplate.update("UPDATE monitor_states SET status = ? WHERE monitor_id = ?", status, monitorId)
    }

    private fun incident(monitorId: Long, startedAt: Instant, resolvedAt: Instant?) {
        jdbcTemplate.update(
            """
            INSERT INTO incidents (monitor_id, started_at, detected_at, resolved_at, duration_seconds, cause)
            VALUES (?, ?, ?, ?, ?, 'TIMEOUT')
            """.trimIndent(),
            monitorId, utc(startedAt), utc(startedAt.plusSeconds(60)), resolvedAt?.let(::utc),
            resolvedAt?.let { Duration.between(startedAt, it).seconds.toInt() },
        )
    }

    private fun utc(instant: Instant) = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun MockHttpServletRequestDsl.bearer(token: String) {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
