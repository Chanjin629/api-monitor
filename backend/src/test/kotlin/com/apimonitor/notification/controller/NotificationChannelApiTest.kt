package com.apimonitor.notification.controller

import com.apimonitor.TestDiscordClient
import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.global.security.JwtTokenProvider
import com.apimonitor.member.domain.Role
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.service.MemberService
import com.apimonitor.notification.discord.DiscordSendResult
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper

/** 알림 채널 API를 실제 필터 체인, DB와 함께 확인한다. Discord는 가짜 클라이언트를 쓴다. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class NotificationChannelApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val memberService: MemberService,
    @Autowired private val jwtTokenProvider: JwtTokenProvider,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val discordClient: TestDiscordClient,
) {

    @Test
    fun `채널을 등록하면 주소는 끝 4자리만 보여 주고 DB에는 암호화해 저장한다`() {
        val token = userToken()
        val webhookUrl = webhook("abcd")

        val id = createChannel(token, webhookUrl) {
            jsonPath("$.data.type") { value("DISCORD") }
            jsonPath("$.data.name") { value("운영 알림") }
            jsonPath("$.data.webhookUrlMasked") { value("••••abcd") }
            jsonPath("$.data.enabled") { value(true) }
        }

        val stored = jdbcTemplate.queryForObject(
            "SELECT webhook_url_encrypted FROM notification_channels WHERE id = ?", String::class.java, id,
        )
        assertThat(stored).startsWith("v1:").doesNotContain("abcd")
        mockMvc.get("/api/v1/notification-channels") { bearer(token) }.andExpect {
            jsonPath("$.data.items", hasSize<Any>(1))
            jsonPath("$.data.items[0].webhookUrlMasked") { value("••••abcd") }
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://discord.com/api/webhooks/123/token",
            "https://evil.example/api/webhooks/123/token",
            "https://discord.com.evil.example/api/webhooks/123/token",
            "https://discord.com/api/webhooks/123/token?wait=true",
            "https://discord.com/api/webhooks/abc/token",
            "https://discord.com/api/other/123/token",
        ],
    )
    fun `Discord Webhook 형식이 아니면 422 WEBHOOK_URL_INVALID다`(url: String) {
        mockMvc.post("/api/v1/notification-channels") {
            bearer(userToken())
            json("""{ "name": "채널", "webhookUrl": "$url" }""")
        }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.error.code") { value("WEBHOOK_URL_INVALID") }
        }
    }

    @Test
    fun `채널은 3개까지 등록할 수 있다`() {
        val token = userToken()
        repeat(3) { createChannel(token, webhook("n$it")) }

        mockMvc.post("/api/v1/notification-channels") {
            bearer(token)
            json("""{ "name": "네 번째", "webhookUrl": "${webhook("four")}" }""")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("CHANNEL_LIMIT_EXCEEDED") }
        }
    }

    @Test
    fun `보낸 필드만 수정하고, 삭제하면 목록에서 사라진다`() {
        val token = userToken()
        val id = createChannel(token, webhook("old1"))

        mockMvc.patch("/api/v1/notification-channels/$id") {
            bearer(token)
            json("""{ "enabled": false }""")
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.enabled") { value(false) }
            jsonPath("$.data.name") { value("운영 알림") }
            jsonPath("$.data.webhookUrlMasked") { value("••••old1") }
        }
        mockMvc.patch("/api/v1/notification-channels/$id") {
            bearer(token)
            json("""{ "name": "새 이름", "webhookUrl": "${webhook("new2")}" }""")
        }.andExpect {
            jsonPath("$.data.name") { value("새 이름") }
            jsonPath("$.data.webhookUrlMasked") { value("••••new2") }
        }
        mockMvc.patch("/api/v1/notification-channels/$id") {
            bearer(token)
            json("""{ "webhookUrl": "https://evil.example/hook" }""")
        }.andExpect {
            jsonPath("$.error.code") { value("WEBHOOK_URL_INVALID") }
        }

        mockMvc.delete("/api/v1/notification-channels/$id") { bearer(token) }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/notification-channels") { bearer(token) }.andExpect {
            jsonPath("$.data.items") { isEmpty() }
        }
    }

    @Test
    fun `다른 회원의 채널은 수정, 삭제, 테스트 발송 모두 404다`() {
        val id = createChannel(userToken(), webhook("mine"))
        val other = userToken()

        mockMvc.patch("/api/v1/notification-channels/$id") { bearer(other); json("""{ "enabled": false }""") }
            .andExpect { jsonPath("$.error.code") { value("CHANNEL_NOT_FOUND") } }
        mockMvc.delete("/api/v1/notification-channels/$id") { bearer(other) }
            .andExpect { status { isNotFound() } }
        mockMvc.post("/api/v1/notification-channels/$id/test") { bearer(other) }
            .andExpect { jsonPath("$.error.code") { value("CHANNEL_NOT_FOUND") } }
    }

    @Test
    fun `테스트 발송이 성공하면 delivered, 실패하면 502다`() {
        val token = userToken()
        val okUrl = webhook("tok1")
        val ok = createChannel(token, okUrl)
        val brokenUrl = webhook("bad1")
        val broken = createChannel(token, brokenUrl)
        discordClient.willReturn(brokenUrl, DiscordSendResult.Failed("HTTP_404", permanent = true))

        mockMvc.post("/api/v1/notification-channels/$ok/test") { bearer(token) }.andExpect {
            status { isOk() }
            jsonPath("$.data.delivered") { value(true) }
        }
        assertThat(discordClient.messagesTo(okUrl).single()).contains("테스트 메시지", "운영 알림")

        mockMvc.post("/api/v1/notification-channels/$broken/test") { bearer(token) }.andExpect {
            status { isBadGateway() }
            jsonPath("$.error.code") { value("NOTIFICATION_SEND_FAILED") }
        }
    }

    @Test
    fun `데모 계정은 채널을 등록할 수 없다`() {
        val body = mockMvc.post("/api/v1/auth/demo").andReturn().response.contentAsString
        val demoToken = jsonMapper.readTree(body).path("data").path("accessToken").asString()

        mockMvc.post("/api/v1/notification-channels") {
            bearer(demoToken)
            json("""{ "name": "데모", "webhookUrl": "${webhook("demo")}" }""")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("DEMO_READ_ONLY") }
        }
    }

    private fun createChannel(
        token: String,
        webhookUrl: String,
        extra: org.springframework.test.web.servlet.MockMvcResultMatchersDsl.() -> Unit = {},
    ): Long {
        val response = mockMvc.post("/api/v1/notification-channels") {
            bearer(token)
            json("""{ "type": "DISCORD", "name": "운영 알림", "webhookUrl": "$webhookUrl" }""")
        }.andExpect {
            status { isCreated() }
            extra()
        }.andReturn().response.contentAsString
        return jsonMapper.readTree(response).path("data").path("id").asLong()
    }

    /** 테스트마다 다른 주소를 쓰도록 끝에 고유한 값을 붙인다. */
    private fun webhook(suffix: String) = "https://discord.com/api/webhooks/${System.nanoTime()}/token-$suffix"

    private fun userToken(): String {
        val id = System.nanoTime().toString()
        val memberId = requireNotNull(memberService.loginWithGithub(GithubUser(id, "user$id", null, null)).id)
        return jwtTokenProvider.createAccessToken(memberId, Role.USER)
    }

    private fun MockHttpServletRequestDsl.bearer(token: String) {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private fun MockHttpServletRequestDsl.json(body: String) {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }
}
