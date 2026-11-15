package com.apimonitor.global.security

import com.apimonitor.member.domain.Role
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

/**
 * 인증된 사용자. Controller에서 @AuthenticationPrincipal로 받는다.
 */
data class AuthMember(
    val id: Long,
    val role: Role,
) {
    fun authorities(): List<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_${role.name}"))
}
