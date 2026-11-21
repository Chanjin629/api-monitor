package com.apimonitor.notification.discord

import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 알림 메시지 문구를 만든다. (docs/05-api-spec.md 8장)
 * URL의 쿼리 문자열은 빼서, URL에 API 키를 넣어 쓰는 사용자의 키가 Discord에 노출되지 않게 한다.
 */
@Component
class DiscordMessageFormatter {

    fun incidentStarted(monitorName: String, url: String, cause: String, statusCode: Int?, startedAt: Instant): String =
        """
        🔴 장애 감지: $monitorName
        URL: ${displayUrl(url)}
        원인: ${causeLabel(cause, statusCode)}
        시작: ${kst(startedAt)}
        """.trimIndent()

    fun recovered(monitorName: String, startedAt: Instant, resolvedAt: Instant): String =
        """
        🟢 복구: $monitorName
        장애 시간: ${durationLabel(Duration.between(startedAt, resolvedAt))}
        복구: ${kst(resolvedAt)}
        """.trimIndent()

    fun test(channelName: String): String =
        "✅ API Monitor 테스트 메시지: \"$channelName\" 채널로 장애·복구 알림을 보냅니다."

    /** 스킴, 호스트, 포트, 경로만 남긴다. 사용자 정보, 쿼리, 프래그먼트는 뺀다. */
    fun displayUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "-"
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty()
        val display = "${uri.scheme}://${uri.host}$port$path"
        return if (display.length > MAX_URL_LENGTH) display.take(MAX_URL_LENGTH) + "…" else display
    }

    fun causeLabel(cause: String, statusCode: Int?): String = when (cause) {
        "UNEXPECTED_STATUS" -> if (statusCode != null) "HTTP $statusCode" else "예상과 다른 상태 코드"
        "TIMEOUT" -> "시간 초과"
        "CONNECTION_ERROR" -> "연결 실패"
        "DNS_ERROR" -> "도메인 조회 실패"
        "TLS_ERROR" -> "인증서 오류"
        else -> cause
    }

    fun durationLabel(duration: Duration): String {
        val minutes = duration.toMinutes()
        return when {
            minutes < 1 -> "1분 미만"
            minutes < 60 -> "${minutes}분"
            minutes < 60 * 24 -> joinParts(minutes / 60, "시간", minutes % 60, "분")
            else -> joinParts(minutes / (60 * 24), "일", minutes / 60 % 24, "시간")
        }
    }

    private fun joinParts(major: Long, majorUnit: String, minor: Long, minorUnit: String) =
        if (minor == 0L) "$major$majorUnit" else "$major$majorUnit $minor$minorUnit"

    private fun kst(instant: Instant): String = TIME_FORMAT.format(instant) + " (KST)"

    companion object {
        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))
        private const val MAX_URL_LENGTH = 300
    }
}
