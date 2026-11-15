package com.apimonitor.member.dto

import com.apimonitor.member.domain.Member
import com.apimonitor.member.domain.Role

data class MemberResponse(
    val id: Long,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?,
    val role: Role,
    val monitorLimit: Int,
) {
    companion object {
        /** 사용자당 모니터 최대 개수 (CLAUDE.md 도메인 규칙) */
        const val MONITOR_LIMIT = 20

        fun from(member: Member) = MemberResponse(
            id = requireNotNull(member.id),
            username = member.username,
            displayName = member.displayName,
            avatarUrl = member.avatarUrl,
            role = member.role,
            monitorLimit = MONITOR_LIMIT,
        )
    }
}
