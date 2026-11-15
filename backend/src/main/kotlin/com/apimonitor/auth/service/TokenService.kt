package com.apimonitor.auth.service

import com.apimonitor.auth.domain.RefreshToken
import com.apimonitor.auth.dto.AccessTokenResponse
import com.apimonitor.auth.dto.RefreshResult
import com.apimonitor.auth.repository.RefreshTokenRepository
import com.apimonitor.global.security.AuthProperties
import com.apimonitor.global.security.JwtTokenProvider
import com.apimonitor.member.domain.Role
import com.apimonitor.member.repository.MemberRepository
import com.apimonitor.member.service.MemberService
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * 토큰 수명 주기: 발급, 재발급(교체), 재사용 감지, 로그아웃.
 */
@Service
class TokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val memberRepository: MemberRepository,
    private val memberService: MemberService,
    private val jwtTokenProvider: JwtTokenProvider,
    private val properties: AuthProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()

    /** 로그인할 때 새 Refresh Token을 발급한다. 원문은 쿠키로만 전달하고 DB에는 해시만 저장한다. */
    @Transactional
    fun issueRefreshToken(memberId: Long, familyId: String = UUID.randomUUID().toString()): String {
        val now = clock.instant()
        val rawToken = newRawToken()
        refreshTokenRepository.save(
            RefreshToken(
                memberId = memberId,
                tokenHash = hash(rawToken),
                familyId = familyId,
                expiresAt = now.plus(properties.refreshTokenTtl),
                createdAt = now,
            ),
        )
        return rawToken
    }

    /**
     * Refresh Token으로 Access Token을 재발급하고 Refresh Token을 새것으로 교체한다.
     *
     * 이미 교체된 토큰이 다시 오면:
     * - 10초 이내: 여러 탭이 동시에 재발급한 경우로 보고 재발급만 거절한다.
     * - 10초 이후: 탈취된 토큰으로 보고 같은 묶음의 토큰을 모두 무효화한다.
     */
    @Transactional
    fun refresh(rawToken: String): RefreshResult {
        val now = clock.instant()
        val token = refreshTokenRepository.findByTokenHash(hash(rawToken)) ?: return RefreshResult.Rejected

        val usedAt = token.usedAt
        if (usedAt != null && token.revokedAt == null) {
            if (Duration.between(usedAt, now) > REUSE_GRACE_PERIOD) {
                refreshTokenRepository.revokeFamily(token.familyId, now)
                log.warn("Refresh token reuse detected. familyId={} memberId={}", token.familyId, token.memberId)
            }
            return RefreshResult.Rejected
        }
        if (!token.isUsable(now)) return RefreshResult.Rejected

        // 탈퇴하면 토큰도 함께 삭제되지만, 경계 상황을 대비해 회원을 다시 확인한다.
        val member = memberRepository.findByIdOrNull(token.memberId) ?: return RefreshResult.Rejected
        token.markUsed(now)

        val memberId = requireNotNull(member.id)
        return RefreshResult.Issued(
            accessToken = accessTokenResponse(memberId, member.role, properties.jwt.accessTokenTtl),
            refreshToken = issueRefreshToken(memberId, token.familyId),
        )
    }

    /** 현재 기기 로그아웃. 이 로그인에서 이어진 토큰을 모두 무효화한다. */
    @Transactional
    fun revoke(rawToken: String) {
        val token = refreshTokenRepository.findByTokenHash(hash(rawToken)) ?: return
        refreshTokenRepository.revokeFamily(token.familyId, clock.instant())
    }

    /** 모든 기기 로그아웃 */
    @Transactional
    fun revokeAll(memberId: Long) {
        refreshTokenRepository.revokeAllByMemberId(memberId, clock.instant())
    }

    /** 데모 로그인. 읽기 전용 Access Token만 발급하고 Refresh Token은 주지 않는다. */
    fun issueDemoAccessToken(): AccessTokenResponse {
        val demo = memberService.getDemoMember()
        return accessTokenResponse(requireNotNull(demo.id), Role.DEMO, properties.jwt.demoTokenTtl)
    }

    /** 만료된 토큰을 하루 한 번 삭제한다. */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @Transactional
    fun deleteExpiredTokens() {
        val deleted = refreshTokenRepository.deleteExpired(clock.instant())
        log.info("Deleted expired refresh tokens count={}", deleted)
    }

    private fun accessTokenResponse(memberId: Long, role: Role, ttl: Duration) = AccessTokenResponse(
        accessToken = jwtTokenProvider.createAccessToken(memberId, role, ttl),
        expiresIn = ttl.seconds,
    )

    private fun newRawToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(rawToken: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray()))

    companion object {
        private const val TOKEN_BYTES = 32
        private val REUSE_GRACE_PERIOD: Duration = Duration.ofSeconds(10)
    }
}
