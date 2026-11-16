package com.apimonitor.monitor.controller

import com.apimonitor.MutableClock
import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.global.security.JwtTokenProvider
import com.apimonitor.member.domain.Role
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.service.MemberService
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 모니터 API를 실제 필터 체인, DB와 함께 검증한다.
 * DNS는 TestcontainersConfiguration의 가짜 응답을 쓴다 (public.example = 공인 IP, internal.example = 사설 IP).
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class MonitorApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val memberService: MemberService,
    @Autowired private val jwtTokenProvider: JwtTokenProvider,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val jsonMapper: JsonMapper,
) {

    @AfterEach
    fun resetClock() = clock.reset()

    @Test
    fun `모니터를 등록하면 헤더 값은 가려서 응답하고 DB에는 암호화해 저장한다`() {
        val now = Instant.parse("2026-11-16T03:00:00Z")
        clock.fixAt(now)
        val token = userToken()

        val id = createMonitor(
            token,
            """
            {
              "name": "결제 API",
              "url": "https://public.example/health",
              "intervalMinutes": 5,
              "expectedStatusCodes": [301, 200],
              "headers": [{ "name": "Authorization", "value": "Bearer secret-token" }]
            }
            """,
        ) {
            jsonPath("$.data.name") { value("결제 API") }
            jsonPath("$.data.method") { value("GET") }
            jsonPath("$.data.timeoutSeconds") { value(10) }
            jsonPath("$.data.expectedStatusCodes[0]") { value(200) }
            jsonPath("$.data.expectedStatusCodes[1]") { value(301) }
            jsonPath("$.data.headers[0].name") { value("Authorization") }
            jsonPath("$.data.headers[0].value") { value("********") }
            jsonPath("$.data.state.status") { value("UNKNOWN") }
            jsonPath("$.data.uptime24h") { doesNotExist() }
        }

        val encrypted = jdbcTemplate.queryForObject(
            "SELECT request_headers_encrypted FROM monitors WHERE id = ?",
            String::class.java,
            id,
        )
        assertThat(encrypted).startsWith("v1:").doesNotContain("secret-token")
        // 첫 검사는 0~30초 뒤로 흩어 놓는다. DB에는 UTC 기준 시각으로 저장된다.
        val nextCheckAt = jdbcTemplate.queryForObject(
            "SELECT next_check_at FROM monitor_states WHERE monitor_id = ?",
            { rs, _ -> rs.getObject(1, LocalDateTime::class.java).toInstant(ZoneOffset.UTC) },
            id,
        )
        assertThat(nextCheckAt).isBetween(now, now.plusSeconds(30))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://127.0.0.1/health",
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]/",
            "http://2130706433/",
            "https://internal.example/health",
            "https://mixed.example/health",
            "ftp://public.example/file",
        ],
    )
    fun `내부망이나 허용하지 않는 주소는 422 URL_BLOCKED다`(url: String) {
        mockMvc.post("/api/v1/monitors") {
            bearer(userToken())
            json("""{ "name": "내부", "url": "$url", "intervalMinutes": 1 }""")
        }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.error.code") { value("URL_BLOCKED") }
            // 어떤 대역에 걸렸는지는 알려 주지 않는다.
            jsonPath("$.error.details") { isEmpty() }
        }
    }

    @Test
    fun `입력값이 규칙에 맞지 않으면 400 VALIDATION_FAILED와 문제 필드를 알려 준다`() {
        val token = userToken()

        expectValidationFailed(token, """{ "url": "https://public.example", "intervalMinutes": 1 }""", "name")
        expectValidationFailed(token, """{ "name": "a", "url": "https://public.example", "intervalMinutes": 7 }""", "intervalMinutes")
        expectValidationFailed(
            token,
            """{ "name": "a", "url": "https://public.example", "intervalMinutes": 1, "timeoutSeconds": 31 }""",
            "timeoutSeconds",
        )
        expectValidationFailed(
            token,
            """{ "name": "a", "url": "https://public.example", "intervalMinutes": 1, "expectedStatusCodes": [700] }""",
            "expectedStatusCodes",
        )
        expectValidationFailed(
            token,
            """{ "name": "a", "url": "https://public.example", "intervalMinutes": 1, "headers": [{ "name": "X Bad", "value": "v" }] }""",
            "headers[0].name",
        )
    }

    @Test
    fun `Host 같은 헤더는 422 HEADER_NOT_ALLOWED다`() {
        mockMvc.post("/api/v1/monitors") {
            bearer(userToken())
            json("""{ "name": "a", "url": "https://public.example", "intervalMinutes": 1, "headers": [{ "name": "host", "value": "evil" }] }""")
        }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.error.code") { value("HEADER_NOT_ALLOWED") }
            jsonPath("$.error.details[0].field") { value("headers[0].name") }
        }
    }

    @Test
    fun `모니터는 20개까지 등록할 수 있다`() {
        val token = userToken()
        repeat(20) { createMonitor(token, simpleMonitor("모니터 $it")) }

        mockMvc.post("/api/v1/monitors") {
            bearer(token)
            json(simpleMonitor("21번째"))
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("MONITOR_LIMIT_EXCEEDED") }
        }
        mockMvc.get("/api/v1/monitors") { bearer(token) }.andExpect {
            jsonPath("$.data.items", hasSize<Any>(20))
            jsonPath("$.data.items[0].name") { value("모니터 0") }
            jsonPath("$.data.nextCursor") { doesNotExist() }
        }
    }

    @Test
    fun `다른 회원의 모니터는 조회, 수정, 삭제, 정지 모두 404다`() {
        val id = createMonitor(userToken(), simpleMonitor("남의 모니터"))
        val otherToken = userToken()

        mockMvc.get("/api/v1/monitors/$id") { bearer(otherToken) }.andExpectNotFound()
        mockMvc.patch("/api/v1/monitors/$id") { bearer(otherToken); json("""{ "name": "탈취" }""") }.andExpectNotFound()
        mockMvc.delete("/api/v1/monitors/$id") { bearer(otherToken) }.andExpectNotFound()
        mockMvc.post("/api/v1/monitors/$id/pause") { bearer(otherToken) }.andExpectNotFound()
        mockMvc.get("/api/v1/monitors") { bearer(otherToken) }.andExpect {
            jsonPath("$.data.items") { isEmpty() }
        }
    }

    @Test
    fun `일시 정지하면 검사 대상에서 빠지고 재개하면 처음부터 다시 판정한다`() {
        val token = userToken()
        val id = createMonitor(token, simpleMonitor("정지 테스트"))

        mockMvc.post("/api/v1/monitors/$id/pause") { bearer(token) }.andExpect {
            status { isOk() }
            jsonPath("$.data.state.status") { value("PAUSED") }
            jsonPath("$.data.state.nextCheckAt") { doesNotExist() }
        }
        mockMvc.post("/api/v1/monitors/$id/pause") { bearer(token) }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("MONITOR_ALREADY_PAUSED") }
        }
        mockMvc.post("/api/v1/monitors/$id/resume") { bearer(token) }.andExpect {
            status { isOk() }
            jsonPath("$.data.state.status") { value("UNKNOWN") }
            jsonPath("$.data.state.nextCheckAt") { exists() }
        }
        mockMvc.post("/api/v1/monitors/$id/resume") { bearer(token) }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("MONITOR_NOT_PAUSED") }
        }
    }

    @Test
    fun `진행 중인 장애가 있는 모니터를 재개하면 DOWN에서 다시 판정한다`() {
        val token = userToken()
        val id = createMonitor(token, simpleMonitor("장애 중"))
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        jdbcTemplate.update(
            "INSERT INTO incidents (monitor_id, started_at, detected_at, cause) VALUES (?, ?, ?, 'TIMEOUT')",
            id,
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now),
        )

        mockMvc.post("/api/v1/monitors/$id/pause") { bearer(token) }
        mockMvc.post("/api/v1/monitors/$id/resume") { bearer(token) }.andExpect {
            jsonPath("$.data.state.status") { value("DOWN") }
        }
    }

    @Test
    fun `수정은 보낸 필드만 바꾸고 URL을 바꾸면 연속 카운터를 초기화한다`() {
        val token = userToken()
        val id = createMonitor(
            token,
            """{ "name": "수정 전", "url": "https://public.example/a", "intervalMinutes": 1,
                 "headers": [{ "name": "X-Api-Key", "value": "secret" }] }""",
        )
        jdbcTemplate.update("UPDATE monitor_states SET status = 'UP', consecutive_failures = 2 WHERE monitor_id = ?", id)

        mockMvc.patch("/api/v1/monitors/$id") {
            bearer(token)
            json("""{ "intervalMinutes": 10 }""")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.name") { value("수정 전") }
            jsonPath("$.data.intervalMinutes") { value(10) }
            jsonPath("$.data.headers[0].name") { value("X-Api-Key") }
            jsonPath("$.data.state.consecutiveFailures") { value(2) }
        }
        mockMvc.patch("/api/v1/monitors/$id") {
            bearer(token)
            json("""{ "url": "https://public.example/b", "headers": [] }""")
        }.andExpect {
            jsonPath("$.data.url") { value("https://public.example/b") }
            jsonPath("$.data.headers") { isEmpty() }
            jsonPath("$.data.state.status") { value("UNKNOWN") }
            jsonPath("$.data.state.consecutiveFailures") { value(0) }
        }
        mockMvc.patch("/api/v1/monitors/$id") {
            bearer(token)
            json("""{ "url": "http://10.0.0.1/" }""")
        }.andExpect {
            jsonPath("$.error.code") { value("URL_BLOCKED") }
        }
    }

    @Test
    fun `가려진 헤더 값을 그대로 다시 보내면 400으로 거부한다`() {
        val token = userToken()
        val id = createMonitor(token, simpleMonitor("헤더"))

        mockMvc.patch("/api/v1/monitors/$id") {
            bearer(token)
            json("""{ "headers": [{ "name": "Authorization", "value": "********" }] }""")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_FAILED") }
            jsonPath("$.error.details[0].field") { value("headers[0].value") }
        }
    }

    @Test
    fun `삭제하면 다시 조회할 수 없다`() {
        val token = userToken()
        val id = createMonitor(token, simpleMonitor("삭제"))

        mockMvc.delete("/api/v1/monitors/$id") { bearer(token) }.andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
        }
        mockMvc.get("/api/v1/monitors/$id") { bearer(token) }.andExpectNotFound()
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM monitor_states WHERE monitor_id = ?", Long::class.java, id))
            .isZero()
    }

    @Test
    fun `데모 계정은 모니터를 조회할 수 있지만 등록할 수 없다`() {
        val body = mockMvc.post("/api/v1/auth/demo").andReturn().response.contentAsString
        val demoToken = jsonMapper.readTree(body).path("data").path("accessToken").asString()

        mockMvc.get("/api/v1/monitors") { bearer(demoToken) }.andExpect { status { isOk() } }
        mockMvc.post("/api/v1/monitors") {
            bearer(demoToken)
            json(simpleMonitor("데모"))
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("DEMO_READ_ONLY") }
        }
    }

    private fun createMonitor(token: String, body: String, extra: org.springframework.test.web.servlet.MockMvcResultMatchersDsl.() -> Unit = {}): Long {
        val response = mockMvc.post("/api/v1/monitors") {
            bearer(token)
            json(body)
        }.andExpect {
            status { isCreated() }
            extra()
        }.andReturn().response.contentAsString
        return jsonMapper.readTree(response).path("data").path("id").asLong()
    }

    private fun expectValidationFailed(token: String, body: String, field: String) {
        mockMvc.post("/api/v1/monitors") {
            bearer(token)
            json(body)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_FAILED") }
            jsonPath("$.error.details[0].field") { value(field) }
        }
    }

    private fun simpleMonitor(name: String) = """{ "name": "$name", "url": "https://public.example/health", "intervalMinutes": 1 }"""

    private fun userToken(): String {
        val id = System.nanoTime().toString()
        val memberId = requireNotNull(memberService.loginWithGithub(GithubUser(id, "user$id", null, null)).id)
        return jwtTokenProvider.createAccessToken(memberId, Role.USER)
    }

    private fun ResultActionsDsl.andExpectNotFound() = andExpect {
        status { isNotFound() }
        jsonPath("$.error.code") { value("MONITOR_NOT_FOUND") }
    }

    private fun MockHttpServletRequestDsl.bearer(token: String) {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private fun MockHttpServletRequestDsl.json(body: String) {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }
}
