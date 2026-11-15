package com.apimonitor.auth.service

import com.apimonitor.global.security.AuthProperties
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Refresh Token 쿠키 생성과 삭제 (Synco의 CookieUtil 구조).
 * 삭제할 때도 생성할 때와 같은 Path를 써야 브라우저가 지운다.
 */
@Component
class CookieUtil(
    private val properties: AuthProperties,
) {
    fun createRefreshTokenCookie(refreshToken: String): ResponseCookie =
        baseCookie(refreshToken).maxAge(properties.refreshTokenTtl).build()

    fun deleteRefreshTokenCookie(): ResponseCookie =
        baseCookie("").maxAge(Duration.ZERO).build()

    private fun baseCookie(value: String): ResponseCookie.ResponseCookieBuilder =
        ResponseCookie.from(REFRESH_TOKEN_COOKIE, value)
            .httpOnly(true)
            .secure(properties.cookie.secure)
            .sameSite("Lax")
            // 재발급·로그아웃 요청에만 실리고 일반 API 요청에는 실리지 않는다.
            .path(COOKIE_PATH)

    companion object {
        const val REFRESH_TOKEN_COOKIE = "refreshToken"
        const val COOKIE_PATH = "/api/v1/auth"
    }
}
