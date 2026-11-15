package com.apimonitor.global.security

import com.apimonitor.global.error.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

/**
 * 인증되지 않은 요청에 401로 응답한다.
 * 토큰이 만료되었으면 AUTH_TOKEN_EXPIRED를 내려 Frontend가 재발급을 시도하게 한다.
 */
@Component
class JwtAuthenticationHandler(
    private val errorResponseWriter: ErrorResponseWriter,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val errorCode = request.getAttribute(JwtTokenFilter.AUTH_ERROR_ATTRIBUTE) as? ErrorCode
            ?: ErrorCode.AUTH_REQUIRED
        errorResponseWriter.write(response, errorCode)
    }
}
