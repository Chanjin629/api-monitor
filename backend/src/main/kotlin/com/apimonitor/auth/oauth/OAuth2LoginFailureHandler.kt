package com.apimonitor.auth.oauth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.stereotype.Component

/**
 * GitHub 로그인 실패 처리. 사용자가 권한 승인을 거절했거나 state 검증에 실패한 경우 등.
 */
@Component
class OAuth2LoginFailureHandler : AuthenticationFailureHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        log.info("GitHub login failed: {}", exception.message)
        request.getSession(false)?.invalidate()
        response.sendRedirect("/?loginError=true")
    }
}
