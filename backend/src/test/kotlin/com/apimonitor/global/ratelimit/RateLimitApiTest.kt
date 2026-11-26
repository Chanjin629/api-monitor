package com.apimonitor.global.ratelimit

import com.apimonitor.MutableClock
import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.global.security.AuthMember
import com.apimonitor.global.security.JwtTokenProvider
import com.apimonitor.member.domain.Role
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.service.MemberService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 요청 제한을 실제 보안 필터 체인과 Redis 컨테이너로 검증한다.
 * 다른 테스트는 요청 제한을 끄므로(application-test.yml) 이 테스트만 켠다.
 * 테스트마다 다른 IP와 회원을 써서 서로의 횟수가 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["ratelimit.enabled=true"])
@AutoConfigureMockMvc
class RateLimitApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val memberService: MemberService,
    @Autowired private val jwtTokenProvider: JwtTokenProvider,
    @Autowired private val clock: MutableClock,
) {

    /** 1분 칸이 테스트 도중 바뀌면 횟수가 0부터 다시 세어지므로, 시각을 분의 30초 지점에 고정한다. */
    @BeforeEach
    fun fixClock() = clock.fixAt(Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30))

    @AfterEach
    fun reset() {
        SecurityContextHolder.clearContext()
        clock.reset()
    }

    @Test
    fun `데모 로그인은 IP당 분당 10번까지이고, 11번째는 429와 Retry-After다`() {
        val ip = newIp()
        repeat(10) {
            mockMvc.post("/api/v1/auth/demo") { from(ip) }.andExpect { status { isOk() } }
        }

        mockMvc.post("/api/v1/auth/demo") { from(ip) }.andExpect {
            status { isTooManyRequests() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.error.code") { value("RATE_LIMITED") }
            jsonPath("$.error.traceId") { exists() }
            // 분의 30초 지점이므로 다음 1분 칸까지 30초 남았다.
            header { string(HttpHeaders.RETRY_AFTER, "30") }
        }

        // 다른 IP는 따로 센다.
        mockMvc.post("/api/v1/auth/demo") { from(newIp()) }.andExpect { status { isOk() } }
    }

    @Test
    fun `X-Forwarded-For를 바꿔 보내도 실제 접속 IP로 센다`() {
        val ip = newIp()
        repeat(10) { i ->
            mockMvc.post("/api/v1/auth/demo") {
                from(ip)
                header("X-Forwarded-For", "198.51.100.$i")
            }.andExpect { status { isOk() } }
        }

        mockMvc.post("/api/v1/auth/demo") {
            from(ip)
            header("X-Forwarded-For", "198.51.100.99")
        }.andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `GitHub 로그인 시작도 IP당 분당 10번까지다`() {
        val ip = newIp()
        repeat(10) {
            mockMvc.get("/oauth2/authorization/github") { from(ip) }.andExpect { status { is3xxRedirection() } }
        }

        mockMvc.get("/oauth2/authorization/github") { from(ip) }.andExpect {
            status { isTooManyRequests() }
            jsonPath("$.error.code") { value("RATE_LIMITED") }
        }
    }

    @Test
    fun `알림 테스트 발송은 회원당 분당 5번까지이고, 같은 IP의 다른 회원은 따로 센다`() {
        val ip = newIp()
        val first = userToken()
        // 없는 채널이라 404지만 횟수는 센다. 실제 발송 없이 규칙만 확인한다.
        repeat(5) {
            mockMvc.post("/api/v1/notification-channels/999999999/test") {
                from(ip)
                bearer(first)
            }.andExpect { status { isNotFound() } }
        }

        mockMvc.post("/api/v1/notification-channels/999999999/test") {
            from(ip)
            bearer(first)
        }.andExpect { status { isTooManyRequests() } }

        mockMvc.post("/api/v1/notification-channels/999999999/test") {
            from(ip)
            bearer(userToken())
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `일반 회원은 회원 ID로, 데모 계정과 비로그인은 IP로 센다`() {
        assertThat(targetOf("/api/v1/monitors", AuthMember(7, Role.USER))).isEqualTo(
            RateLimitTarget(RateLimitRule.API, "member:7"),
        )
        assertThat(targetOf("/api/v1/monitors", AuthMember(8, Role.DEMO))).isEqualTo(
            RateLimitTarget(RateLimitRule.API, "ip:203.0.113.5"),
        )
        assertThat(targetOf("/api/v1/monitors", null)).isEqualTo(
            RateLimitTarget(RateLimitRule.API, "ip:203.0.113.5"),
        )
    }

    @Test
    fun `Swagger, Actuator, GitHub 콜백은 제한하지 않는다`() {
        listOf("/v3/api-docs", "/swagger-ui/index.html", "/actuator/health", "/login/oauth2/code/github").forEach {
            assertThat(targetOf(it, null, method = "GET")).isNull()
        }
    }

    private fun targetOf(path: String, member: AuthMember?, method: String = "GET"): RateLimitTarget? {
        SecurityContextHolder.clearContext()
        if (member != null) {
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken.authenticated(member, null, member.authorities())
        }
        val request = MockHttpServletRequest(method, path).apply { remoteAddr = "203.0.113.5" }
        return RateLimitTargets.of(request)
    }

    private fun userToken(): String {
        val id = System.nanoTime().toString()
        val memberId = requireNotNull(memberService.loginWithGithub(GithubUser(id, "user$id", null, null)).id)
        return jwtTokenProvider.createAccessToken(memberId, Role.USER)
    }

    private fun MockHttpServletRequestDsl.from(ip: String) {
        with { it.remoteAddr = ip; it }
    }

    private fun MockHttpServletRequestDsl.bearer(token: String) {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    /** 문서용 예약 대역(192.0.2.0/24)에서 테스트마다 다른 IP를 만든다. */
    private fun newIp(): String = "192.0.2.${counter.incrementAndGet()}"

    companion object {
        private val counter = AtomicInteger()
    }
}
