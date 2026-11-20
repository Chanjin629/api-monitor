package com.apimonitor.incident.repository

import com.apimonitor.incident.domain.Incident
import org.springframework.data.jpa.repository.JpaRepository

interface IncidentRepository : JpaRepository<Incident, Long> {

    /** 진행 중인 장애. 유니크 키 때문에 모니터당 최대 하나다. */
    fun findByMonitorIdAndResolvedAtIsNull(monitorId: Long): Incident?
}
