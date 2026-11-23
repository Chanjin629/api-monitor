package com.apimonitor.member.service

import com.apimonitor.check.repository.HourlyStatsRepository
import com.apimonitor.member.domain.AuthProvider
import com.apimonitor.member.repository.MemberRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Statement
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * @property seedEnabled false면 데모 데이터를 만들지 않는다. 테스트에서는 꺼 두고 [DemoDataSeeder.seed]를 직접 부른다.
 */
@ConfigurationProperties("demo")
data class DemoProperties(
    val seedEnabled: Boolean = true,
)

/**
 * 데모 계정의 샘플 데이터를 현재 시각 기준으로 만든다. (F-M3)
 *
 * 고정된 SQL로 넣으면 하루만 지나도 "최근 24시간" 그래프가 비므로, 서버 시작 시와 매일 한 번 다시 만든다.
 * 데모 계정의 모니터와 기록만 지우고 새로 만든다. 데모 계정은 읽기 전용이라 사용자가 바꾼 데이터가 사라지는 일은 없다.
 * 주소는 example 도메인이고 다음 검사 시각이 비어 있어 실제로 검사하지 않는다. 알림 채널도 없어 알림이 나가지 않는다.
 */
@Component
class DemoDataSeeder(
    private val jdbcTemplate: JdbcTemplate,
    private val memberRepository: MemberRepository,
    private val hourlyStatsRepository: HourlyStatsRepository,
    private val transactionTemplate: TransactionTemplate,
    private val properties: DemoProperties,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val failures = Counter.builder("apimonitor.scheduled.job.failures")
        .description("주기 작업이 예외로 실패한 횟수")
        .tag("task", "demo_seed")
        .register(meterRegistry)

    private val log = LoggerFactory.getLogger(javaClass)

    // 파티션 준비와 시간별 집계 뒤에 실행한다.
    @EventListener(ApplicationReadyEvent::class)
    @Order(20)
    fun onStartup() = runIfEnabled()

    @Scheduled(cron = "0 40 0 * * *", zone = "UTC")
    fun daily() = runIfEnabled()

    private fun runIfEnabled() {
        if (!properties.seedEnabled) return
        try {
            seed(clock.instant())
        } catch (e: Exception) {
            // 데모 화면이 하루 묵을 뿐 서비스에는 영향이 없다. 다음 실행에서 다시 만든다.
            failures.increment()
            log.error("Demo data seeding failed", e)
        }
    }

    fun seed(now: Instant) {
        val demoMemberId = memberRepository.findByProviderAndProviderUserId(AuthProvider.DEMO, DEMO_PROVIDER_USER_ID)?.id
            ?: error("데모 계정이 없습니다. Flyway V2 마이그레이션을 확인하세요.")
        val nowMinute = now.truncatedTo(ChronoUnit.MINUTES)
        val random = Random(SEED)

        transactionTemplate.executeWithoutResult {
            deleteExisting(demoMemberId)
            val incidents = SCENARIOS.flatMap { scenario -> create(demoMemberId, scenario, nowMinute, random) }
            // 실제 서비스처럼 장애는 시작 순서대로 저장한다. 장애 목록이 ID 순서로 최신순을 정하기 때문이다.
            incidents.sortedBy { (_, incident) -> incident.startedAt }.forEach { (monitorId, incident) -> insertIncident(monitorId, incident) }
            // 최근 24시간은 원본 기록에서 집계한다. 화면의 7일·30일 그래프와 24시간 그래프가 같은 값을 보여 준다.
            hourlyStatsRepository.aggregate(
                from = nowMinute.minus(RAW_WINDOW).truncatedTo(ChronoUnit.HOURS),
                to = nowMinute.plus(Duration.ofHours(1)),
            )
        }
        log.info("Demo data seeded: {} monitors", SCENARIOS.size)
    }

    /** 원본 기록은 FK가 없어 모니터를 지워도 남으므로 먼저 지운다. 나머지는 ON DELETE CASCADE로 함께 지워진다. */
    private fun deleteExisting(memberId: Long) {
        val ids = jdbcTemplate.queryForList("SELECT id FROM monitors WHERE member_id = ?", Long::class.java, memberId).filterNotNull()
        if (ids.isEmpty()) return
        jdbcTemplate.update("DELETE FROM check_results WHERE monitor_id IN (${ids.joinToString(",") { "?" }})", *ids.toTypedArray())
        jdbcTemplate.update("DELETE FROM monitors WHERE member_id = ?", memberId)
    }

    /** 모니터와 기록을 만들고, 나중에 시간순으로 저장할 장애 목록을 돌려준다. */
    private fun create(memberId: Long, scenario: Scenario, now: Instant, random: Random): List<Pair<Long, DemoIncident>> {
        val monitorId = insertMonitor(memberId, scenario, now)
        val activeUntil = scenario.pausedFor?.let { now.minus(it) } ?: now

        // 30일 전부터 24시간 전까지는 시간별 집계만 만든다.
        val hourlyFrom = now.minus(Duration.ofDays(30)).truncatedTo(ChronoUnit.HOURS)
        val hourlyUntil = minOf(now.minus(RAW_WINDOW).truncatedTo(ChronoUnit.HOURS), activeUntil.truncatedTo(ChronoUnit.HOURS))
        insertHourly(monitorId, scenario, hourlyFrom, hourlyUntil, now, random)

        // 최근 24시간은 1분 간격의 원본 기록을 만든다.
        val results = generateSequence(now.minus(RAW_WINDOW)) { it.plus(Duration.ofMinutes(1)) }
            .takeWhile { !it.isAfter(activeUntil) }
            .map { at -> scenario.resultAt(at, now, random) }
            .toList()
        insertResults(monitorId, results)

        insertState(monitorId, scenario, results.lastOrNull() ?: DemoResult(activeUntil, "SUCCESS", 200, scenario.baseMs))
        return scenario.incidents(now).map { monitorId to it }
    }

    private fun insertMonitor(memberId: Long, scenario: Scenario, now: Instant): Long {
        val createdAt = utc(now.minus(Duration.ofDays(30)))
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            connection.prepareStatement(
                """
                INSERT INTO monitors (member_id, name, url, http_method, interval_minutes, timeout_seconds, created_at, updated_at)
                VALUES (?, ?, ?, 'GET', 1, 10, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).apply {
                setLong(1, memberId)
                setString(2, scenario.name)
                setString(3, scenario.url)
                setObject(4, createdAt)
                setObject(5, createdAt)
            }
        }, keyHolder)
        return keyHolder.key!!.toLong()
    }

    private fun insertHourly(monitorId: Long, scenario: Scenario, from: Instant, until: Instant, now: Instant, random: Random) {
        val rows = generateSequence(from) { it.plus(Duration.ofHours(1)) }
            .takeWhile { it.isBefore(until) }
            .map { hour ->
                val success = if (scenario.hadOutageAt(hour, now)) 55 else 60
                val average = scenario.baseMs + random.nextInt(-scenario.jitterMs / 2, scenario.jitterMs / 2 + 1)
                arrayOf<Any>(
                    monitorId, utc(hour), 60, success, 0,
                    average.toLong() * success, success,
                    (average - scenario.jitterMs).coerceAtLeast(5), average + scenario.jitterMs * 2,
                )
            }
            .toList()
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO monitor_hourly_stats (monitor_id, hour_start, total_count, success_count, blocked_count,
                response_time_sum_ms, response_time_count, response_time_min_ms, response_time_max_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            rows,
        )
    }

    private fun insertResults(monitorId: Long, results: List<DemoResult>) {
        jdbcTemplate.batchUpdate(
            "INSERT INTO check_results (monitor_id, checked_at, result, status_code, response_time_ms) VALUES (?, ?, ?, ?, ?)",
            results.map { arrayOf<Any?>(monitorId, utc(it.at), it.result, it.statusCode, it.responseTimeMs) },
        )
    }

    private fun insertIncident(monitorId: Long, incident: DemoIncident) {
        jdbcTemplate.update(
            """
            INSERT INTO incidents (monitor_id, started_at, detected_at, resolved_at, duration_seconds, cause, last_status_code)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            monitorId,
            utc(incident.startedAt),
            utc(incident.startedAt.plus(Duration.ofMinutes(2))),
            incident.resolvedAt?.let(::utc),
            incident.resolvedAt?.let { Duration.between(incident.startedAt, it).seconds.toInt() },
            incident.cause,
            incident.statusCode,
        )
    }

    /** 다음 검사 시각을 비워 두어 디스패처가 실제로 검사하지 않게 한다. */
    private fun insertState(monitorId: Long, scenario: Scenario, last: DemoResult) {
        jdbcTemplate.update(
            """
            INSERT INTO monitor_states (monitor_id, status, consecutive_failures, consecutive_successes, first_failure_at,
                last_checked_at, last_result, last_status_code, last_response_time_ms, next_check_at)
            VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, NULL)
            """.trimIndent(),
            monitorId,
            scenario.status,
            scenario.consecutiveFailures,
            if (scenario.consecutiveFailures > 0) utc(last.at.minus(Duration.ofMinutes(scenario.consecutiveFailures - 1L))) else null,
            utc(last.at),
            last.result,
            last.statusCode,
            last.responseTimeMs,
        )
    }

    private fun utc(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)

    private data class DemoResult(val at: Instant, val result: String, val statusCode: Int?, val responseTimeMs: Int?)

    private data class DemoIncident(val startedAt: Instant, val resolvedAt: Instant?, val cause: String, val statusCode: Int?)

    /**
     * 데모 모니터 하나의 모습.
     * @property failingFor 최근 이 기간 동안 [failure]로 실패 중 (진행 중인 장애)
     * @property outage 지금으로부터 얼마 전에 시작해 얼마 동안 이어진 복구된 장애
     */
    private data class Scenario(
        val name: String,
        val url: String,
        val status: String,
        val baseMs: Int,
        val jitterMs: Int,
        val consecutiveFailures: Int = 0,
        val failure: String = "TIMEOUT",
        val failureStatusCode: Int? = null,
        val failingFor: Duration? = null,
        val outage: Pair<Duration, Duration>? = null,
        val outageCause: Pair<String, Int?> = "UNEXPECTED_STATUS" to 503,
        val olderOutageAgo: Duration? = null,
        val pausedFor: Duration? = null,
    ) {
        fun resultAt(at: Instant, now: Instant, random: Random): DemoResult {
            val failingSince = failingFor?.let { now.minus(it) }
            val outageWindow = outage?.let { (ago, length) -> now.minus(ago).let { start -> start to start.plus(length) } }
            return when {
                failingSince != null && !at.isBefore(failingSince) -> failed(at)
                outageWindow != null && !at.isBefore(outageWindow.first) && at.isBefore(outageWindow.second) ->
                    DemoResult(at, outageCause.first, outageCause.second, if (outageCause.second != null) 80 else null)
                // 마지막 검사 1회만 실패해 "확인 중"으로 보이는 모니터
                consecutiveFailures in 1..2 && !at.isBefore(now.minus(Duration.ofMinutes(consecutiveFailures - 1L))) -> failed(at)
                else -> DemoResult(at, "SUCCESS", 200, (baseMs + random.nextInt(-jitterMs, jitterMs + 1)).coerceAtLeast(5))
            }
        }

        private fun failed(at: Instant) = DemoResult(at, failure, failureStatusCode, null)

        fun incidents(now: Instant): List<DemoIncident> = listOfNotNull(
            failingFor?.let { DemoIncident(now.minus(it), null, failure, failureStatusCode) },
            outage?.let { (ago, length) ->
                DemoIncident(now.minus(ago), now.minus(ago).plus(length), outageCause.first, outageCause.second)
            },
            olderOutageAgo?.let { ago -> DemoIncident(now.minus(ago), now.minus(ago).plus(Duration.ofMinutes(5)), "CONNECTION_ERROR", null) },
        )

        /** 시간별 집계에서 예전 장애(5분)가 있던 시간인지 */
        fun hadOutageAt(hour: Instant, now: Instant): Boolean =
            olderOutageAgo != null && hour == now.minus(olderOutageAgo).truncatedTo(ChronoUnit.HOURS)
    }

    companion object {
        private const val DEMO_PROVIDER_USER_ID = "demo"
        private const val SEED = 20261109
        private val RAW_WINDOW: Duration = Duration.ofHours(24)

        private val SCENARIOS = listOf(
            Scenario(
                name = "결제 API", url = "https://payments.demo.example/health", status = "UP",
                baseMs = 120, jitterMs = 40,
                outage = Duration.ofHours(5) to Duration.ofMinutes(20),
            ),
            Scenario(
                name = "회원 API", url = "https://members.demo.example/health", status = "UP",
                baseMs = 60, jitterMs = 20,
                olderOutageAgo = Duration.ofDays(9),
            ),
            Scenario(
                name = "검색 API", url = "https://search.demo.example/health", status = "DOWN",
                baseMs = 250, jitterMs = 80, consecutiveFailures = 12,
                failingFor = Duration.ofMinutes(12),
            ),
            Scenario(
                name = "알림 수신 API", url = "https://webhooks.demo.example/health", status = "UP",
                baseMs = 180, jitterMs = 60, consecutiveFailures = 1,
            ),
            Scenario(
                name = "레거시 관리자 API", url = "https://legacy-admin.demo.example/health", status = "PAUSED",
                baseMs = 400, jitterMs = 120,
                pausedFor = Duration.ofDays(3),
            ),
        )
    }
}
