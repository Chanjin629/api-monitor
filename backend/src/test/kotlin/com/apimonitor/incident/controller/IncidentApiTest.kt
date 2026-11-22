package com.apimonitor.incident.controller

import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.global.security.JwtTokenProvider
import com.apimonitor.member.domain.Role
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.service.MemberService
import com.apimonitor.monitor.dto.CreateMonitorRequest
import com.apimonitor.monitor.service.MonitorService
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasSize
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class IncidentApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val memberService: MemberService,
    @Autowired private val monitorService: MonitorService,
    @Autowired private val jwtTokenProvider: JwtTokenProvider,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @Test
    fun `내 모니터의 장애만 최신순으로 보여 주고, 상태로 거를 수 있다`() {
        val (memberId, token) = newMember()
        val payment = newMonitor(memberId, "결제 API")
        val member = newMonitor(memberId, "회원 API")
        val old = incident(payment, resolved = true)
        val open = incident(payment, resolved = false)
        val other = incident(member, resolved = true)
        val (otherMemberId, _) = newMember()
        incident(newMonitor(otherMemberId, "남의 API"), resolved = false)

        mockMvc.get("/api/v1/incidents") { bearer(token) }.andExpect {
            status { isOk() }
            jsonPath("$.data.items", hasSize<Any>(3))
            jsonPath("$.data.items[0].id") { value(other) }
            jsonPath("$.data.items[0].monitor.name") { value("회원 API") }
            jsonPath("$.data.items[1].id") { value(open) }
            jsonPath("$.data.items[1].status") { value("OPEN") }
            jsonPath("$.data.items[1].resolvedAt") { doesNotExist() }
            jsonPath("$.data.items[2].id") { value(old) }
            jsonPath("$.data.items[2].durationSeconds") { value(300) }
            jsonPath("$.data.nextCursor") { doesNotExist() }
        }
        mockMvc.get("/api/v1/incidents?status=OPEN") { bearer(token) }.andExpect {
            jsonPath("$.data.items", hasSize<Any>(1))
            jsonPath("$.data.items[0].id") { value(open) }
        }
        mockMvc.get("/api/v1/incidents?status=RESOLVED") { bearer(token) }.andExpect {
            jsonPath("$.data.items", hasSize<Any>(2))
        }
        mockMvc.get("/api/v1/monitors/$payment/incidents") { bearer(token) }.andExpect {
            jsonPath("$.data.items", hasSize<Any>(2))
        }
    }

    @Test
    fun `커서를 따라 다음 페이지를 읽는다`() {
        val (memberId, token) = newMember()
        val monitorId = newMonitor(memberId, "API")
        val ids = (1..3).map { incident(monitorId, resolved = true) }

        val first = jsonMapper.readTree(
            mockMvc.get("/api/v1/incidents?limit=2") { bearer(token) }.andReturn().response.contentAsString,
        ).path("data")
        val cursor = first.path("nextCursor").asString()
        val second = jsonMapper.readTree(
            mockMvc.get("/api/v1/incidents?limit=2&cursor=$cursor") { bearer(token) }.andReturn().response.contentAsString,
        ).path("data")

        val read = (first.path("items") + second.path("items")).map { it.path("id").asLong() }
        assertThat(read).containsExactlyElementsOf(ids.reversed())
        assertThat(second.path("nextCursor").isNull).isTrue()
    }

    @Test
    fun `다른 회원의 모니터, 잘못된 커서와 개수, 잘못된 상태는 거부한다`() {
        val (otherMemberId, _) = newMember()
        val othersMonitor = newMonitor(otherMemberId, "남의 API")
        val (_, token) = newMember()

        mockMvc.get("/api/v1/monitors/$othersMonitor/incidents") { bearer(token) }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("MONITOR_NOT_FOUND") }
        }
        mockMvc.get("/api/v1/incidents?cursor=%%%") { bearer(token) }.andExpect { status { isBadRequest() } }
        mockMvc.get("/api/v1/incidents?limit=0") { bearer(token) }.andExpect { status { isBadRequest() } }
        mockMvc.get("/api/v1/incidents?status=WHATEVER") { bearer(token) }.andExpect { status { isBadRequest() } }
    }

    private fun newMember(): Pair<Long, String> {
        val id = System.nanoTime().toString()
        val memberId = requireNotNull(memberService.loginWithGithub(GithubUser(id, "user$id", null, null)).id)
        return memberId to jwtTokenProvider.createAccessToken(memberId, Role.USER)
    }

    private fun newMonitor(memberId: Long, name: String): Long = monitorService.create(
        memberId,
        CreateMonitorRequest(name = name, url = "https://public.example/health", intervalMinutes = 1),
    ).id

    /** 장애를 만들고 ID를 돌려준다. 같은 모니터의 진행 중 장애는 하나뿐이므로 복구된 장애를 먼저 만든다. */
    private fun incident(monitorId: Long, resolved: Boolean): Long {
        val startedAt = now.minusSeconds(3600)
        jdbcTemplate.update(
            """
            INSERT INTO incidents (monitor_id, started_at, detected_at, resolved_at, duration_seconds, cause, last_status_code)
            VALUES (?, ?, ?, ?, ?, 'UNEXPECTED_STATUS', 503)
            """.trimIndent(),
            monitorId, utc(startedAt), utc(startedAt.plusSeconds(60)),
            if (resolved) utc(startedAt.plusSeconds(300)) else null,
            if (resolved) 300 else null,
        )
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM incidents", Long::class.java)!!
    }

    private fun utc(instant: Instant) = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun MockHttpServletRequestDsl.bearer(token: String) {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
