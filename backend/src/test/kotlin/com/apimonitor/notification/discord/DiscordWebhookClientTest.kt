package com.apimonitor.notification.discord

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.http.Fault
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/** Discord 대신 WireMock으로 응답 종류별 결과를 확인한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiscordWebhookClientTest {

    private val server = WireMockServer(options().dynamicPort()).apply { start() }
    private val client = DiscordWebhookClient()
    private val webhookPath = "/api/webhooks/123/token"
    private val webhookUrl get() = "http://localhost:${server.port()}$webhookPath"

    @BeforeEach
    fun reset() = server.resetAll()

    @AfterAll
    fun stop() = server.stop()

    @Test
    fun `성공하면 Sent이고, 멘션을 막는 옵션과 함께 메시지를 보낸다`() {
        server.stubFor(post(webhookPath).willReturn(aResponse().withStatus(204)))

        val result = client.send(webhookUrl, "🔴 장애 감지: @everyone API")

        assertThat(result).isEqualTo(DiscordSendResult.Sent)
        server.verify(
            postRequestedFor(urlEqualTo(webhookPath)).withRequestBody(
                equalToJson("""{ "content": "🔴 장애 감지: @everyone API", "allowed_mentions": { "parse": [] } }"""),
            ),
        )
    }

    @Test
    fun `Discord 최대 길이를 넘는 메시지는 잘라서 보낸다`() {
        server.stubFor(post(webhookPath).willReturn(aResponse().withStatus(204)))

        client.send(webhookUrl, "가".repeat(3000))

        val body = server.allServeEvents.single().request.bodyAsString
        val content = JsonMapper.builder().build().readTree(body).path("content").asString()
        assertThat(content).hasSize(DiscordWebhookClient.MAX_CONTENT_LENGTH)
    }

    @Test
    fun `429면 Retry-After 헤더나 본문의 retry_after만큼 기다리게 한다`() {
        server.stubFor(post(webhookPath).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "2.5")))
        assertThat(client.send(webhookUrl, "a")).isEqualTo(DiscordSendResult.RateLimited(Duration.ofMillis(2500)))

        server.stubFor(
            post(webhookPath).willReturn(
                aResponse().withStatus(429).withHeader("Content-Type", "application/json").withBody("""{"retry_after": 3, "global": false}"""),
            ),
        )
        assertThat(client.send(webhookUrl, "a")).isEqualTo(DiscordSendResult.RateLimited(Duration.ofSeconds(3)))
    }

    @Test
    fun `Webhook이 없으면 다시 보내도 소용없는 실패다`() {
        server.stubFor(post(webhookPath).willReturn(aResponse().withStatus(404)))

        assertThat(client.send(webhookUrl, "a")).isEqualTo(DiscordSendResult.Failed("HTTP_404", permanent = true))
    }

    @Test
    fun `5xx와 연결 실패는 다시 보낼 수 있는 실패다`() {
        server.stubFor(post(webhookPath).willReturn(aResponse().withStatus(503)))
        assertThat(client.send(webhookUrl, "a")).isEqualTo(DiscordSendResult.Failed("HTTP_503", permanent = false))

        server.stubFor(post(webhookPath).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
        assertThat(client.send(webhookUrl, "a")).isEqualTo(DiscordSendResult.Failed("CONNECTION_ERROR", permanent = false))
    }
}
