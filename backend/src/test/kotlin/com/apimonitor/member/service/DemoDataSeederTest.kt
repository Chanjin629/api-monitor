package com.apimonitor.member.service

import com.apimonitor.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class DemoDataSeederTest(
    @Autowired private val seeder: DemoDataSeeder,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val jsonMapper: JsonMapper,
) {

    @Test
    fun `데모 계정에 모니터 5개와 기록을 만들고, 다시 만들어도 쌓이지 않는다`() {
        seeder.seed(Instant.now())
        val first = counts()

        seeder.seed(Instant.now())

        assertThat(counts()).isEqualTo(first)
        assertThat(first.monitors).isEqualTo(5)
        assertThat(first.checkResults).isGreaterThan(4 * 1400)
        assertThat(first.hourlyStats).isGreaterThan(5 * 24 * 25)
        assertThat(first.openIncidents).isEqualTo(1)
        // 실제로 검사하지 않도록 다음 검사 시각이 비어 있다.
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monitor_states s JOIN monitors m ON m.id = s.monitor_id WHERE m.member_id = ? AND s.next_check_at IS NOT NULL",
                Int::class.java, demoMemberId(),
            ),
        ).isZero()
    }

    @Test
    fun `데모 로그인으로 채워진 대시보드와 그래프를 볼 수 있다`() {
        seeder.seed(Instant.now())
        val body = mockMvc.post("/api/v1/auth/demo").andReturn().response.contentAsString
        val token = jsonMapper.readTree(body).path("data").path("accessToken").asString()

        mockMvc.get("/api/v1/dashboard/summary") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.data.totalMonitors") { value(5) }
            jsonPath("$.data.downCount") { value(1) }
            jsonPath("$.data.pausedCount") { value(1) }
            jsonPath("$.data.upCount") { value(3) }
            jsonPath("$.data.recentIncidents", hasSize<Any>(2))
            jsonPath("$.data.recentIncidents[0].status") { value("OPEN") }
            jsonPath("$.data.recentIncidents[1].lastStatusCode") { value(503) }
        }
        // 장애 목록은 최신순이다. 데모 데이터도 시작 순서대로 저장한다.
        mockMvc.get("/api/v1/incidents") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }.andExpect {
            jsonPath("$.data.items[0].monitor.name") { value("검색 API") }
            jsonPath("$.data.items[1].monitor.name") { value("결제 API") }
            jsonPath("$.data.items[2].monitor.name") { value("회원 API") }
        }

        val monitorId = jdbcTemplate.queryForObject(
            "SELECT id FROM monitors WHERE member_id = ? AND name = '결제 API'", Long::class.java, demoMemberId(),
        )
        listOf("24h", "7d", "30d").forEach { range ->
            mockMvc.get("/api/v1/monitors/$monitorId/stats?range=$range") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.data.points.length()") { value(greaterThan(10)) }
                }
        }
    }

    private data class Counts(val monitors: Int, val checkResults: Int, val hourlyStats: Int, val openIncidents: Int)

    private fun counts(): Counts {
        val memberId = demoMemberId()
        fun count(sql: String) = jdbcTemplate.queryForObject(sql, Int::class.java, memberId)!!
        return Counts(
            monitors = count("SELECT COUNT(*) FROM monitors WHERE member_id = ?"),
            checkResults = count("SELECT COUNT(*) FROM check_results c JOIN monitors m ON m.id = c.monitor_id WHERE m.member_id = ?"),
            hourlyStats = count("SELECT COUNT(*) FROM monitor_hourly_stats h JOIN monitors m ON m.id = h.monitor_id WHERE m.member_id = ?"),
            openIncidents = count("SELECT COUNT(*) FROM incidents i JOIN monitors m ON m.id = i.monitor_id WHERE m.member_id = ? AND i.resolved_at IS NULL"),
        )
    }

    private fun demoMemberId(): Long =
        jdbcTemplate.queryForObject("SELECT id FROM members WHERE provider = 'DEMO'", Long::class.java)!!
}
