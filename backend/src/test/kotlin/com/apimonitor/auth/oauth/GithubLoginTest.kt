package com.apimonitor.auth.oauth

import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.member.domain.AuthProvider
import com.apimonitor.member.repository.MemberRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.net.URLDecoder

/**
 * GitHub 로그인. 실제 GitHub는 호출하지 않고, Spring Security가 넘겨주는 로그인 결과를 흉내 내 확인한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class GithubLoginTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val successHandler: OAuth2LoginSuccessHandler,
    @Autowired private val failureHandler: OAuth2LoginFailureHandler,
    @Autowired private val memberRepository: MemberRepository,
) {

    @Test
    fun `로그인을 시작하면 state를 붙여 GitHub 권한 승인 화면으로 보낸다`() {
        val location = mockMvc.get("/oauth2/authorization/github").andExpect {
            status { is3xxRedirection() }
        }.andReturn().response.getHeader(HttpHeaders.LOCATION)!!
        val decoded = URLDecoder.decode(location, Charsets.UTF_8)

        assertThat(decoded)
            .startsWith("https://github.com/login/oauth/authorize")
            .contains("client_id=test-client-id")
            .contains("scope=read:user")
            .contains("state=")
            .contains("/login/oauth2/code/github")
    }

    @Test
    fun `첫 로그인이면 회원을 만들고 Refresh Token 쿠키와 함께 콜백 화면으로 보낸다`() {
        val githubId = System.nanoTime()
        val response = login(githubId, login = "octocat", name = "The Octocat")

        assertThat(response.redirectedUrl).isEqualTo("/auth/callback")
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("refreshToken=", "HttpOnly")

        val member = memberRepository.findByProviderAndProviderUserId(AuthProvider.GITHUB, githubId.toString())!!
        assertThat(member.username).isEqualTo("octocat")
        assertThat(member.displayName).isEqualTo("The Octocat")
        assertThat(member.lastLoginAt).isNotNull()
    }

    @Test
    fun `다시 로그인하면 같은 회원의 프로필을 갱신한다`() {
        val githubId = System.nanoTime()
        login(githubId, login = "old-name", name = null)
        val countAfterFirst = memberRepository.count()

        login(githubId, login = "new-name", name = "New Name")

        assertThat(memberRepository.count()).isEqualTo(countAfterFirst)
        val member = memberRepository.findByProviderAndProviderUserId(AuthProvider.GITHUB, githubId.toString())!!
        assertThat(member.username).isEqualTo("new-name")
        assertThat(member.displayName).isEqualTo("New Name")
    }

    @Test
    fun `로그인에 실패하면 랜딩 화면으로 오류 표시와 함께 보낸다`() {
        val response = MockHttpServletResponse()

        failureHandler.onAuthenticationFailure(
            MockHttpServletRequest(),
            response,
            OAuth2AuthenticationException(OAuth2Error("access_denied")),
        )

        assertThat(response.redirectedUrl).isEqualTo("/?loginError=true")
    }

    private fun login(githubId: Long, login: String, name: String?): MockHttpServletResponse {
        val attributes = mutableMapOf<String, Any>("id" to githubId, "login" to login, "avatar_url" to "https://avatars.example/$githubId")
        name?.let { attributes["name"] = it }
        val authorities = listOf(SimpleGrantedAuthority("OAUTH2_USER"))
        val authentication = OAuth2AuthenticationToken(DefaultOAuth2User(authorities, attributes, "id"), authorities, "github")

        val response = MockHttpServletResponse()
        successHandler.onAuthenticationSuccess(MockHttpServletRequest(), response, authentication)
        return response
    }
}
