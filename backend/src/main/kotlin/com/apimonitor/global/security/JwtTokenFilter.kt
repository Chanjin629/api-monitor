package com.apimonitor.global.security

import com.apimonitor.global.error.ErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authorization: Bearer 토큰을 검증해 SecurityContext에 사용자를 넣는다.
 *
 * - 일반 서블릿 필터로 자동 등록되어 두 번 실행되지 않도록 @Component를 붙이지 않고 SecurityConfig에서 직접 만든다.
 * - 토큰 오류의 종류(만료, 위조)를 요청 속성에 남겨 401 응답에서 구분할 수 있게 한다.
 */
class JwtTokenFilter(
    private val jwtTokenProvider: JwtTokenProvider,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header != null) {
            authenticate(request, header)
        }
        filterChain.doFilter(request, response)
    }

    private fun authenticate(request: HttpServletRequest, header: String) {
        if (!header.startsWith(BEARER_PREFIX)) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.AUTH_TOKEN_INVALID)
            return
        }
        when (val result = jwtTokenProvider.parse(header.removePrefix(BEARER_PREFIX))) {
            is TokenParseResult.Valid -> {
                val member = result.member
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken.authenticated(member, null, member.authorities())
            }
            TokenParseResult.Expired -> request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.AUTH_TOKEN_EXPIRED)
            TokenParseResult.Invalid -> request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.AUTH_TOKEN_INVALID)
        }
    }

    companion object {
        const val AUTH_ERROR_ATTRIBUTE = "com.apimonitor.authError"
        private const val BEARER_PREFIX = "Bearer "
    }
}
