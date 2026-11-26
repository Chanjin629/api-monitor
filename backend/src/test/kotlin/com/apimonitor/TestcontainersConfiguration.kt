package com.apimonitor

import com.apimonitor.global.ssrf.HostResolver
import com.apimonitor.notification.discord.DiscordSendResult
import com.apimonitor.notification.discord.DiscordWebhookClient
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.utility.DockerImageName
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 통합 테스트 공통 설정. 모든 @SpringBootTest가 이 설정을 import해 같은 컨텍스트와 MariaDB 컨테이너를 공유한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	fun mariaDbContainer(): MariaDBContainer {
		return MariaDBContainer(DockerImageName.parse("mariadb:11.8"))
	}

	/** 요청 제한 횟수를 세는 Redis. 개발 환경과 같은 버전을 쓴다. */
	@Bean
	@ServiceConnection(name = "redis")
	fun redisContainer(): GenericContainer<*> {
		return GenericContainer(DockerImageName.parse("redis:8.12.0-alpine")).withExposedPorts(6379)
	}

	/** 테스트에서 시각을 바꿔 만료 같은 상황을 재현한다. 기본은 실제 현재 시각이다. */
	@Bean
	@Primary
	fun testClock(): MutableClock = MutableClock()

	/**
	 * 실제 DNS 대신 정해 둔 응답을 돌려준다. 테스트가 네트워크 상태에 따라 달라지지 않게 한다.
	 * IP를 직접 쓴 주소는 그대로 해석하고, 목록에 없는 도메인은 조회 실패로 처리한다.
	 */
	@Bean
	@Primary
	fun testHostResolver(): HostResolver = HostResolver { host ->
		when (host) {
			"public.example" -> listOf(InetAddress.getByName("93.184.216.34"))
			"internal.example" -> listOf(InetAddress.getByName("10.0.0.5"))
			"mixed.example" -> listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1"))
			else -> if (host.contains(':') || host.first().isDigit()) {
				InetAddress.getAllByName(host).toList()
			} else {
				throw UnknownHostException(host)
			}
		}
	}

	/** 실제 Discord로 보내지 않고 보낸 내용을 기록한다. */
	@Bean
	@Primary
	fun testDiscordClient(): TestDiscordClient = TestDiscordClient()
}

/**
 * 가짜 Discord 클라이언트. Webhook URL마다 돌려줄 결과를 정해 둘 수 있고, 정하지 않으면 성공으로 처리한다.
 * 테스트끼리 같은 DB를 공유하므로, 테스트마다 다른 Webhook URL을 써서 서로의 결과가 섞이지 않게 한다.
 */
class TestDiscordClient : DiscordWebhookClient() {
	private val results = ConcurrentHashMap<String, ConcurrentLinkedQueue<DiscordSendResult>>()
	private val sent = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

	fun willReturn(webhookUrl: String, vararg next: DiscordSendResult) {
		results.computeIfAbsent(webhookUrl) { ConcurrentLinkedQueue() }.addAll(next)
	}

	fun messagesTo(webhookUrl: String): List<String> = sent[webhookUrl].orEmpty()

	override fun send(webhookUrl: String, content: String): DiscordSendResult {
		sent.computeIfAbsent(webhookUrl) { CopyOnWriteArrayList() }.add(content)
		return results[webhookUrl]?.poll() ?: DiscordSendResult.Sent
	}
}
