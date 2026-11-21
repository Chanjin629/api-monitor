package com.apimonitor.notification.repository

import com.apimonitor.notification.domain.NotificationChannel
import org.springframework.data.jpa.repository.JpaRepository

/** 모든 조회는 memberId를 함께 받아 소유자를 검증한다. */
interface NotificationChannelRepository : JpaRepository<NotificationChannel, Long> {
    fun findByIdAndMemberId(id: Long, memberId: Long): NotificationChannel?

    fun findAllByMemberIdOrderByIdAsc(memberId: Long): List<NotificationChannel>

    fun countByMemberId(memberId: Long): Long
}
