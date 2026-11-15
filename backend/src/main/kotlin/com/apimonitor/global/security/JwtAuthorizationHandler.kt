package com.apimonitor.global.security

import com.apimonitor.global.error.ErrorCode
import com.apimonitor.member.domain.Role
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

/**
 * 권한이 없는 요청에 403으로 응답한다. 데모 계정의 변경 요청은 DEMO_READ_ONLY로 구분한다.
 */
@Component
class JwtAuthorizationHandler(
    private val errorResponseWriter: ErrorResponseWriter,
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        val member = SecurityContextHolder.getContext().authentication?.principal as? AuthMember
        val errorCode = if (member?.role == Role.DEMO) ErrorCode.DEMO_READ_ONLY else ErrorCode.ACCESS_DENIED
        errorResponseWriter.write(response, errorCode)
    }
}
