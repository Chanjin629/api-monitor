package com.apimonitor.global.security

import com.apimonitor.MutableClock
import com.apimonitor.member.domain.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class JwtTokenProviderTest {

    private val clock = MutableClock().apply { fixAt(Instant.parse("2026-11-15T00:00:00Z")) }
    private val properties = authProperties("SvgvwpW6j+qJC0CMWj8GY4tsmcgSImJ9q1A5RYilO90=")
    private val provider = JwtTokenProvider(properties, clock)

    @Test
    fun `발급한 토큰에서 회원 ID와 역할을 읽는다`() {
        val token = provider.createAccessToken(42L, Role.USER)

        assertThat(provider.parse(token)).isEqualTo(TokenParseResult.Valid(AuthMember(42L, Role.USER)))
    }

    @Test
    fun `만료된 토큰은 Expired로 구분한다`() {
        val token = provider.createAccessToken(42L, Role.USER)

        clock.advance(Duration.ofMinutes(16))

        assertThat(provider.parse(token)).isEqualTo(TokenParseResult.Expired)
    }

    @Test
    fun `다른 키로 서명한 토큰은 Invalid다`() {
        val otherProvider = JwtTokenProvider(authProperties("xe5QWxyvyFVnRBeiXDawT6FyLGB7OLNwB91w/KCCidY="), clock)
        val token = otherProvider.createAccessToken(42L, Role.USER)

        assertThat(provider.parse(token)).isEqualTo(TokenParseResult.Invalid)
    }

    @Test
    fun `내용을 바꾼 토큰과 형식이 틀린 문자열은 Invalid다`() {
        val token = provider.createAccessToken(42L, Role.USER)
        val tampered = token.dropLast(2) + if (token.endsWith("AA")) "BB" else "AA"

        assertThat(provider.parse(tampered)).isEqualTo(TokenParseResult.Invalid)
        assertThat(provider.parse("not-a-jwt")).isEqualTo(TokenParseResult.Invalid)
    }

    private fun authProperties(secret: String) = AuthProperties(
        jwt = AuthProperties.Jwt(
            secret = secret,
            issuer = "api-monitor",
            accessTokenTtl = Duration.ofMinutes(15),
            demoTokenTtl = Duration.ofMinutes(60),
        ),
        refreshTokenTtl = Duration.ofDays(14),
        cookie = AuthProperties.Cookie(secure = false),
    )
}
