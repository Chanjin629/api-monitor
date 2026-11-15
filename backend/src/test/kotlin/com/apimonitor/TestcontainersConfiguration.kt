package com.apimonitor

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.utility.DockerImageName

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
}
