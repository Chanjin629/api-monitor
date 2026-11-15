package com.apimonitor.auth.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Refresh Token. 원문은 저장하지 않고 SHA-256 해시만 저장한다.
 * 재발급할 때마다 새 토큰으로 교체하며, 같은 로그인에서 이어진 토큰은 familyId가 같다.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
    val memberId: Long,
    val tokenHash: String,
    val familyId: String,
    val expiresAt: Instant,
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /** 재발급에 사용된 시각. 값이 있으면 이미 교체된 토큰이다. */
    var usedAt: Instant? = null
        protected set

    /** 로그아웃 또는 재사용 감지로 무효화된 시각 */
    var revokedAt: Instant? = null
        protected set

    fun isUsable(now: Instant): Boolean = revokedAt == null && usedAt == null && expiresAt.isAfter(now)

    fun markUsed(now: Instant) {
        usedAt = now
    }
}
