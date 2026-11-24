package com.apimonitor.global.error

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 요청이 들어오면 traceId를 만들어 MDC(로그마다 자동으로 붙는 값 저장소)에 넣는다.
 * 보안 필터보다 먼저 실행되어 401·403 응답과 그 로그에도 같은 traceId가 붙는다.
 * 클라이언트가 보낸 값은 쓰지 않는다. 로그를 위조된 ID로 섞을 수 있기 때문이다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        MDC.put(TraceIds.MDC_KEY, TraceIds.newId())
        try {
            chain.doFilter(request, response)
        } finally {
            // 스레드는 다음 요청에 재사용되므로 반드시 지운다.
            MDC.remove(TraceIds.MDC_KEY)
        }
    }
}
