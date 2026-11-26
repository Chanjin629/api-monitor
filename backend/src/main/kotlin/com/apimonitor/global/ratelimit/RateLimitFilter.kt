package com.apimonitor.global.ratelimit

import com.apimonitor.global.error.ErrorCode
import com.apimonitor.global.security.ErrorResponseWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 요청에 맞는 규칙으로 횟수를 세고, 한도를 넘으면 429 RATE_LIMITED와 Retry-After로 응답한다.
 *
 * 일반 서블릿 필터로 자동 등록되지 않도록 @Component를 붙이지 않고 SecurityConfig에서 직접 만든다.
 * 회원 ID를 알아야 하므로 API 체인에서는 JWT 필터 뒤에 둔다.
 */
class RateLimitFilter(
    private val rateLimiter: RedisRateLimiter,
    private val errorResponseWriter: ErrorResponseWriter,
    private val enabled: Boolean,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val target = if (enabled) RateLimitTargets.of(request) else null
        val decision = target?.let { rateLimiter.tryAcquire(it) }
        if (decision is RateLimitDecision.Rejected) {
            response.setHeader(HttpHeaders.RETRY_AFTER, decision.retryAfterSeconds.toString())
            errorResponseWriter.write(response, ErrorCode.RATE_LIMITED)
            return
        }
        filterChain.doFilter(request, response)
    }
}
