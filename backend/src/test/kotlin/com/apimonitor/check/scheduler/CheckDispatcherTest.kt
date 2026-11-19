package com.apimonitor.check.scheduler

import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.check.dto.CheckCommand
import com.apimonitor.check.dto.CheckResult
import com.apimonitor.check.dto.CheckResultType
import com.apimonitor.check.executor.CheckExecutor
import com.apimonitor.check.repository.CheckTargetRepository
import com.apimonitor.check.service.CheckResultProcessor
import com.apimonitor.global.crypto.AesGcmEncryptor
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.service.MemberService
import com.apimonitor.monitor.dto.CreateMonitorRequest
import com.apimonitor.monitor.dto.HeaderRequest
import com.apimonitor.monitor.service.MonitorService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 디스패처가 검사 대상을 올바르게, 중복 없이 선점하는지 실제 MariaDB로 확인한다.
 * 실제 외부 호출을 하지 않도록 실행기 대신 넘겨받은 명령을 기록하는 가짜를 쓴다.
 * 다른 테스트의 모니터도 같은 DB에 있으므로, 이 테스트에서 만든 모니터만 골라 확인한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class CheckDispatcherTest(
    @Autowired private val checkTargetRepository: CheckTargetRepository,
    @Autowired private val checkResultProcessor: CheckResultProcessor,
    @Autowired private val transactionTemplate: TransactionTemplate,
    @Autowired private val encryptor: AesGcmEncryptor,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val monitorService: MonitorService,
    @Autowired private val memberService: MemberService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @Test
    fun `검사 시각이 지난 모니터만 선점하고 다음 검사 시각을 설정 주기 뒤로 옮긴다`() {
        val memberId = newMember()
        val due = createMonitor(memberId, intervalMinutes = 5, headers = listOf(HeaderRequest("X-Api-Key", "secret")))
        val future = createMonitor(memberId)
        val paused = createMonitor(memberId)
        setNextCheckAt(due, now.minusSeconds(10))
        setNextCheckAt(future, now.plusSeconds(60))
        setNextCheckAt(paused, now.minusSeconds(10))
        monitorService.pause(memberId, paused)
        val executor = RecordingExecutor()

        dispatcherWith(executor).dispatch()

        val command = executor.commands.single { it.monitorId == due }
        assertThat(command.url).isEqualTo("https://public.example/health")
        assertThat(command.timeoutSeconds).isEqualTo(10)
        assertThat(command.expectedStatusCodes).containsExactly(200, 204)
        assertThat(command.headers).containsExactly(CheckCommand.Header("X-Api-Key", "secret"))
        assertThat(executor.commands.map { it.monitorId }).doesNotContain(future, paused)
        assertThat(nextCheckAt(due)).isEqualTo(now.plus(Duration.ofMinutes(5)))
        assertThat(nextCheckAt(paused)).isNull()
    }

    @Test
    fun `실행기에 빈자리가 없으면 선점하지 않는다`() {
        val due = createMonitor(newMember())
        setNextCheckAt(due, now.minusSeconds(10))

        val dispatched = dispatcherWith(RecordingExecutor(slots = 0)).dispatch()

        assertThat(dispatched).isZero()
        assertThat(nextCheckAt(due)).isEqualTo(now.minusSeconds(10))
    }

    @Test
    fun `다른 트랜잭션이 잠근 모니터는 기다리지 않고 건너뛴다`() {
        val due = createMonitor(newMember())
        setNextCheckAt(due, now.minusSeconds(10))
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val otherServer = Executors.newSingleThreadExecutor()

        // 다른 서버가 같은 모니터를 먼저 선점하고 트랜잭션을 열어 둔 상황
        val other = otherServer.submit {
            transactionTemplate.executeWithoutResult {
                checkTargetRepository.findDueForUpdate(now, 1000)
                locked.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        locked.await(10, TimeUnit.SECONDS)

        val executor = RecordingExecutor()
        val startedAt = System.nanoTime()
        dispatcherWith(executor).dispatch()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        release.countDown()
        other.get(10, TimeUnit.SECONDS)
        otherServer.shutdown()

        assertThat(executor.commands.map { it.monitorId }).doesNotContain(due)
        // 잠금을 기다렸다면 상대 트랜잭션이 끝날 때까지 멈췄을 것이다.
        assertThat(elapsedMs).isLessThan(3000)
    }

    @Test
    fun `검사 결과를 받으면 마지막 검사 정보를 갱신한다`() {
        val memberId = newMember()
        val monitorId = createMonitor(memberId)

        checkResultProcessor.process(CheckResult(monitorId, now, CheckResultType.SUCCESS, 200, 124))

        val state = monitorService.getMonitor(memberId, monitorId).state
        assertThat(state.lastCheckedAt).isEqualTo(now)
        assertThat(state.lastResult).isEqualTo("SUCCESS")
        assertThat(state.lastStatusCode).isEqualTo(200)
        assertThat(state.lastResponseTimeMs).isEqualTo(124)

        checkResultProcessor.process(CheckResult(monitorId, now.plusSeconds(60), CheckResultType.TIMEOUT))
        val failed = monitorService.getMonitor(memberId, monitorId).state
        assertThat(failed.lastResult).isEqualTo("TIMEOUT")
        assertThat(failed.lastStatusCode).isNull()
    }

    private fun dispatcherWith(executor: CheckExecutor) = CheckDispatcher(
        checkTargetRepository = checkTargetRepository,
        checkExecutor = executor,
        transactionTemplate = transactionTemplate,
        encryptor = encryptor,
        jsonMapper = jsonMapper,
        properties = CheckProperties(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun newMember(): Long {
        val id = System.nanoTime().toString()
        return requireNotNull(memberService.loginWithGithub(GithubUser(id, "user$id", null, null)).id)
    }

    private fun createMonitor(
        memberId: Long,
        intervalMinutes: Int = 1,
        headers: List<HeaderRequest>? = null,
    ): Long = monitorService.create(
        memberId,
        CreateMonitorRequest(
            name = "검사 대상",
            url = "https://public.example/health",
            intervalMinutes = intervalMinutes,
            expectedStatusCodes = listOf(204, 200),
            headers = headers,
        ),
    ).id

    private fun setNextCheckAt(monitorId: Long, at: Instant) {
        jdbcTemplate.update(
            "UPDATE monitor_states SET next_check_at = ? WHERE monitor_id = ?",
            LocalDateTime.ofInstant(at, ZoneOffset.UTC),
            monitorId,
        )
    }

    private fun nextCheckAt(monitorId: Long): Instant? =
        jdbcTemplate.queryForObject(
            "SELECT next_check_at FROM monitor_states WHERE monitor_id = ?",
            { rs, _ -> rs.getObject(1, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC) },
            monitorId,
        )

    /** 넘겨받은 검사 명령을 기록만 한다. */
    private class RecordingExecutor(private val slots: Int = 200) : CheckExecutor {
        val commands = mutableListOf<CheckCommand>()

        override fun availableSlots(): Int = slots

        override fun execute(command: CheckCommand) {
            commands += command
        }
    }
}
