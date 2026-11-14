package com.apimonitor

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

/**
 * Flyway가 실제 MariaDB에 설계(docs/03-erd.md)대로 스키마를 만드는지 확인한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Transactional
class SchemaMigrationTest(
    @Autowired private val jdbc: JdbcTemplate,
) {

    @Test
    fun `테이블 9개가 생성된다`() {
        val tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
            String::class.java,
        )

        assertThat(tables).contains(
            "members", "refresh_tokens", "monitors", "monitor_states", "check_results",
            "monitor_hourly_stats", "incidents", "notification_channels", "notification_deliveries",
        )
    }

    @Test
    fun `check_results는 날짜 기준 RANGE 파티션으로 나뉜다`() {
        val partitions = jdbc.queryForList(
            """
            SELECT partition_method FROM information_schema.partitions
            WHERE table_schema = DATABASE() AND table_name = 'check_results' AND partition_name IS NOT NULL
            """.trimIndent(),
            String::class.java,
        )

        assertThat(partitions).hasSizeGreaterThanOrEqualTo(2).allMatch { it == "RANGE" }
    }

    @Test
    fun `check_results에는 외래 키가 없어 모니터가 없어도 저장된다`() {
        jdbc.update(
            "INSERT INTO check_results (monitor_id, checked_at, result) VALUES (?, ?, ?)",
            999_999L, "2030-01-01 00:00:00.000", "SUCCESS",
        )

        val count = jdbc.queryForObject("SELECT COUNT(*) FROM check_results WHERE monitor_id = 999999", Int::class.java)
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `진행 중인 장애는 모니터당 하나만 저장된다`() {
        val monitorId = createMonitor()
        insertIncident(monitorId)

        assertThrows<DataIntegrityViolationException> { insertIncident(monitorId) }
    }

    @Test
    fun `복구된 장애가 있어도 새 장애를 저장할 수 있다`() {
        val monitorId = createMonitor()
        insertIncident(monitorId)
        jdbc.update("UPDATE incidents SET resolved_at = NOW(3), duration_seconds = 60 WHERE monitor_id = ?", monitorId)

        insertIncident(monitorId)

        val openMarkers = jdbc.queryForList(
            "SELECT open_marker FROM incidents WHERE monitor_id = ? ORDER BY id",
            Int::class.javaObjectType,
            monitorId,
        )
        assertThat(openMarkers).containsExactly(null, 1)
    }

    private fun createMonitor(): Long {
        val providerUserId = System.nanoTime().toString()
        jdbc.update(
            """
            INSERT INTO members (provider, provider_user_id, username, role, created_at, updated_at)
            VALUES ('GITHUB', ?, 'tester', 'USER', NOW(3), NOW(3))
            """.trimIndent(),
            providerUserId,
        )
        val memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
        jdbc.update(
            """
            INSERT INTO monitors (member_id, name, url, http_method, interval_minutes, created_at, updated_at)
            VALUES (?, 'test', 'https://example.com', 'GET', 1, NOW(3), NOW(3))
            """.trimIndent(),
            memberId,
        )
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
    }

    private fun insertIncident(monitorId: Long) {
        jdbc.update(
            "INSERT INTO incidents (monitor_id, started_at, detected_at, cause) VALUES (?, NOW(3), NOW(3), 'TIMEOUT')",
            monitorId,
        )
    }
}
