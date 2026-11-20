package com.apimonitor.check.scheduler

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * check_results의 날짜별 파티션을 관리한다. (docs/03-erd.md 3장, N-D1)
 *
 * - 오늘부터 3일치 파티션을 미리 만든다. 작업이 며칠 실패해도 저장이 멈추지 않는다.
 * - 14일이 지난 파티션은 통째로 지운다. 수백만 행을 DELETE하는 것보다 훨씬 빠르고 DB 부담이 적다.
 * - 항상 남아 있는 p_future(MAXVALUE) 파티션이 있어, 파티션이 없는 날짜의 기록도 저장된다.
 *
 * 매번 현재 파티션 목록을 읽고 필요한 것만 바꾸므로 여러 번 실행해도 결과가 같다. 날짜는 DB 시각과 같은 UTC 기준이다.
 */
@Component
class PartitionManager(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 서버가 꺼져 있던 사이 놓친 작업을 시작 직후에 처리한다. */
    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() = runSafely()

    @Scheduled(cron = "0 10 0 * * *", zone = "UTC")
    fun daily() = runSafely()

    private fun runSafely() {
        try {
            maintain(LocalDate.now(clock.withZone(ZoneOffset.UTC)))
        } catch (e: Exception) {
            // 미리 만든 3일치와 p_future가 있어 저장은 계속된다. 다음 실행에서 다시 시도한다.
            log.error("check_results partition maintenance failed", e)
        }
    }

    fun maintain(today: LocalDate) {
        createUpcoming(today)
        dropExpired(today)
    }

    /** 오늘부터 [DAYS_AHEAD]일 뒤까지 날짜별 파티션이 있도록 p_future를 나눈다. */
    private fun createUpcoming(today: LocalDate) {
        val lastBound = datedPartitions().maxOfOrNull { it.upperBound } ?: today
        val until = today.plusDays(DAYS_AHEAD + 1) // 마지막 날짜 파티션의 상한(그 날짜는 포함하지 않음)
        if (!lastBound.isBefore(until)) return

        val newPartitions = generateSequence(lastBound) { it.plusDays(1) }
            .takeWhile { it.isBefore(until) }
            .joinToString(",\n") { day -> "PARTITION ${partitionName(day)} VALUES LESS THAN (TO_DAYS('${day.plusDays(1)}'))" }
        jdbcTemplate.execute(
            """
            ALTER TABLE check_results REORGANIZE PARTITION $FUTURE_PARTITION INTO (
            $newPartitions,
            PARTITION $FUTURE_PARTITION VALUES LESS THAN MAXVALUE
            )
            """.trimIndent(),
        )
        log.info("Created check_results partitions until {}", until.minusDays(1))
    }

    /** 상한이 보관 기준일 이하인 파티션, 즉 기준일 이전 기록만 담은 파티션을 지운다. */
    private fun dropExpired(today: LocalDate) {
        val cutoff = today.minusDays(RETENTION_DAYS)
        val expired = datedPartitions().filter { !it.upperBound.isAfter(cutoff) }.map { it.name }
        if (expired.isEmpty()) return

        jdbcTemplate.execute("ALTER TABLE check_results DROP PARTITION ${expired.joinToString(", ")}")
        log.info("Dropped {} expired check_results partitions before {}", expired.size, cutoff)
    }

    /** MAXVALUE 파티션을 뺀 날짜 파티션 목록. 상한은 DB의 TO_DAYS 값을 날짜로 바꿔 읽는다. */
    private fun datedPartitions(): List<DatedPartition> =
        jdbcTemplate.query(
            """
            SELECT partition_name, FROM_DAYS(partition_description) AS upper_bound
            FROM information_schema.partitions
            WHERE table_schema = DATABASE() AND table_name = 'check_results'
              AND partition_name IS NOT NULL AND partition_description <> 'MAXVALUE'
            ORDER BY partition_ordinal_position
            """.trimIndent(),
        ) { rs, _ -> DatedPartition(rs.getString("partition_name"), rs.getObject("upper_bound", LocalDate::class.java)) }

    private fun partitionName(day: LocalDate) = "p" + day.format(DateTimeFormatter.BASIC_ISO_DATE)

    private data class DatedPartition(val name: String, val upperBound: LocalDate)

    companion object {
        /** 오늘을 포함해 앞으로 며칠치를 더 만들어 둘지 */
        private const val DAYS_AHEAD = 3L

        /** 검사 원본 보관 기간 (CLAUDE.md 도메인 규칙) */
        private const val RETENTION_DAYS = 14L
        private const val FUTURE_PARTITION = "p_future"
    }
}
