package com.apimonitor

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 테스트용 시계. fixed를 지정하면 그 시각에 멈추고, reset()하면 실제 시각으로 돌아간다.
 */
class MutableClock : Clock() {

    @Volatile
    private var fixed: Instant? = null

    fun fixAt(instant: Instant) {
        fixed = instant
    }

    fun advance(duration: Duration) {
        fixed = instant().plus(duration)
    }

    fun reset() {
        fixed = null
    }

    override fun instant(): Instant = fixed ?: Instant.now()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
