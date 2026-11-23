package com.apimonitor.check.executor

import com.apimonitor.check.dto.CheckCommand
import com.apimonitor.check.dto.CheckResultType
import com.apimonitor.check.scheduler.CheckProperties
import com.apimonitor.check.service.CheckResultProcessor
import com.apimonitor.global.ssrf.HostResolver
import com.apimonitor.global.ssrf.SafeAddressResolver
import com.apimonitor.global.ssrf.UrlSafetyValidator
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.head
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.http.Fault
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * 실제 HTTP 서버(WireMock)를 상대로 검사 결과 분류를 확인한다.
 * WireMock은 127.0.0.1에서 뜨므로, 차단 규칙을 확인하는 테스트 외에는 차단 판정을 꺼 둔 리졸버를 쓴다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebClientCheckExecutorTest {

    private val server = WireMockServer(options().dynamicPort().dynamicHttpsPort()).apply { start() }

    /** api.test는 WireMock(127.0.0.1)으로 연결된다. */
    private val fakeDns = HostResolver { host ->
        when (host) {
            "api.test" -> listOf(InetAddress.getByName("127.0.0.1"))
            else -> throw UnknownHostException(host)
        }
    }
    private val executor = executorWith(SafeAddressResolver(fakeDns) { false })

    @BeforeEach
    fun reset() = server.resetAll()

    @AfterAll
    fun stop() = server.stop()

    @Test
    fun `기대한 상태 코드면 SUCCESS이고 응답 시간과 요청 헤더를 전달한다`() {
        server.stubFor(get("/health").willReturn(aResponse().withStatus(200).withBody("ok")))

        val result = check(command("/health", headers = listOf(CheckCommand.Header("X-Api-Key", "secret"))))

        assertThat(result.type).isEqualTo(CheckResultType.SUCCESS)
        assertThat(result.statusCode).isEqualTo(200)
        assertThat(result.responseTimeMs).isNotNull().isGreaterThanOrEqualTo(0)
        server.verify(
            getRequestedFor(urlEqualTo("/health"))
                .withHeader("X-Api-Key", equalTo("secret"))
                .withHeader("User-Agent", equalTo("api-monitor/1.0"))
                .withHeader("Host", equalTo("api.test:${server.port()}")),
        )
    }

    @Test
    fun `5xx는 UNEXPECTED_STATUS다`() {
        server.stubFor(get("/health").willReturn(aResponse().withStatus(503)))

        val result = check(command("/health"))

        assertThat(result.type).isEqualTo(CheckResultType.UNEXPECTED_STATUS)
        assertThat(result.statusCode).isEqualTo(503)
    }

    @Test
    fun `기대 상태 코드를 지정하면 그 코드만 성공이다`() {
        server.stubFor(get("/created").willReturn(aResponse().withStatus(201)))
        server.stubFor(get("/ok").willReturn(aResponse().withStatus(200)))

        assertThat(check(command("/created", expected = listOf(201))).type).isEqualTo(CheckResultType.SUCCESS)
        assertThat(check(command("/ok", expected = listOf(201))).type).isEqualTo(CheckResultType.UNEXPECTED_STATUS)
    }

    @Test
    fun `Redirect는 따라가지 않고 3xx 그대로 판정한다`() {
        server.stubFor(get("/old").willReturn(aResponse().withStatus(301).withHeader("Location", "/new")))
        server.stubFor(get("/new").willReturn(aResponse().withStatus(200)))

        val result = check(command("/old"))

        assertThat(result.type).isEqualTo(CheckResultType.UNEXPECTED_STATUS)
        assertThat(result.statusCode).isEqualTo(301)
        server.verify(0, getRequestedFor(urlEqualTo("/new")))
    }

    @Test
    fun `제한 시간 안에 응답 헤더가 오지 않으면 TIMEOUT이다`() {
        server.stubFor(get("/slow").willReturn(aResponse().withStatus(200).withFixedDelay(3000)))

        val elapsed = measureTimeMillis {
            assertThat(check(command("/slow", timeoutSeconds = 1)).type).isEqualTo(CheckResultType.TIMEOUT)
        }
        assertThat(elapsed).isLessThan(2500)
    }

    @Test
    fun `응답 본문은 읽지 않으므로 본문이 느리게 와도 헤더를 받으면 끝난다`() {
        // 헤더는 바로 보내고, 본문은 4초에 걸쳐 조금씩 보낸다.
        server.stubFor(
            get("/big").willReturn(
                aResponse().withStatus(200).withBody("x".repeat(100_000)).withChunkedDribbleDelay(20, 4000),
            ),
        )

        val result = check(command("/big", timeoutSeconds = 2))

        assertThat(result.type).isEqualTo(CheckResultType.SUCCESS)
        assertThat(result.responseTimeMs).isLessThan(1000)
    }

    @Test
    fun `HEAD 요청도 보낼 수 있다`() {
        server.stubFor(head(urlEqualTo("/health")).willReturn(aResponse().withStatus(204)))

        assertThat(check(command("/health", method = "HEAD")).type).isEqualTo(CheckResultType.SUCCESS)
    }

    @Test
    fun `연결이 거부되거나 끊기면 CONNECTION_ERROR다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        server.stubFor(any(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

        assertThat(check(command("/", url = "http://api.test:$closedPort/")).type).isEqualTo(CheckResultType.CONNECTION_ERROR)
        assertThat(check(command("/reset")).type).isEqualTo(CheckResultType.CONNECTION_ERROR)
    }

    @Test
    fun `도메인을 찾을 수 없으면 DNS_ERROR다`() {
        assertThat(check(command("/", url = "http://unknown.test:${server.port()}/")).type)
            .isEqualTo(CheckResultType.DNS_ERROR)
    }

    @Test
    fun `신뢰할 수 없는 인증서면 TLS_ERROR다`() {
        // WireMock의 HTTPS는 자체 서명 인증서를 쓴다.
        server.stubFor(get("/health").willReturn(aResponse().withStatus(200)))

        assertThat(check(command("/", url = "https://api.test:${server.httpsPort()}/health")).type)
            .isEqualTo(CheckResultType.TLS_ERROR)
    }

    @Test
    fun `연결 시점에 내부 IP로 바뀐 도메인은 요청을 보내지 않고 BLOCKED다`() {
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)))
        // 등록할 때는 공인 IP였다가 검사할 때 127.0.0.1(WireMock)로 바뀌는 DNS 리바인딩
        val calls = AtomicInteger()
        val rebindingDns = HostResolver {
            if (calls.getAndIncrement() == 0) listOf(InetAddress.getByName("93.184.216.34")) else listOf(InetAddress.getByName("127.0.0.1"))
        }
        UrlSafetyValidator(rebindingDns).validate("http://rebind.test:${server.port()}/") // 등록 시점 검증 통과
        val realRules = executorWith(SafeAddressResolver(rebindingDns))

        val result = runBlocking { realRules.check(command("/", url = "http://rebind.test:${server.port()}/")) }

        assertThat(result.type).isEqualTo(CheckResultType.BLOCKED)
        assertThat(server.allServeEvents).isEmpty()
    }

    @Test
    fun `URL에 내부 IP를 직접 쓰거나 형식이 맞지 않으면 BLOCKED다`() {
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)))
        val realRules = executorWith(SafeAddressResolver(fakeDns))

        runBlocking {
            assertThat(realRules.check(command("/", url = "http://127.0.0.1:${server.port()}/")).type)
                .isEqualTo(CheckResultType.BLOCKED)
            assertThat(realRules.check(command("/", url = "http://[::1]:${server.port()}/")).type)
                .isEqualTo(CheckResultType.BLOCKED)
            assertThat(realRules.check(command("/", url = "ftp://api.test/")).type).isEqualTo(CheckResultType.BLOCKED)
        }
        assertThat(server.allServeEvents).isEmpty()
    }

    private fun check(command: CheckCommand) = runBlocking { executor.check(command) }

    private fun command(
        path: String,
        url: String = "http://api.test:${server.port()}$path",
        method: String = "GET",
        timeoutSeconds: Int = 5,
        expected: List<Int> = emptyList(),
        headers: List<CheckCommand.Header> = emptyList(),
    ) = CheckCommand(
        monitorId = 1,
        url = url,
        method = method,
        timeoutSeconds = timeoutSeconds,
        expectedStatusCodes = expected,
        headers = headers,
    )

    private fun executorWith(resolver: SafeAddressResolver, registry: MeterRegistry = SimpleMeterRegistry()) = WebClientCheckExecutor(
        safeAddressResolver = resolver,
        urlSafetyValidator = UrlSafetyValidator(fakeDns),
        resultProcessor = mockk<CheckResultProcessor>(relaxed = true),
        properties = CheckProperties(),
        clock = Clock.systemUTC(),
        meterRegistry = registry,
    )

    @Test
    fun `검사할 때마다 결과 종류별 횟수와 걸린 시간을 메트릭으로 남긴다`() {
        server.stubFor(get("/health").willReturn(aResponse().withStatus(200)))
        server.stubFor(get("/down").willReturn(aResponse().withStatus(500)))
        val registry = SimpleMeterRegistry()
        val measured = executorWith(SafeAddressResolver(fakeDns) { false }, registry)

        runBlocking {
            measured.check(command("/health"))
            measured.check(command("/health"))
            measured.check(command("/down"))
        }

        assertThat(registry.get("apimonitor.check.executions").tag("result", "SUCCESS").counter().count()).isEqualTo(2.0)
        assertThat(registry.get("apimonitor.check.executions").tag("result", "UNEXPECTED_STATUS").counter().count()).isEqualTo(1.0)
        assertThat(registry.get("apimonitor.check.executions").tag("result", "TIMEOUT").counter().count()).isZero()
        assertThat(registry.get("apimonitor.check.duration").timer().count()).isEqualTo(3)
        assertThat(registry.get("apimonitor.check.in_flight").gauge().value()).isZero()
    }
}
