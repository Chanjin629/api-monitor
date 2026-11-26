package com.apimonitor.global.ratelimit

import com.apimonitor.global.security.AuthMember
import com.apimonitor.member.domain.Role
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpMethod
import org.springframework.security.core.context.SecurityContextHolder

/**
 * 요청 제한 규칙. 한도는 1분 동안의 요청 수다. (docs/02-architecture.md 6.1)
 */
enum class RateLimitRule(
    val id: String,
    val limitPerMinute: Long,
) {
    DEMO_LOGIN("demo-login", 10),
    OAUTH_LOGIN("oauth-login", 10),
    TOKEN_REFRESH("token-refresh", 30),
    NOTIFICATION_TEST("notification-test", 5),
    API("api", 300),
}

/** 이 요청에 적용할 규칙과, 횟수를 셀 대상(IP 또는 회원). */
data class RateLimitTarget(
    val rule: RateLimitRule,
    val subject: String,
)

/**
 * 요청에 맞는 규칙을 고른다. 표의 위에서부터 먼저 맞는 규칙 하나만 쓴다.
 * 제한하지 않는 요청(Swagger, Actuator, GitHub 콜백 등)은 null이다.
 */
object RateLimitTargets {

    private val NOTIFICATION_TEST_PATH = Regex("^/api/v1/notification-channels/[^/]+/test$")

    fun of(request: HttpServletRequest): RateLimitTarget? {
        val path = request.requestURI.removePrefix(request.contextPath)
        val method = request.method
        return when {
            method == HttpMethod.POST.name() && path == "/api/v1/auth/demo" ->
                RateLimitTarget(RateLimitRule.DEMO_LOGIN, byIp(request))
            method == HttpMethod.GET.name() && path.startsWith("/oauth2/authorization/") ->
                RateLimitTarget(RateLimitRule.OAUTH_LOGIN, byIp(request))
            method == HttpMethod.POST.name() && path == "/api/v1/auth/refresh" ->
                RateLimitTarget(RateLimitRule.TOKEN_REFRESH, byIp(request))
            method == HttpMethod.POST.name() && NOTIFICATION_TEST_PATH.matches(path) ->
                RateLimitTarget(RateLimitRule.NOTIFICATION_TEST, byMember(request))
            path.startsWith("/api/") ->
                RateLimitTarget(RateLimitRule.API, byMember(request))
            else -> null
        }
    }

    /**
     * 클라이언트가 보낸 X-Forwarded-For는 위조할 수 있으므로 쓰지 않는다.
     * 운영에서 Caddy가 넘겨 주는 실제 IP는 20단계에서 서버 설정으로 remoteAddr에 반영한다.
     */
    private fun byIp(request: HttpServletRequest): String = "ip:${request.remoteAddr}"

    /** 일반 회원은 회원 ID로 센다. 데모 계정은 방문자 모두가 같은 회원이므로 IP로 센다. */
    private fun byMember(request: HttpServletRequest): String {
        val member = SecurityContextHolder.getContext().authentication?.principal as? AuthMember
        return if (member != null && member.role != Role.DEMO) "member:${member.id}" else byIp(request)
    }
}
