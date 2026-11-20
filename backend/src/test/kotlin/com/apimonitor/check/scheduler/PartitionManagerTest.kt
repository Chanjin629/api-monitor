package com.apimonitor.check.scheduler

import com.apimonitor.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * 날짜별 파티션 생성과 보관 기간이 지난 파티션 삭제를 실제 MariaDB로 확인한다.
 * 파티션은 테이블 구조라 다른 테스트와 공유된다. 그래서 날짜는 오늘 기준으로 잡고, 이 테스트의 기록만 골라 확인한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class PartitionManagerTest(
    @Autowired private val partitionManager: PartitionManager,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    @Test
    fun `오늘부터 3일치 날짜 파티션을 만들고, 다시 실행해도 바뀌지 않는다`() {
        partitionManager.maintain(today)
        val first = partitionNames()

        partitionManager.maintain(today)

        assertThat(partitionNames()).isEqualTo(first)
        // 오늘 이전 파티션은 다른 테스트(보관 기간)가 먼저 지웠을 수 있어, 앞으로 만들어 둔 마지막 날짜를 확인한다.
        assertThat(first).contains("p" + today.plusDays(3).toString().replace("-", ""))
        assertThat(first.last()).isEqualTo("p_future")
    }

    @Test
    fun `14일이 지난 파티션은 지우고 그 이후 기록은 남긴다`() {
        partitionManager.maintain(today)
        val monitorId = 900_000_000L + Random.nextLong(1_000_000)
        insert(monitorId, today.minusDays(30))
        insert(monitorId, today.plusDays(2))

        // 오늘이 15일 뒤라고 보면, 기준일(오늘+1) 이전 파티션은 보관 기간이 지났다.
        val later = today.plusDays(15)
        partitionManager.maintain(later)

        assertThat(checkedDays(monitorId)).containsExactly(today.plusDays(2))
        val names = partitionNames()
        assertThat(names).doesNotContain("p_initial", "p" + today.toString().replace("-", ""))
        assertThat(names).contains("p" + later.plusDays(3).toString().replace("-", ""), "p_future")
    }

    private fun insert(monitorId: Long, day: LocalDate) {
        jdbcTemplate.update(
            "INSERT INTO check_results (monitor_id, checked_at, result) VALUES (?, ?, 'SUCCESS')",
            monitorId,
            day.atTime(12, 0),
        )
    }

    private fun checkedDays(monitorId: Long): List<LocalDate> =
        jdbcTemplate.query(
            "SELECT DATE(checked_at) AS day FROM check_results WHERE monitor_id = ? ORDER BY checked_at",
            { rs, _ -> rs.getObject("day", LocalDate::class.java) },
            monitorId,
        )

    private fun partitionNames(): List<String> =
        jdbcTemplate.queryForList(
            """
            SELECT partition_name FROM information_schema.partitions
            WHERE table_schema = DATABASE() AND table_name = 'check_results' AND partition_name IS NOT NULL
            ORDER BY partition_ordinal_position
            """.trimIndent(),
            String::class.java,
        ).filterNotNull()
}
