package com.apimonitor.check

import com.apimonitor.MutableClock
import com.apimonitor.TestDiscordClient
import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.check.scheduler.CheckDispatcher
import com.apimonitor.global.security.JwtTokenProvider
import com.apimonitor.global.ssrf.HostResolver
import com.apimonitor.global.ssrf.LoadTestAddressConfig
import com.apimonitor.global.ssrf.SafeAddressResolver
import com.apimonitor.member.domain.Role
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.service.MemberService
import com.apimonitor.notification.scheduler.NotificationSender
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 고객 API가 죽었다가 살아나는 과정을 처음부터 끝까지 확인한다.
 *
 * 디스패처, 검사 실행기(실제 HTTP), 결과 처리, 알림 발송기를 모두 실제 빈으로 연결하고,
 * 고객 API는 WireMock, Discord는 기록만 하는 가짜를 쓴다. 시각은 테스트 시계로 다음 검사 시각까지 옮긴다.
 *
 * WireMock은 127.0.0.1에서 뜨므로, 이 테스트에서만 검사 시점의 SSRF 차단에서 127.0.0.1 하나를 뺀다.
 * 등록 시점의 검증은 그대로라 127.0.0.1로는 등록할 수 없다. 그래서 공개 주소로 등록한 뒤 DB의 URL을 바꾼다.
 * 이 설정 때문에 다른 테스트와 컨텍스트(그리고 DB 컨테이너)를 공유하지 않아, 다른 테스트의 모니터가 섞이지 않는다.
 */
@Import(TestcontainersConfiguration::class, CheckFlowScenarioTest.AllowWireMock::class)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckFlowScenarioTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val dispatcher: CheckDispatcher,
    @Autowired private val notificationSender: NotificationSender,
    @Autowired private val discordClient: TestDiscordClient,
    @Autowired private val memberService: MemberService,
    @Autowired private val jwtTokenProvider: JwtTokenProvider,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val clock: MutableClock,
) {
    private val customerApi = WireMockServer(options().dynamicPort()).apply { start() }

    @TestConfiguration(proxyBeanMethods = false)
    class AllowWireMock {
        @Bean
        @Primary
        fun scenarioSafeAddressResolver(hostResolver: HostResolver): SafeAddressResolver =
            SafeAddressResolver(hostResolver, LoadTestAddressConfig.blockRule(setOf(InetAddress.getByName("127.0.0.1"))))
    }

    @BeforeEach
    fun setUp() {
        customerApi.resetAll()
        clock.fixAt(Instant.now().truncatedTo(ChronoUnit.SECONDS))
        // 앞 시나리오의 모니터가 같은 시각에 검사 대상이 되지 않도록 검사 일정에서 뺀다. (이 컨텍스트 전용 DB)
        jdbcTemplate.update("UPDATE monitor_states SET next_check_at = NULL")
    }

    @AfterEach
    fun resetClock() = clock.reset()

    @AfterAll
    fun stopServer() = customerApi.stop()

    @Test
    fun `고객 API가 500을 내면 3번째 실패에 장애 알림, 다시 살아나면 2번째 성공에 복구 알림을 보낸다`() {
        val user = newUser()
        val webhook = "https://discord.com/api/webhooks/${System.nanoTime()}/scenario-token"
        createChannel(user, webhook)
        val monitorId = createMonitor(user, name = "결제 API", path = "/pay/health")
        customerApi.stubFor(get("/pay/health").willReturn(aResponse().withStatus(500)))

        // 실패 1, 2회는 판정 중이라 30초 뒤에 다시 검사한다. 아직 알림은 없다.
        checkNow(monitorId)
        assertThat(secondsUntilNextCheck(monitorId)).isEqualTo(30)
        checkAtNextTime(monitorId)
        assertThat(secondsUntilNextCheck(monitorId)).isEqualTo(30)
        notificationSender.sendDue()
        assertThat(discordClient.messagesTo(webhook)).isEmpty()

        // 실패 3회: 첫 실패 시각으로 장애가 시작되고 장애 알림이 한 번 간다.
        val firstFailureAt = lastCheckedAt(monitorId).minusSeconds(30)
        checkAtNextTime(monitorId)
        notificationSender.sendDue()
        assertThat(discordClient.messagesTo(webhook)).singleElement().asString()
            .contains("장애 감지: 결제 API")
        mockMvc.get("/api/v1/monitors/$monitorId") { bearer(user) }.andExpect {
            jsonPath("$.data.state.status") { value("DOWN") }
            jsonPath("$.data.state.lastStatusCode") { value(500) }
        }
        mockMvc.get("/api/v1/incidents") { bearer(user) }.andExpect {
            jsonPath("$.data.items[0].status") { value("OPEN") }
            jsonPath("$.data.items[0].cause") { value("UNEXPECTED_STATUS") }
        }
        // 장애 중 검사 주기는 설정 주기(1분)와 5분 중 짧은 쪽이다.
        assertThat(secondsUntilNextCheck(monitorId)).isEqualTo(60)

        // 계속 실패해도 장애 알림을 다시 보내지 않는다.
        checkAtNextTime(monitorId)
        notificationSender.sendDue()
        assertThat(discordClient.messagesTo(webhook)).hasSize(1)

        // 고객 API 복구: 성공 1회는 30초 뒤 재검사, 2회 연속이면 첫 성공 시각으로 복구한다.
        customerApi.stubFor(get("/pay/health").willReturn(aResponse().withStatus(200)))
        checkAtNextTime(monitorId)
        val firstSuccessAt = lastCheckedAt(monitorId)
        assertThat(secondsUntilNextCheck(monitorId)).isEqualTo(30)
        checkAtNextTime(monitorId)
        notificationSender.sendDue()

        val messages = discordClient.messagesTo(webhook)
        assertThat(messages).hasSize(2)
        assertThat(messages[1]).contains("복구: 결제 API")
        mockMvc.get("/api/v1/incidents") { bearer(user) }.andExpect {
            jsonPath("$.data.items[0].status") { value("RESOLVED") }
            jsonPath("$.data.items[0].durationSeconds") {
                value(Duration.between(firstFailureAt, firstSuccessAt).seconds.toInt())
            }
        }
        mockMvc.get("/api/v1/dashboard/summary") { bearer(user) }.andExpect {
            jsonPath("$.data.upCount") { value(1) }
            jsonPath("$.data.downCount") { value(0) }
            jsonPath("$.data.recentIncidents[0].status") { value("RESOLVED") }
        }
        assertThat(checkResultCount(monitorId)).isEqualTo(6)
    }

    @Test
    fun `응답 헤더가 제한 시간 안에 오지 않으면 TIMEOUT으로 세어 장애가 된다`() {
        val user = newUser()
        val monitorId = createMonitor(user, name = "느린 API", path = "/slow", timeoutSeconds = 1)
        customerApi.stubFor(get("/slow").willReturn(aResponse().withStatus(200).withFixedDelay(2_000)))

        checkNow(monitorId)
        repeat(2) { checkAtNextTime(monitorId) }

        mockMvc.get("/api/v1/monitors/$monitorId") { bearer(user) }.andExpect {
            jsonPath("$.data.state.status") { value("DOWN") }
            jsonPath("$.data.state.lastResult") { value("TIMEOUT") }
        }
        mockMvc.get("/api/v1/incidents") { bearer(user) }.andExpect {
            jsonPath("$.data.items[0].cause") { value("TIMEOUT") }
        }
    }

    /** 지금 시각에 검사 대상이 되도록 한 뒤 검사한다. */
    private fun checkNow(monitorId: Long) {
        jdbcTemplate.update(
            "UPDATE monitor_states SET next_check_at = ? WHERE monitor_id = ?",
            LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), monitorId,
        )
        dispatchAndWait(monitorId)
    }

    /** 시계를 다음 검사 시각으로 옮기고 디스패처를 한 번 돌린다. */
    private fun checkAtNextTime(monitorId: Long) {
        clock.fixAt(nextCheckAt(monitorId))
        dispatchAndWait(monitorId)
    }

    /** 검사는 코루틴으로 따로 실행되므로, 결과가 DB에 반영될 때까지 기다린다. */
    private fun dispatchAndWait(monitorId: Long) {
        val before = checkResultCount(monitorId)
        assertThat(dispatcher.dispatch()).isEqualTo(1)
        val deadline = System.currentTimeMillis() + 10_000
        while (checkResultCount(monitorId) == before) {
            check(System.currentTimeMillis() < deadline) { "검사 결과가 10초 안에 저장되지 않았다" }
            Thread.sleep(20)
        }
        // 원본 저장과 상태 갱신은 한 트랜잭션이라, 원본이 보이면 상태도 반영되어 있다.
    }

    private fun secondsUntilNextCheck(monitorId: Long): Long =
        Duration.between(clock.instant(), nextCheckAt(monitorId)).seconds

    private fun nextCheckAt(monitorId: Long): Instant = utc("next_check_at", monitorId)

    private fun lastCheckedAt(monitorId: Long): Instant = utc("last_checked_at", monitorId)

    private fun utc(column: String, monitorId: Long): Instant =
        jdbcTemplate.queryForObject(
            "SELECT $column FROM monitor_states WHERE monitor_id = ?",
            { rs, _ -> rs.getObject(1, LocalDateTime::class.java).toInstant(ZoneOffset.UTC) },
            monitorId,
        )!!

    private fun checkResultCount(monitorId: Long): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM check_results WHERE monitor_id = ?", Int::class.java, monitorId)!!

    private fun newUser(): String {
        val id = System.nanoTime().toString()
        val memberId = requireNotNull(memberService.loginWithGithub(GithubUser(id, "user$id", null, null)).id)
        return jwtTokenProvider.createAccessToken(memberId, Role.USER)
    }

    private fun createChannel(token: String, webhookUrl: String) {
        mockMvc.post("/api/v1/notification-channels") {
            bearer(token)
            json("""{ "name": "운영 알림", "webhookUrl": "$webhookUrl" }""")
        }.andExpect { status { isCreated() } }
    }

    /** 공개 주소로 등록한 뒤(등록 시 SSRF 검증 통과), 검사 대상을 WireMock 주소로 바꾼다. */
    private fun createMonitor(token: String, name: String, path: String, timeoutSeconds: Int = 5): Long {
        val body = mockMvc.post("/api/v1/monitors") {
            bearer(token)
            json("""{ "name": "$name", "url": "https://public.example$path", "intervalMinutes": 1, "timeoutSeconds": $timeoutSeconds }""")
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val monitorId = jsonMapper.readTree(body).path("data").path("id").asLong()
        jdbcTemplate.update("UPDATE monitors SET url = ? WHERE id = ?", "http://127.0.0.1:${customerApi.port()}$path", monitorId)
        return monitorId
    }

    private fun MockHttpServletRequestDsl.bearer(token: String) {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private fun MockHttpServletRequestDsl.json(body: String) {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }
}
