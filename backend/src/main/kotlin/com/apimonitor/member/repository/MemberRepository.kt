package com.apimonitor.member.repository

import com.apimonitor.member.domain.AuthProvider
import com.apimonitor.member.domain.Member
import org.springframework.data.jpa.repository.JpaRepository

interface MemberRepository : JpaRepository<Member, Long> {
    fun findByProviderAndProviderUserId(provider: AuthProvider, providerUserId: String): Member?
}
