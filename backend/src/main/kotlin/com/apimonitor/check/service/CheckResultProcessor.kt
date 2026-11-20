package com.apimonitor.check.service

import com.apimonitor.check.dto.CheckResult
import com.apimonitor.check.dto.CheckResultType
import com.apimonitor.check.repository.CheckResultRepository
import com.apimonitor.incident.service.IncidentService
import com.apimonitor.monitor.domain.CheckOutcome
import com.apimonitor.monitor.domain.Judgement
import com.apimonitor.monitor.repository.MonitorRepository
import com.apimonitor.monitor.repository.MonitorStateRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * 검사 결과 하나를 한 트랜잭션으로 반영한다. (docs/02-architecture.md 4.1)
 *
 * 1. 모니터 상태 행을 잠근다. 같은 모니터의 결과가 동시에 와도 하나씩 처리된다.
 * 2. 원본 기록을 저장한다. 이미 있으면 처리한 결과이므로 끝낸다.
 * 3. 상태를 판정하고, 장애가 시작되거나 복구되면 장애 기록과 알림 예약을 저장한다.
 *
 * 같은 결과가 두 번 와도 결과가 같다. 저장 재시도나 나중에 Kafka의 중복 전달에 대비한 것이다.
 */
@Service
class CheckResultProcessor(
    private val checkResultRepository: CheckResultRepository,
    private val monitorStateRepository: MonitorStateRepository,
    private val monitorRepository: MonitorRepository,
    private val incidentService: IncidentService,
    private val clock: Clock,
) {

    @Transactional
    fun process(result: CheckResult) {
        // 검사 중에 모니터가 삭제되었으면 기록하지 않는다.
        val state = monitorStateRepository.findByIdForUpdate(result.monitorId) ?: return
        val monitor = monitorRepository.findByIdOrNull(result.monitorId) ?: return
        if (!checkResultRepository.insertIfAbsent(result)) return

        val openIncident = incidentService.findOpen(result.monitorId)
        val now = clock.instant()
        val judgement = state.recordCheck(
            outcome = outcomeOf(result.type),
            result = result.type.name,
            statusCode = result.statusCode,
            responseTimeMs = result.responseTimeMs,
            checkedAt = result.checkedAt,
            hasOpenIncident = openIncident != null,
            interval = Duration.ofMinutes(monitor.intervalMinutes.toLong()),
            now = now,
        )

        when (judgement) {
            is Judgement.IncidentStarted -> incidentService.start(
                monitorId = result.monitorId,
                startedAt = judgement.startedAt,
                detectedAt = judgement.detectedAt,
                cause = result.type.name,
                lastStatusCode = result.statusCode,
                now = now,
            )
            is Judgement.Recovered -> openIncident?.let { incidentService.resolve(it, judgement.resolvedAt, now) }
            Judgement.None, Judgement.Stale -> Unit
        }
    }

    private fun outcomeOf(type: CheckResultType): CheckOutcome = when (type) {
        CheckResultType.SUCCESS -> CheckOutcome.SUCCESS
        CheckResultType.BLOCKED -> CheckOutcome.BLOCKED
        else -> CheckOutcome.FAILURE
    }
}
