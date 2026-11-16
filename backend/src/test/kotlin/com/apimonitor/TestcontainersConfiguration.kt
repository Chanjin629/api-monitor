package com.apimonitor

import com.apimonitor.global.ssrf.HostResolver
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.utility.DockerImageName
import java.net.InetAddress
import java.net.UnknownHostException

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
}
