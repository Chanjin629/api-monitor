package com.apimonitor.auth.oauth

import com.apimonitor.auth.service.CookieUtil
import com.apimonitor.auth.service.TokenService
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.service.MemberService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component

/**
 * GitHub 로그인 성공 처리.
 * Spring Security가 state 검증, code 교환, 사용자 정보 조회를 마친 뒤 호출한다.
 */
@Component
class OAuth2LoginSuccessHandler(
    private val memberService: MemberService,
    private val tokenService: TokenService,
    private val cookieUtil: CookieUtil,
) : AuthenticationSuccessHandler {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val oauth2 = authentication as OAuth2AuthenticationToken
        check(oauth2.authorizedClientRegistrationId == GITHUB) {
            "지원하지 않는 로그인 제공자입니다: ${oauth2.authorizedClientRegistrationId}"
        }

        val member = memberService.loginWithGithub(GithubUser.from(oauth2.principal.attributes))
        val refreshToken = tokenService.issueRefreshToken(requireNotNull(member.id))

        // state 보관에 쓴 세션은 더 이상 필요 없다.
        request.getSession(false)?.invalidate()
        response.addHeader(HttpHeaders.SET_COOKIE, cookieUtil.createRefreshTokenCookie(refreshToken).toString())
        // Access Token은 URL에 담지 않는다. Frontend가 이 화면에서 /api/v1/auth/refresh로 받아 간다.
        response.sendRedirect(CALLBACK_PATH)
    }

    companion object {
        private const val GITHUB = "github"
        private const val CALLBACK_PATH = "/auth/callback"
    }
}
