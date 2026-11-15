package com.apimonitor.member.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 회원. 비밀번호, GitHub 토큰, 이메일은 저장하지 않는다.
 * 같은 사람인지는 바뀌지 않는 (provider, providerUserId)로 판단한다.
 */
@Entity
@Table(name = "members")
class Member(
    @Enumerated(EnumType.STRING)
    val provider: AuthProvider,
    val providerUserId: String,
    username: String,
    displayName: String?,
    avatarUrl: String?,
    @Enumerated(EnumType.STRING)
    val role: Role,
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    var username: String = username
        protected set

    var displayName: String? = displayName
        protected set

    var avatarUrl: String? = avatarUrl
        protected set

    var lastLoginAt: Instant? = null
        protected set

    var updatedAt: Instant = createdAt
        protected set

    /** GitHub에서 이름이나 프로필 사진을 바꿨을 수 있으므로 로그인할 때마다 갱신한다. */
    fun login(username: String, displayName: String?, avatarUrl: String?, now: Instant) {
        this.username = username
        this.displayName = displayName
        this.avatarUrl = avatarUrl
        this.lastLoginAt = now
        this.updatedAt = now
    }

    companion object {
        fun github(providerUserId: String, username: String, now: Instant): Member = Member(
            provider = AuthProvider.GITHUB,
            providerUserId = providerUserId,
            username = username,
            displayName = null,
            avatarUrl = null,
            role = Role.USER,
            createdAt = now,
        )
    }
}
