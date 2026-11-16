package com.apimonitor.monitor.repository

import com.apimonitor.monitor.domain.Monitor
import org.springframework.data.jpa.repository.JpaRepository

/** 모든 조회는 memberId를 함께 받아 소유자를 검증한다. */
interface MonitorRepository : JpaRepository<Monitor, Long> {
    fun findByIdAndMemberId(id: Long, memberId: Long): Monitor?

    fun findAllByMemberIdOrderByIdAsc(memberId: Long): List<Monitor>

    fun countByMemberId(memberId: Long): Long
}
