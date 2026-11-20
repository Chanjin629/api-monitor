package com.apimonitor.monitor.repository

import com.apimonitor.monitor.domain.MonitorState
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface MonitorStateRepository : JpaRepository<MonitorState, Long> {

    /** 상태 행에 쓰기 잠금을 건다 (SELECT ... FOR UPDATE). 같은 모니터의 검사 결과를 하나씩 처리하기 위해 쓴다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MonitorState s WHERE s.monitorId = :monitorId")
    fun findByIdForUpdate(monitorId: Long): MonitorState?

    /** 진행 중인 장애가 있는지. 장애 엔티티는 장애 판정 단계(12단계)에서 만들므로 지금은 네이티브 쿼리로 조회한다. */
    @Query(
        value = "SELECT COUNT(*) FROM incidents WHERE monitor_id = :monitorId AND resolved_at IS NULL",
        nativeQuery = true,
    )
    fun countOpenIncidents(monitorId: Long): Long
}
