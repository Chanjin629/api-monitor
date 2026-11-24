package com.apimonitor.global.error

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.apimonitor.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

/**
 * 요청마다 traceId가 만들어지고, 같은 요청의 로그와 오류 응답이 같은 값을 쓰는지 확인한다.
 * 이 값으로 Grafana(Loki)에서 그 요청의 로그를 찾는다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TraceIdFilterTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val handlerLogger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        appender.start()
        handlerLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        handlerLogger.detachAppender(appender)
    }

    @Test
    fun `요청 처리 중에는 MDC에 traceId가 있고 요청이 끝나면 지워진다`() {
        var seen: String? = null
        val chain = MockFilterChain(object : jakarta.servlet.http.HttpServlet() {
            override fun service(req: jakarta.servlet.ServletRequest, res: jakarta.servlet.ServletResponse) {
                seen = MDC.get(TraceIds.MDC_KEY)
            }
        })

        TraceIdFilter().doFilter(MockHttpServletRequest(), MockHttpServletResponse(), chain)

        assertThat(seen).matches("[0-9a-f]{16}")
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull()
    }

    @Test
    fun `요청마다 다른 traceId를 쓰고 클라이언트가 보낸 값은 쓰지 않는다`() {
        val first = unauthorizedTraceId()
        val second = unauthorizedTraceId()

        assertThat(first).isNotEqualTo(second)
        assertThat(first).isNotEqualTo("forged-trace-id")
    }

    @Test
    fun `오류 응답의 traceId와 같은 요청의 로그 traceId가 같다`() {
        val token = demoToken()
        appender.list.clear()

        val body = mockMvc.get("/api/v1/monitors/999999999") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }.andExpect { status { isNotFound() } }.andReturn().response.contentAsString
        val responseTraceId = jsonMapper.readTree(body).path("error").path("traceId").asString()

        val event = appender.list.single { it.formattedMessage.startsWith("Request failed") }
        assertThat(event.mdcPropertyMap[TraceIds.MDC_KEY]).isEqualTo(responseTraceId)
        assertThat(event.formattedMessage).contains("traceId=$responseTraceId")
    }

    @Test
    fun `보안 필터가 돌려주는 401 응답에도 traceId가 있다`() {
        assertThat(unauthorizedTraceId()).matches("[0-9a-f]{16}")
    }

    private fun unauthorizedTraceId(): String {
        val body = mockMvc.get("/api/v1/monitors") {
            header("X-Trace-Id", "forged-trace-id")
        }.andExpect { status { isUnauthorized() } }.andReturn().response.contentAsString
        return jsonMapper.readTree(body).path("error").path("traceId").asString()
    }

    private fun demoToken(): String {
        val body = mockMvc.post("/api/v1/auth/demo").andReturn().response.contentAsString
        return jsonMapper.readTree(body).path("data").path("accessToken").asString()
    }
}
