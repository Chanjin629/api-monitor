package com.apimonitor.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 알림 채널. 회원 단위로 관리해 그 회원의 모든 모니터 알림을 받는다.
 * Webhook URL은 알면 누구나 그 채널에 글을 쓸 수 있는 비밀 값이라 암호화해 저장한다.
 */
@Entity
@Table(name = "notification_channels")
class NotificationChannel(
    val memberId: Long,
    @Enumerated(EnumType.STRING)
    val type: ChannelType,
    name: String,
    webhookUrlEncrypted: String,
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    var name: String = name
        protected set

    @Column(columnDefinition = "TEXT")
    var webhookUrlEncrypted: String = webhookUrlEncrypted
        protected set

    var enabled: Boolean = true
        protected set

    var updatedAt: Instant = createdAt
        protected set

    fun rename(name: String) {
        this.name = name
    }

    fun changeWebhookUrl(webhookUrlEncrypted: String) {
        this.webhookUrlEncrypted = webhookUrlEncrypted
    }

    fun changeEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun touch(now: Instant) {
        this.updatedAt = now
    }
}

enum class ChannelType {
    DISCORD,
}
