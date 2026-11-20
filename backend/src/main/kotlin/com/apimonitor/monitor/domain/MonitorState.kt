package com.apimonitor.monitor.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Persistable
import java.time.Duration
import java.time.Instant

/**
 * 모니터의 실행 상태. 검사할 때마다 바뀌므로 설정([Monitor])과 다른 테이블에 둔다.
 * 기본 키는 monitors.id를 그대로 쓴다.
 */
@Entity
@Table(name = "monitor_states")
class MonitorState(
    @Id
    val monitorId: Long,
    status: MonitorStatus,
    nextCheckAt: Instant?,
) : Persistable<Long> {

    @Enumerated(EnumType.STRING)
    var status: MonitorStatus = status
        protected set

    var consecutiveFailures: Int = 0
        protected set

    var consecutiveSuccesses: Int = 0
        protected set

    var firstFailureAt: Instant? = null
        protected set

    var firstSuccessAt: Instant? = null
        protected set

    var lastCheckedAt: Instant? = null
        protected set

    /** 마지막 검사 결과 (SUCCESS, TIMEOUT 등). 검사 결과 종류는 검사 실행 단계(11단계)에서 정한다. */
    var lastResult: String? = null
        protected set

    @Column(columnDefinition = "SMALLINT")
    var lastStatusCode: Int? = null
        protected set

    var lastResponseTimeMs: Int? = null
        protected set

    /** 다음 검사 시각. null이면 디스패처가 검사하지 않는다 (일시 정지). */
    var nextCheckAt: Instant? = nextCheckAt
        protected set

    fun pause() {
        status = MonitorStatus.PAUSED
        nextCheckAt = null
    }

    /**
     * 처음부터 다시 판정한다. 재개하거나 URL을 바꿨을 때 쓴다.
     * 진행 중인 장애가 있으면 복구 판정을 받아야 하므로 DOWN으로 둔다.
     */
    fun restart(hasOpenIncident: Boolean, nextCheckAt: Instant) {
        status = if (hasOpenIncident) MonitorStatus.DOWN else MonitorStatus.UNKNOWN
        resetCounters()
        this.nextCheckAt = nextCheckAt
    }

    /**
     * 검사 결과 하나를 반영하고 장애 판정 결과를 돌려준다. (docs/02-architecture.md 4.2, CLAUDE.md 도메인 규칙)
     *
     * - 실패 3회 연속이면 장애 시작, 장애 중 성공 2회 연속이면 복구.
     * - 판정 중(실패 1~2회, 장애 중 성공 1회)이면 30초 뒤 재검사.
     * - 장애 중에는 설정 주기와 5분 중 짧은 쪽으로 검사.
     * - BLOCKED는 고객 장애가 아니므로 카운터를 초기화하고, 진행 중인 장애는 그대로 둔다.
     *
     * @param hasOpenIncident 진행 중인 장애가 있는지. 장애 여부는 incidents 테이블이 기준이다.
     * @param now 결과를 처리하는 시각. 다음 검사 시각은 이 시각부터 계산한다.
     */
    fun recordCheck(
        outcome: CheckOutcome,
        result: String,
        statusCode: Int?,
        responseTimeMs: Int?,
        checkedAt: Instant,
        hasOpenIncident: Boolean,
        interval: Duration,
        now: Instant,
    ): Judgement {
        // 이미 반영한 결과와 같거나 더 오래된 결과(재시도, 늦게 도착한 결과)는 상태를 바꾸지 않는다.
        val lastChecked = lastCheckedAt
        if (lastChecked != null && !checkedAt.isAfter(lastChecked)) return Judgement.Stale

        lastCheckedAt = checkedAt
        lastResult = result
        lastStatusCode = statusCode
        lastResponseTimeMs = responseTimeMs

        // 검사 도중 일시 정지했다면 기록만 남기고 판정하지 않는다. 다음 검사 시각은 비워 둔 채 유지한다.
        if (status == MonitorStatus.PAUSED) return Judgement.None

        return when (outcome) {
            CheckOutcome.BLOCKED -> {
                status = MonitorStatus.BLOCKED
                resetCounters()
                nextCheckAt = now.plus(interval)
                Judgement.None
            }
            CheckOutcome.SUCCESS -> recordSuccess(checkedAt, hasOpenIncident, interval, now)
            CheckOutcome.FAILURE -> recordFailure(checkedAt, hasOpenIncident, interval, now)
        }
    }

    private fun recordSuccess(checkedAt: Instant, hasOpenIncident: Boolean, interval: Duration, now: Instant): Judgement {
        consecutiveFailures = 0
        firstFailureAt = null

        if (!hasOpenIncident) {
            status = MonitorStatus.UP
            resetCounters()
            nextCheckAt = now.plus(interval)
            return Judgement.None
        }

        status = MonitorStatus.DOWN
        consecutiveSuccesses += 1
        if (consecutiveSuccesses == 1) firstSuccessAt = checkedAt
        if (consecutiveSuccesses < SUCCESSES_TO_RECOVER) {
            nextCheckAt = now.plus(RECHECK_DELAY)
            return Judgement.None
        }

        // 복구 시각은 연속 성공의 첫 시각이다. 장애 시작 시각(첫 실패)과 같은 기준이라 지속 시간이 정확하다.
        val resolvedAt = firstSuccessAt ?: checkedAt
        status = MonitorStatus.UP
        resetCounters()
        nextCheckAt = now.plus(interval)
        return Judgement.Recovered(resolvedAt)
    }

    private fun recordFailure(checkedAt: Instant, hasOpenIncident: Boolean, interval: Duration, now: Instant): Judgement {
        consecutiveSuccesses = 0
        firstSuccessAt = null
        consecutiveFailures += 1
        if (consecutiveFailures == 1) firstFailureAt = checkedAt

        if (hasOpenIncident) {
            status = MonitorStatus.DOWN
            nextCheckAt = now.plus(downInterval(interval))
            return Judgement.None
        }

        if (consecutiveFailures < FAILURES_TO_START) {
            // 차단됐던 주소가 다시 검증을 통과했으므로 처음부터 판정한다.
            if (status == MonitorStatus.BLOCKED) status = MonitorStatus.UNKNOWN
            nextCheckAt = now.plus(RECHECK_DELAY)
            return Judgement.None
        }

        status = MonitorStatus.DOWN
        nextCheckAt = now.plus(downInterval(interval))
        // 장애 시작 시각은 첫 실패 시각이다. 3번째 실패 시각을 쓰면 판정에 걸린 시간만큼 짧게 기록된다.
        return Judgement.IncidentStarted(startedAt = firstFailureAt ?: checkedAt, detectedAt = checkedAt)
    }

    private fun downInterval(interval: Duration): Duration = minOf(interval, MAX_DOWN_INTERVAL)

    fun resetCounters() {
        consecutiveFailures = 0
        consecutiveSuccesses = 0
        firstFailureAt = null
        firstSuccessAt = null
    }

    // 기본 키를 직접 지정하므로, 새 엔티티인지 Spring Data에 알려 불필요한 SELECT 없이 INSERT하게 한다.
    @jakarta.persistence.Transient
    private var isNewEntity: Boolean = true

    override fun getId(): Long = monitorId

    override fun isNew(): Boolean = isNewEntity

    @jakarta.persistence.PostLoad
    @jakarta.persistence.PostPersist
    private fun markNotNew() {
        isNewEntity = false
    }

    companion object {
        const val FAILURES_TO_START = 3
        const val SUCCESSES_TO_RECOVER = 2
        val RECHECK_DELAY: Duration = Duration.ofSeconds(30)
        val MAX_DOWN_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}

/** 판정에 쓰는 결과 분류. 실패 종류(TIMEOUT 등)는 판정에 영향이 없어 하나로 묶는다. */
enum class CheckOutcome {
    SUCCESS,
    FAILURE,

    /** SSRF 차단. 연속 실패에 넣지 않는다 */
    BLOCKED,
}

/** 검사 결과를 반영한 뒤의 판정 */
sealed interface Judgement {
    /** 장애 시작이나 복구가 아니다 */
    data object None : Judgement

    /** 이미 반영한 결과라 아무것도 바꾸지 않았다 */
    data object Stale : Judgement

    data class IncidentStarted(val startedAt: Instant, val detectedAt: Instant) : Judgement

    data class Recovered(val resolvedAt: Instant) : Judgement
}
