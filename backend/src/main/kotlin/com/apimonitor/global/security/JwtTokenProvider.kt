package com.apimonitor.global.security

import com.apimonitor.member.domain.Role
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.Date
import javax.crypto.SecretKey

/**
 * Access Token(JWT)을 발급하고 검증한다.
 * subject는 회원 ID, role 클레임은 USER 또는 DEMO다.
 */
@Component
class JwtTokenProvider(
    private val properties: AuthProperties,
    private val clock: Clock,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.jwt.secret))

    private val parser = Jwts.parser()
        .verifyWith(key)
        .requireIssuer(properties.jwt.issuer)
        .clock { Date.from(clock.instant()) }
        .build()

    fun createAccessToken(
        memberId: Long,
        role: Role,
        ttl: Duration = properties.jwt.accessTokenTtl,
    ): String {
        val now = clock.instant()
        return Jwts.builder()
            .subject(memberId.toString())
            .claim(ROLE_CLAIM, role.name)
            .issuer(properties.jwt.issuer)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    fun parse(token: String): TokenParseResult =
        try {
            val claims = parser.parseSignedClaims(token).payload
            val role = Role.valueOf(claims.get(ROLE_CLAIM, String::class.java))
            TokenParseResult.Valid(AuthMember(id = claims.subject.toLong(), role = role))
        } catch (e: ExpiredJwtException) {
            TokenParseResult.Expired
        } catch (e: JwtException) {
            TokenParseResult.Invalid
        } catch (e: IllegalArgumentException) {
            // subject나 role 값이 형식에 맞지 않는 경우
            TokenParseResult.Invalid
        }

    companion object {
        private const val ROLE_CLAIM = "role"
    }
}

sealed interface TokenParseResult {
    data class Valid(val member: AuthMember) : TokenParseResult

    data object Expired : TokenParseResult

    data object Invalid : TokenParseResult
}
