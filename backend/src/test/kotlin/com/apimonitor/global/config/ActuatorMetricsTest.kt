package com.apimonitor.global.config

import com.apimonitor.TestcontainersConfiguration
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * Actuator가 상태 확인과 Prometheus 메트릭만 내보내는지 확인한다.
 * 운영에서는 관리 포트(8081)에만 열리고, 테스트에서는 같은 포트로 두어 MockMvc로 확인한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorMetricsTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `상태 확인은 로그인 없이 UP을 돌려주고 DB 상태도 포함한다`() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.components.db.status") { value("UP") }
        }
    }

    @Test
    fun `Prometheus 메트릭에 서버 메트릭과 직접 추가한 메트릭이 나온다`() {
        // API 요청 메트릭은 요청이 한 번 있어야 생긴다.
        mockMvc.post("/api/v1/auth/demo")

        mockMvc.get("/actuator/prometheus").andExpect {
            status { isOk() }
            content {
                string(containsString("http_server_requests_seconds_bucket"))
                string(containsString("hikaricp_connections"))
                string(containsString("jvm_memory_used_bytes"))
                string(containsString("apimonitor_check_executions_total{application=\"api-monitor\",result=\"SUCCESS\"}"))
                string(containsString("apimonitor_check_dispatch_delay_seconds_bucket"))
                string(containsString("apimonitor_check_in_flight"))
                string(containsString("apimonitor_notification_deliveries_total"))
                string(containsString("apimonitor_scheduled_job_failures_total{application=\"api-monitor\",task=\"partition_maintenance\"}"))
                // 고객 모니터를 구분하는 태그는 넣지 않는다.
                string(not(containsString("monitor_id")))
            }
        }
    }

    @Test
    fun `설정과 환경 변수를 보여 주는 엔드포인트는 열지 않는다`() {
        // 노출 목록에 없어 엔드포인트가 없고, 보안 설정도 허용하지 않아 4xx로 거부된다.
        listOf("/actuator/env", "/actuator/configprops", "/actuator/beans", "/actuator/heapdump").forEach { path ->
            mockMvc.get(path).andExpect { status { is4xxClientError() } }
        }
    }
}
