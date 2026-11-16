package com.apimonitor.monitor.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Persistable
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
}
