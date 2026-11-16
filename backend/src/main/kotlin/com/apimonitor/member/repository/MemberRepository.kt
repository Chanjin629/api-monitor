package com.apimonitor.member.repository

import com.apimonitor.member.domain.AuthProvider
import com.apimonitor.member.domain.Member
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface MemberRepository : JpaRepository<Member, Long> {
    fun findByProviderAndProviderUserId(provider: AuthProvider, providerUserId: String): Member?

    /**
     * 회원 행에 쓰기 잠금을 건다 (SELECT ... FOR UPDATE).
     * 같은 회원의 모니터 등록 요청이 동시에 와도 하나씩 처리되어 개수 제한을 넘지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :id")
    fun findByIdForUpdate(id: Long): Member?
}
