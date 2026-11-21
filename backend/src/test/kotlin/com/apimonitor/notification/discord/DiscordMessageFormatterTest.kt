package com.apimonitor.notification.discord

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class DiscordMessageFormatterTest {

    private val formatter = DiscordMessageFormatter()

    @Test
    fun `장애 메시지는 한국 시간과 읽기 쉬운 원인을 쓰고 URL의 쿼리를 뺀다`() {
        val message = formatter.incidentStarted(
            monitorName = "결제 API",
            url = "https://api.example.com/health?apiKey=secret#top",
            cause = "UNEXPECTED_STATUS",
            statusCode = 503,
            startedAt = Instant.parse("2026-11-09T11:00:00Z"),
        )

        assertThat(message).isEqualTo(
            """
            🔴 장애 감지: 결제 API
            URL: https://api.example.com/health
            원인: HTTP 503
            시작: 2026-11-09 20:00 (KST)
            """.trimIndent(),
        )
    }

    @Test
    fun `복구 메시지는 장애 시간과 복구 시각을 보여 준다`() {
        val message = formatter.recovered(
            monitorName = "결제 API",
            startedAt = Instant.parse("2026-11-09T11:00:00Z"),
            resolvedAt = Instant.parse("2026-11-09T11:20:00Z"),
        )

        assertThat(message).isEqualTo(
            """
            🟢 복구: 결제 API
            장애 시간: 20분
            복구: 2026-11-09 20:20 (KST)
            """.trimIndent(),
        )
    }

    @Test
    fun `URL은 사용자 정보, 쿼리, 프래그먼트를 빼고 포트와 경로는 남긴다`() {
        assertThat(formatter.displayUrl("https://user:pw@api.example.com:8443/v1/health?token=abc"))
            .isEqualTo("https://api.example.com:8443/v1/health")
        assertThat(formatter.displayUrl("https://api.example.com")).isEqualTo("https://api.example.com")
        assertThat(formatter.displayUrl("not a url")).isEqualTo("-")
    }

    @Test
    fun `원인 코드를 한글로 바꾼다`() {
        assertThat(formatter.causeLabel("TIMEOUT", null)).isEqualTo("시간 초과")
        assertThat(formatter.causeLabel("DNS_ERROR", null)).isEqualTo("도메인 조회 실패")
        assertThat(formatter.causeLabel("TLS_ERROR", null)).isEqualTo("인증서 오류")
        assertThat(formatter.causeLabel("CONNECTION_ERROR", null)).isEqualTo("연결 실패")
        assertThat(formatter.causeLabel("UNEXPECTED_STATUS", 404)).isEqualTo("HTTP 404")
    }

    @Test
    fun `장애 시간은 분, 시간, 일 단위로 줄여 쓴다`() {
        assertThat(formatter.durationLabel(Duration.ofSeconds(30))).isEqualTo("1분 미만")
        assertThat(formatter.durationLabel(Duration.ofMinutes(20))).isEqualTo("20분")
        assertThat(formatter.durationLabel(Duration.ofMinutes(60))).isEqualTo("1시간")
        assertThat(formatter.durationLabel(Duration.ofMinutes(65))).isEqualTo("1시간 5분")
        assertThat(formatter.durationLabel(Duration.ofHours(27))).isEqualTo("1일 3시간")
    }
}
