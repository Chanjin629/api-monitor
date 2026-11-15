package com.apimonitor.auth.repository

import com.apimonitor.auth.domain.RefreshToken
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {

    /** 같은 토큰으로 동시에 재발급해도 한 요청만 교체하도록 행을 잠그고 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByTokenHash(tokenHash: String): RefreshToken?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    fun revokeFamily(@Param("familyId") familyId: String, @Param("now") now: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.memberId = :memberId and t.revokedAt is null")
    fun revokeAllByMemberId(@Param("memberId") memberId: Long, @Param("now") now: Instant): Int

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    fun deleteExpired(@Param("now") now: Instant): Int
}
