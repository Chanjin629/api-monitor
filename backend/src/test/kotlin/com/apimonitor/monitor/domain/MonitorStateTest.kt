package com.apimonitor.monitor.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** 장애 판정 규칙을 DB 없이 확인한다. (docs/02-architecture.md 4.2의 표) */
class MonitorStateTest {

    private val t0: Instant = Instant.parse("2026-11-20T00:00:00Z")
    private val fiveMinutes: Duration = Duration.ofMinutes(5)
    private var clock = t0

    @Test
    fun `첫 성공이면 UP이 되고 설정 주기 뒤에 검사한다`() {
        val state = newState()

        val judgement = state.record(CheckOutcome.SUCCESS)

        assertThat(judgement).isEqualTo(Judgement.None)
        assertThat(state.status).isEqualTo(MonitorStatus.UP)
        assertThat(state.nextCheckAt).isEqualTo(clock.plus(fiveMinutes))
        assertThat(state.lastResult).isEqualTo("SUCCESS")
    }

    @Test
    fun `실패 1~2회는 판정 중이라 30초 뒤 재검사하고 상태는 그대로다`() {
        val state = newState().apply { record(CheckOutcome.SUCCESS) }

        assertThat(state.record(CheckOutcome.FAILURE)).isEqualTo(Judgement.None)
        assertThat(state.status).isEqualTo(MonitorStatus.UP)
        assertThat(state.consecutiveFailures).isEqualTo(1)
        assertThat(state.nextCheckAt).isEqualTo(clock.plusSeconds(30))

        assertThat(state.record(CheckOutcome.FAILURE)).isEqualTo(Judgement.None)
        assertThat(state.consecutiveFailures).isEqualTo(2)
        assertThat(state.nextCheckAt).isEqualTo(clock.plusSeconds(30))
    }

    @Test
    fun `연속 3회 실패하면 첫 실패 시각으로 장애가 시작된다`() {
        val state = newState()
        state.record(CheckOutcome.FAILURE)
        val firstFailure = clock
        state.record(CheckOutcome.FAILURE)

        val judgement = state.record(CheckOutcome.FAILURE)

        assertThat(judgement).isEqualTo(Judgement.IncidentStarted(startedAt = firstFailure, detectedAt = clock))
        assertThat(state.status).isEqualTo(MonitorStatus.DOWN)
        assertThat(state.nextCheckAt).isEqualTo(clock.plus(fiveMinutes))
    }

    @Test
    fun `중간에 성공하면 연속 실패가 끊긴다`() {
        val state = newState()
        state.record(CheckOutcome.FAILURE)
        state.record(CheckOutcome.FAILURE)
        state.record(CheckOutcome.SUCCESS)

        assertThat(state.record(CheckOutcome.FAILURE)).isEqualTo(Judgement.None)
        assertThat(state.consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `장애 중 검사 주기는 설정 주기와 5분 중 짧은 쪽이다`() {
        val hourly = newState()
        hourly.record(CheckOutcome.FAILURE, openIncident = true, interval = Duration.ofMinutes(60))
        assertThat(hourly.nextCheckAt).isEqualTo(clock.plus(Duration.ofMinutes(5)))

        val everyMinute = newState()
        everyMinute.record(CheckOutcome.FAILURE, openIncident = true, interval = Duration.ofMinutes(1))
        assertThat(everyMinute.nextCheckAt).isEqualTo(clock.plus(Duration.ofMinutes(1)))
    }

    @Test
    fun `장애 중 성공 1회는 30초 뒤 재검사하고, 2회 연속이면 첫 성공 시각으로 복구한다`() {
        val state = newState()

        assertThat(state.record(CheckOutcome.SUCCESS, openIncident = true)).isEqualTo(Judgement.None)
        val firstSuccess = clock
        assertThat(state.status).isEqualTo(MonitorStatus.DOWN)
        assertThat(state.nextCheckAt).isEqualTo(clock.plusSeconds(30))

        val judgement = state.record(CheckOutcome.SUCCESS, openIncident = true)

        assertThat(judgement).isEqualTo(Judgement.Recovered(resolvedAt = firstSuccess))
        assertThat(state.status).isEqualTo(MonitorStatus.UP)
        assertThat(state.consecutiveSuccesses).isZero()
        assertThat(state.nextCheckAt).isEqualTo(clock.plus(fiveMinutes))
    }

    @Test
    fun `장애 중 성공 뒤 다시 실패하면 연속 성공이 끊긴다`() {
        val state = newState()
        state.record(CheckOutcome.SUCCESS, openIncident = true)
        state.record(CheckOutcome.FAILURE, openIncident = true)

        assertThat(state.record(CheckOutcome.SUCCESS, openIncident = true)).isEqualTo(Judgement.None)
        assertThat(state.consecutiveSuccesses).isEqualTo(1)
    }

    @Test
    fun `BLOCKED는 연속 실패에 넣지 않고 카운터를 초기화한다`() {
        val state = newState()
        state.record(CheckOutcome.FAILURE)
        state.record(CheckOutcome.FAILURE)

        assertThat(state.record(CheckOutcome.BLOCKED)).isEqualTo(Judgement.None)
        assertThat(state.status).isEqualTo(MonitorStatus.BLOCKED)
        assertThat(state.consecutiveFailures).isZero()
        assertThat(state.nextCheckAt).isEqualTo(clock.plus(fiveMinutes))

        // 다시 실패해도 1회째부터 센다.
        assertThat(state.record(CheckOutcome.FAILURE)).isEqualTo(Judgement.None)
        assertThat(state.status).isEqualTo(MonitorStatus.UNKNOWN)
        assertThat(state.consecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `진행 중인 장애가 있으면 BLOCKED에서 검증을 통과해도 DOWN으로 돌아간다`() {
        val state = newState()
        state.record(CheckOutcome.BLOCKED, openIncident = true)

        state.record(CheckOutcome.FAILURE, openIncident = true)

        assertThat(state.status).isEqualTo(MonitorStatus.DOWN)
    }

    @Test
    fun `일시 정지 중에 도착한 결과는 기록만 하고 판정하지 않는다`() {
        val state = newState()
        state.pause()

        assertThat(state.record(CheckOutcome.FAILURE)).isEqualTo(Judgement.None)
        assertThat(state.status).isEqualTo(MonitorStatus.PAUSED)
        assertThat(state.consecutiveFailures).isZero()
        assertThat(state.nextCheckAt).isNull()
        assertThat(state.lastResult).isEqualTo("FAILURE")
    }

    @Test
    fun `이미 반영한 시각과 같거나 오래된 결과는 무시한다`() {
        val state = newState()
        state.record(CheckOutcome.FAILURE)
        val processedAt = clock

        val duplicate = state.recordCheck(
            CheckOutcome.FAILURE, "TIMEOUT", null, null, processedAt, false, fiveMinutes, clock,
        )
        val older = state.recordCheck(
            CheckOutcome.FAILURE, "TIMEOUT", null, null, processedAt.minusSeconds(1), false, fiveMinutes, clock,
        )

        assertThat(duplicate).isEqualTo(Judgement.Stale)
        assertThat(older).isEqualTo(Judgement.Stale)
        assertThat(state.consecutiveFailures).isEqualTo(1)
    }

    private fun newState() = MonitorState(monitorId = 1, status = MonitorStatus.UNKNOWN, nextCheckAt = t0)

    /** 검사할 때마다 1분씩 시간이 지난다고 가정한다. 검사 시각과 처리 시각은 같다고 본다. */
    private fun MonitorState.record(
        outcome: CheckOutcome,
        openIncident: Boolean = false,
        interval: Duration = fiveMinutes,
    ): Judgement {
        clock = clock.plusSeconds(60)
        return recordCheck(outcome, outcome.name, null, null, clock, openIncident, interval, clock)
    }
}
