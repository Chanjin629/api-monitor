package com.apimonitor.notification.discord

import io.netty.channel.ChannelOption
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * Discord Webhook으로 메시지를 보낸다.
 * 발송기 스레드에서 응답을 기다리는 방식으로 쓴다. 코루틴은 검사 실행부에만 쓴다는 규칙(CLAUDE.md)을 따른다.
 * Webhook URL은 비밀 값이므로 로그와 결과에 남기지 않는다.
 */
@Component
class DiscordWebhookClient {

    private val log = LoggerFactory.getLogger(javaClass)

    // 서버는 MVC로 동작해 WebClient.Builder 빈이 없으므로 직접 만든다. 검사 실행기와 같은 방식이다.
    private val webClient: WebClient = WebClient.builder()
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                    .responseTimeout(RESPONSE_TIMEOUT)
                    .followRedirect(false),
            ),
        )
        .build()

    fun send(webhookUrl: String, content: String): DiscordSendResult =
        try {
            webClient.post()
                .uri(URI(webhookUrl))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    mapOf(
                        "content" to content.take(MAX_CONTENT_LENGTH),
                        // 모니터 이름에 @everyone 같은 글자가 있어도 채널 멤버를 호출하지 않게 한다.
                        "allowed_mentions" to mapOf("parse" to emptyList<String>()),
                    ),
                )
                .exchangeToMono { response ->
                    val status = response.statusCode().value()
                    when {
                        response.statusCode().is2xxSuccessful -> response.releaseBody().thenReturn(DiscordSendResult.Sent)
                        status == 429 -> response.bodyToMono(String::class.java).defaultIfEmpty("").map { body ->
                            DiscordSendResult.RateLimited(retryAfter(response.headers().asHttpHeaders(), body))
                        }
                        // Webhook이 삭제되었거나 주소가 틀렸다. 다시 보내도 결과가 같다.
                        status in PERMANENT_FAILURE_CODES ->
                            response.releaseBody().thenReturn(DiscordSendResult.Failed("HTTP_$status", permanent = true))
                        else -> response.releaseBody().thenReturn(DiscordSendResult.Failed("HTTP_$status", permanent = false))
                    }
                }
                .timeout(TOTAL_TIMEOUT, Mono.error(TimeoutException()))
                .block()!!
        } catch (e: Exception) {
            val code = if (generateSequence<Throwable>(e) { it.cause }.any { it is TimeoutException || it is io.netty.handler.timeout.ReadTimeoutException }) {
                "TIMEOUT"
            } else {
                "CONNECTION_ERROR"
            }
            log.debug("Discord send failed code={} error={}", code, e.javaClass.simpleName)
            DiscordSendResult.Failed(code, permanent = false)
        }

    /** 429 응답의 대기 시간. Retry-After 헤더를 먼저 보고, 없으면 본문의 retry_after(초)를 읽는다. */
    private fun retryAfter(headers: HttpHeaders, body: String): Duration {
        val seconds = headers.getFirst(HttpHeaders.RETRY_AFTER)?.toDoubleOrNull()
            ?: RETRY_AFTER_IN_BODY.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: DEFAULT_RETRY_AFTER_SECONDS
        val millis = (seconds * 1000).toLong().coerceIn(MIN_RETRY_AFTER.toMillis(), MAX_RETRY_AFTER.toMillis())
        return Duration.ofMillis(millis)
    }

    companion object {
        /** Discord 메시지 최대 길이 */
        const val MAX_CONTENT_LENGTH = 2000
        private const val CONNECT_TIMEOUT_MS = 5000
        private val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val TOTAL_TIMEOUT: Duration = Duration.ofSeconds(15)
        private val PERMANENT_FAILURE_CODES = setOf(400, 401, 403, 404)
        private val RETRY_AFTER_IN_BODY = Regex("\"retry_after\"\\s*:\\s*([0-9.]+)")
        private const val DEFAULT_RETRY_AFTER_SECONDS = 5.0
        private val MIN_RETRY_AFTER: Duration = Duration.ofSeconds(1)
        private val MAX_RETRY_AFTER: Duration = Duration.ofHours(1)
    }
}

/** 발송 결과 */
sealed interface DiscordSendResult {
    data object Sent : DiscordSendResult

    /** 요청이 너무 많다. [retryAfter] 뒤에 다시 보낸다. */
    data class RateLimited(val retryAfter: Duration) : DiscordSendResult

    /**
     * 보내지 못했다.
     * @property errorCode HTTP_503, TIMEOUT 같은 코드. 응답 본문이나 URL은 담지 않는다.
     * @property permanent true면 다시 보내도 소용없다 (Webhook 삭제 등).
     */
    data class Failed(val errorCode: String, val permanent: Boolean) : DiscordSendResult
}
