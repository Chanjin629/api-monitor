package com.apimonitor.incident.service

import com.apimonitor.incident.domain.Incident
import com.apimonitor.incident.repository.IncidentRepository
import com.apimonitor.notification.repository.NotificationDeliveryRepository
import com.apimonitor.notification.repository.NotificationEventType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 장애 시작과 복구를 기록하고 알림을 예약한다.
 * 호출한 쪽(검사 결과 처리)의 트랜잭션 안에서 실행되어, 장애 기록과 알림 예약이 함께 저장되거나 함께 취소된다. (F-N4)
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class IncidentService(
    private val incidentRepository: IncidentRepository,
    private val notificationDeliveryRepository: NotificationDeliveryRepository,
) {

    fun findOpen(monitorId: Long): Incident? = incidentRepository.findByMonitorIdAndResolvedAtIsNull(monitorId)

    fun start(
        monitorId: Long,
        startedAt: Instant,
        detectedAt: Instant,
        cause: String,
        lastStatusCode: Int?,
        now: Instant,
    ): Incident {
        val incident = incidentRepository.save(Incident(monitorId, startedAt, detectedAt, cause, lastStatusCode))
        notificationDeliveryRepository.reserve(incident.id!!, monitorId, NotificationEventType.DOWN, now)
        return incident
    }

    fun resolve(incident: Incident, resolvedAt: Instant, now: Instant) {
        incident.resolve(resolvedAt)
        notificationDeliveryRepository.reserve(incident.id!!, incident.monitorId, NotificationEventType.RECOVERED, now)
    }
}
