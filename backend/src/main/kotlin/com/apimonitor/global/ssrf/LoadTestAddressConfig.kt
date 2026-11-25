package com.apimonitor.global.ssrf

import io.netty.util.NetUtil
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import java.net.InetAddress

/** 부하 테스트에서 차단 규칙에서 뺄 IP 목록. 도메인은 받지 않는다. */
@ConfigurationProperties("loadtest")
data class LoadTestProperties(
    val allowedAddresses: List<String> = emptyList(),
)

/**
 * 부하 테스트 전용 SSRF 예외. (docs/02-architecture.md 4.1)
 *
 * PC에 띄운 가짜 대상 서버(127.0.0.1)를 검사하려면 차단 대역 하나를 열어야 한다.
 * `loadtest`와 `local` 프로필이 함께 켜졌을 때만 만들어지므로 운영에서는 존재하지 않는다.
 * 설정에 적은 IP만 빼고 나머지 차단 대역은 그대로 막는다.
 */
@Configuration
@Profile("loadtest & local")
@EnableConfigurationProperties(LoadTestProperties::class)
class LoadTestAddressConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    @Primary
    fun loadTestSafeAddressResolver(hostResolver: HostResolver, properties: LoadTestProperties): SafeAddressResolver {
        val allowed = parseAllowed(properties.allowedAddresses)
        log.warn("Load test mode: SSRF block list excludes {}", allowed.map { it.hostAddress })
        return SafeAddressResolver(hostResolver, blockRule(allowed))
    }

    companion object {
        /** 허용한 IP가 아니면 원래 차단 규칙을 따른다. */
        fun blockRule(allowed: Set<InetAddress>): (InetAddress) -> Boolean =
            { address -> address !in allowed && BlockedAddressRanges.isBlocked(address) }

        /** IP 문자열만 받는다. 도메인을 받으면 DNS 응답에 따라 허용 대상이 바뀔 수 있기 때문이다. */
        fun parseAllowed(values: List<String>): Set<InetAddress> {
            require(values.isNotEmpty()) { "loadtest.allowed-addresses must not be empty" }
            return values.map { value ->
                val bytes = requireNotNull(NetUtil.createByteArrayFromIpAddressString(value.trim())) {
                    "loadtest.allowed-addresses accepts IP addresses only: $value"
                }
                InetAddress.getByAddress(bytes)
            }.toSet()
        }
    }
}
