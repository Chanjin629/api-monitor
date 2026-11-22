package com.apimonitor.check.scheduler

import com.apimonitor.check.repository.HourlyStatsRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 시간별 집계 작업. (docs/03-erd.md 3장)
 *
 * - 매시 5분(UTC)과 서버 시작 직후에 직전 1시간과 지금 진행 중인 1시간을 다시 계산한다.
 *   늦게 저장된 기록도 다음 실행에서 반영되고, 진행 중인 시간도 그래프에 거의 바로 나타난다.
 * - 하루 한 번 보관 기간(365일)이 지난 집계를 지운다.
 */
@Component
class HourlyStatsAggregator(
    private val hourlyStatsRepository: HourlyStatsRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 파티션 준비(PartitionManager) 뒤, 데모 데이터 생성 전에 실행한다.
    @EventListener(ApplicationReadyEvent::class)
    @Order(10)
    fun onStartup() = runSafely { aggregate(clock.instant()) }

    @Scheduled(cron = "0 5 * * * *", zone = "UTC")
    fun hourly() = runSafely { aggregate(clock.instant()) }

    @Scheduled(cron = "0 30 0 * * *", zone = "UTC")
    fun daily() = runSafely { deleteExpired(clock.instant()) }

    /** [now]가 속한 시간과 그 직전 시간을 다시 계산한다. */
    fun aggregate(now: Instant): Int {
        val currentHour = now.truncatedTo(ChronoUnit.HOURS)
        return hourlyStatsRepository.aggregate(from = currentHour.minus(Duration.ofHours(1)), to = currentHour.plus(Duration.ofHours(1)))
    }

    fun deleteExpired(now: Instant): Int = hourlyStatsRepository.deleteBefore(now.minus(RETENTION))

    private fun runSafely(action: () -> Int) {
        try {
            action()
        } catch (e: Exception) {
            // 다음 실행에서 같은 구간을 다시 계산하므로 한 번 실패해도 값이 복구된다.
            log.error("Hourly stats job failed", e)
        }
    }

    companion object {
        /** 시간별 집계 보관 기간 (CLAUDE.md 도메인 규칙) */
        private val RETENTION: Duration = Duration.ofDays(365)
    }
}
