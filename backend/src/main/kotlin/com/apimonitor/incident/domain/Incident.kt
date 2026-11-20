package com.apimonitor.incident.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

/**
 * 장애 하나를 한 행으로 저장한다. resolvedAt이 null이면 진행 중이다.
 *
 * DB의 open_marker 생성 컬럼은 진행 중일 때만 1이 되어, (monitor_id, open_marker) 유니크 키로
 * 진행 중인 장애를 모니터당 하나로 제한한다. DB가 계산하는 값이라 엔티티에는 매핑하지 않는다.
 */
@Entity
@Table(name = "incidents")
class Incident(
    val monitorId: Long,
    /** 첫 번째 실패 시각 */
    val startedAt: Instant,
    /** 장애로 판정한 시각 (3번째 실패) */
    val detectedAt: Instant,
    /** 판정 시점의 실패 종류 (TIMEOUT 등) */
    val cause: String,
    @Column(columnDefinition = "SMALLINT")
    val lastStatusCode: Int?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    var resolvedAt: Instant? = null
        protected set

    var durationSeconds: Int? = null
        protected set

    fun resolve(resolvedAt: Instant) {
        // 시각이 어긋나도 음수 지속 시간은 저장하지 않는다.
        val end = if (resolvedAt.isBefore(startedAt)) startedAt else resolvedAt
        this.resolvedAt = end
        this.durationSeconds = Duration.between(startedAt, end).seconds.toInt()
    }
}
