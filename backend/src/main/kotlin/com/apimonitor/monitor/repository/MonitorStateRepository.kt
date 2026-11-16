package com.apimonitor.monitor.repository

import com.apimonitor.monitor.domain.MonitorState
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface MonitorStateRepository : JpaRepository<MonitorState, Long> {

    /** 진행 중인 장애가 있는지. 장애 엔티티는 장애 판정 단계(12단계)에서 만들므로 지금은 네이티브 쿼리로 조회한다. */
    @Query(
        value = "SELECT COUNT(*) FROM incidents WHERE monitor_id = :monitorId AND resolved_at IS NULL",
        nativeQuery = true,
    )
    fun countOpenIncidents(monitorId: Long): Long
}
