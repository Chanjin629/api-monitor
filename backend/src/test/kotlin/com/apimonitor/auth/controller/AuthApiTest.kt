package com.apimonitor.auth.controller

import com.apimonitor.MutableClock
import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.auth.service.TokenService
import com.apimonitor.global.security.JwtTokenProvider
import com.apimonitor.member.domain.Role
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.repository.MemberRepository
import com.apimonitor.member.service.MemberService
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * 인증 API와 보안 설정을 실제 필터 체인과 함께 검증한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokenService: TokenService,
    @Autowired private val memberService: MemberService,
    @Autowired private val memberRepository: MemberRepository,
    @Autowired private val jwtTokenProvider: JwtTokenProvider,
    @Autowired private val clock: MutableClock,
    @Autowired private val jsonMapper: JsonMapper,
) {

    @AfterEach
    fun resetClock() = clock.reset()

    @Test
    fun `데모 로그인으로 받은 토큰으로 내 정보를 조회한다`() {
        val token = demoToken()

        mockMvc.get("/api/v1/members/me") { bearer(token) }.andExpect {
            status { isOk() }
            jsonPath("$.data.role") { value("DEMO") }
            jsonPath("$.data.username") { value("demo") }
        }
    }

    @Test
    fun `데모 계정의 변경 요청은 403 DEMO_READ_ONLY다`() {
        mockMvc.delete("/api/v1/members/me") { bearer(demoToken()) }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("DEMO_READ_ONLY") }
        }
    }

    @Test
    fun `토큰이 없으면 401 AUTH_REQUIRED다`() {
        mockMvc.get("/api/v1/members/me").andExpect {
            status { isUnauthorized() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.error.code") { value("AUTH_REQUIRED") }
        }
        mockMvc.post("/api/v1/monitors").andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("AUTH_REQUIRED") }
        }
    }

    @Test
    fun `만료된 토큰은 AUTH_TOKEN_EXPIRED, 잘못된 토큰은 AUTH_TOKEN_INVALID다`() {
        val token = jwtTokenProvider.createAccessToken(newMember(), Role.USER)
        clock.advance(Duration.ofMinutes(16))

        mockMvc.get("/api/v1/members/me") { bearer(token) }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("AUTH_TOKEN_EXPIRED") }
        }
        mockMvc.get("/api/v1/members/me") { bearer("broken.token.value") }.andExpect {
            jsonPath("$.error.code") { value("AUTH_TOKEN_INVALID") }
        }
        mockMvc.get("/api/v1/members/me") { header(HttpHeaders.AUTHORIZATION, "Basic abc") }.andExpect {
            jsonPath("$.error.code") { value("AUTH_TOKEN_INVALID") }
        }
    }

    @Test
    fun `재발급하면 Access Token과 함께 교체된 Refresh Token 쿠키를 받는다`() {
        val refreshToken = tokenService.issueRefreshToken(newMember())

        val result = mockMvc.post("/api/v1/auth/refresh") { cookie(Cookie("refreshToken", refreshToken)) }.andExpect {
            status { isOk() }
            jsonPath("$.data.accessToken") { isNotEmpty() }
            jsonPath("$.data.expiresIn") { value(900) }
            header {
                string(
                    HttpHeaders.SET_COOKIE,
                    allOf(
                        containsString("refreshToken="),
                        containsString("HttpOnly"),
                        containsString("Path=/api/v1/auth"),
                        containsString("SameSite=Lax"),
                    ),
                )
            }
        }.andReturn()

        val newToken = result.response.getCookie("refreshToken")?.value
        assertThat(newToken).isNotBlank().isNotEqualTo(refreshToken)
    }

    @Test
    fun `쿠키가 없거나 무효한 재발급 요청은 401 AUTH_REFRESH_INVALID다`() {
        mockMvc.post("/api/v1/auth/refresh").andExpect {
            status { isUnauthorized() }
            jsonPath("$.error.code") { value("AUTH_REFRESH_INVALID") }
        }
        mockMvc.post("/api/v1/auth/refresh") { cookie(Cookie("refreshToken", "unknown")) }.andExpect {
            jsonPath("$.error.code") { value("AUTH_REFRESH_INVALID") }
        }
    }

    @Test
    fun `로그아웃하면 쿠키가 삭제되고 그 토큰으로는 재발급할 수 없다`() {
        val refreshToken = tokenService.issueRefreshToken(newMember())

        mockMvc.post("/api/v1/auth/logout") { cookie(Cookie("refreshToken", refreshToken)) }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")) }
        }

        mockMvc.post("/api/v1/auth/refresh") { cookie(Cookie("refreshToken", refreshToken)) }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `회원 탈퇴하면 회원과 토큰이 삭제된다`() {
        val memberId = newMember()
        val refreshToken = tokenService.issueRefreshToken(memberId)
        val accessToken = jwtTokenProvider.createAccessToken(memberId, Role.USER)

        mockMvc.delete("/api/v1/members/me") { bearer(accessToken) }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")) }
        }

        assertThat(memberRepository.existsById(memberId)).isFalse()
        mockMvc.post("/api/v1/auth/refresh") { cookie(Cookie("refreshToken", refreshToken)) }.andExpect {
            status { isUnauthorized() }
        }
    }

    private fun demoToken(): String {
        val body = mockMvc.post("/api/v1/auth/demo").andExpect {
            status { isOk() }
            jsonPath("$.data.expiresIn") { value(3600) }
        }.andReturn().response.contentAsString
        return jsonMapper.readTree(body).path("data").path("accessToken").asString()
    }

    private fun newMember(): Long {
        val id = System.nanoTime().toString()
        return requireNotNull(memberService.loginWithGithub(GithubUser(id, "user$id", null, null)).id)
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.bearer(token: String) {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
