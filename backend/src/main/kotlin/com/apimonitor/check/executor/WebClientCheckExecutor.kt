package com.apimonitor.check.executor

import com.apimonitor.check.dto.CheckCommand
import com.apimonitor.check.dto.CheckResult
import com.apimonitor.check.dto.CheckResultType
import com.apimonitor.check.scheduler.CheckProperties
import com.apimonitor.check.service.CheckResultProcessor
import com.apimonitor.global.error.BusinessException
import com.apimonitor.global.ssrf.BlockedAddressException
import com.apimonitor.global.ssrf.SafeAddressResolver
import com.apimonitor.global.ssrf.UrlSafetyValidator
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.netty.channel.ConnectTimeoutException
import io.netty.handler.timeout.ReadTimeoutException
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 같은 프로세스에서 코루틴으로 검사를 실행한다.
 *
 * - 네트워크 대기는 코루틴으로 처리해 적은 스레드로 많은 검사를 동시에 기다린다.
 * - 세마포어로 동시 검사 수를 제한한다 (N-P2, 기본 200개).
 * - DB 저장은 코루틴 밖의 제한된 스레드에서 한다. JPA·JDBC 트랜잭션은 스레드에 묶여 동작하기 때문이다.
 */
@Component
class WebClientCheckExecutor(
    private val safeAddressResolver: SafeAddressResolver,
    private val urlSafetyValidator: UrlSafetyValidator,
    private val resultProcessor: CheckResultProcessor,
    properties: CheckProperties,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) : CheckExecutor {

    private val log = LoggerFactory.getLogger(javaClass)

    private val semaphore = Semaphore(properties.maxConcurrency)

    // 우리 서버의 처리 현황만 기록한다. 모니터 ID나 URL은 태그에 넣지 않는다 (고객 검사 이력은 DB에만 둔다).
    private val checkDuration: Timer = Timer.builder("apimonitor.check.duration")
        .description("검사 한 번에 걸린 시간 (DNS 조회부터 응답 헤더 수신까지)")
        .register(meterRegistry)
    private val saveFailures: Counter = Counter.builder("apimonitor.check.result_save_failures")
        .description("3번 시도해도 DB에 저장하지 못해 버린 검사 결과 수")
        .register(meterRegistry)

    init {
        CheckResultType.entries.forEach { executions(it) }
        Gauge.builder("apimonitor.check.in_flight") { properties.maxConcurrency - semaphore.availablePermits }
            .description("지금 실행 중인 검사 수")
            .register(meterRegistry)
    }

    private fun executions(type: CheckResultType): Counter = Counter.builder("apimonitor.check.executions")
        .description("결과 종류별 검사 수")
        .tag("result", type.name)
        .register(meterRegistry)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("check") +
            CoroutineExceptionHandler { _, e -> log.error("Check coroutine failed", e) },
    )
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(DB_PARALLELISM)

    private val webClient: WebClient = WebClient.builder()
        .clientConnector(
            ReactorClientHttpConnector(
                // 연결을 재사용하지 않는다. 매번 새로 연결해야 DNS 조회와 IP 검증을 매번 하고, 응답 시간도 일정하게 잰다.
                HttpClient.create(ConnectionProvider.newConnection())
                    .resolver(safeAddressResolver.resolverGroup)
                    .followRedirect(false),
            ),
        )
        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
        .build()

    override fun availableSlots(): Int = semaphore.availablePermits

    override fun execute(command: CheckCommand) {
        scope.launch {
            val result = semaphore.withPermit { check(command) }
            withContext(dbDispatcher) { saveWithRetry(result) }
        }
    }

    /** 검사 한 번을 실행하고 결과를 돌려준다. 예외를 던지지 않고 모든 실패를 결과 종류로 바꾼다. */
    suspend fun check(command: CheckCommand): CheckResult {
        val startedAt = System.nanoTime()
        val result = runCheck(command)
        checkDuration.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
        executions(result.type).increment()
        return result
    }

    private suspend fun runCheck(command: CheckCommand): CheckResult {
        val checkedAt = clock.instant()
        fun result(type: CheckResultType, statusCode: Int? = null, responseTimeMs: Int? = null) =
            CheckResult(command.monitorId, checkedAt, type, statusCode, responseTimeMs)

        // 등록 이후 규칙이 바뀌었거나 DB 값이 바뀐 경우를 대비해 검사할 때도 형식을 다시 확인한다.
        val host = try {
            urlSafetyValidator.validateSyntax(command.url)
        } catch (e: BusinessException) {
            return result(CheckResultType.BLOCKED)
        }
        if (safeAddressResolver.isBlockedLiteral(host)) return result(CheckResultType.BLOCKED)

        val startedAt = System.nanoTime()
        return try {
            // DNS 조회, 연결, 응답 헤더 수신까지 모두 합쳐 제한 시간을 적용한다.
            val (statusCode, responseTimeMs) = withTimeout(command.timeoutSeconds * 1000L) { request(command, startedAt) }
            val type = if (command.isExpected(statusCode)) CheckResultType.SUCCESS else CheckResultType.UNEXPECTED_STATUS
            result(type, statusCode, responseTimeMs)
        } catch (e: TimeoutCancellationException) {
            result(CheckResultType.TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val type = classify(e)
            // URL과 헤더는 남기지 않는다.
            log.debug("Check failed monitorId={} type={} error={}", command.monitorId, type, e.javaClass.simpleName)
            result(type)
        }
    }

    /**
     * 상태 코드와 응답 시간(ms)을 돌려준다. 응답 본문은 읽지 않는다.
     *
     * exchangeToMono는 처리 함수가 끝난 뒤 남은 본문을 끝까지 읽어 버린다. 본문이 크거나 느리면 그만큼 기다리게 된다.
     * 그래서 exchangeToFlux로 상태 코드 하나만 내보내고, awaitFirst가 첫 값을 받자마자 구독을 취소한다.
     * 취소되면 WebClient는 본문을 읽지 않고 연결을 닫는다. 연결을 재사용하지 않으므로 닫아도 손해가 없다.
     */
    private suspend fun request(command: CheckCommand, startedAt: Long): Pair<Int, Int> =
        webClient.method(HttpMethod.valueOf(command.method))
            .uri(URI(command.url))
            .headers { headers -> command.headers.forEach { headers.set(it.name, it.value) } }
            .exchangeToFlux { response ->
                // 이 함수는 응답 헤더를 받은 시점에 호출된다.
                val responseTimeMs = ((System.nanoTime() - startedAt) / 1_000_000).toInt()
                Flux.just(response.statusCode().value() to responseTimeMs)
            }
            .awaitFirst()

    /** 예외를 원인까지 따라가며 결과 종류를 정한다. */
    private fun classify(error: Throwable): CheckResultType {
        generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).forEach {
            when (it) {
                is BlockedAddressException -> return CheckResultType.BLOCKED
                is UnknownHostException -> return CheckResultType.DNS_ERROR
                is SSLException -> return CheckResultType.TLS_ERROR
                is ConnectTimeoutException, is ReadTimeoutException, is SocketTimeoutException ->
                    return CheckResultType.TIMEOUT
            }
        }
        return CheckResultType.CONNECTION_ERROR
    }

    /** DB 일시 장애에 대비해 짧게 3번 시도하고, 그래도 실패하면 이번 결과는 버린다. 다음 주기에 다시 검사한다. */
    private suspend fun saveWithRetry(result: CheckResult) {
        repeat(SAVE_ATTEMPTS) { attempt ->
            try {
                resultProcessor.process(result)
                return
            } catch (e: Exception) {
                if (attempt == SAVE_ATTEMPTS - 1) {
                    saveFailures.increment()
                    log.error("Failed to save check result monitorId={}", result.monitorId, e)
                } else {
                    delay(SAVE_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        // 실행 중이던 검사는 버린다. 다음 검사 시각은 이미 옮겨 두었으므로 재시작 후 정상적으로 이어진다.
        scope.cancel()
    }

    companion object {
        private const val USER_AGENT = "api-monitor/1.0"
        private const val DB_PARALLELISM = 10
        private const val SAVE_ATTEMPTS = 3
        private const val SAVE_RETRY_DELAY_MS = 200L
        private const val MAX_CAUSE_DEPTH = 10
    }
}
