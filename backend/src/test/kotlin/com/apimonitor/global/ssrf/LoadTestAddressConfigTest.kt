package com.apimonitor.global.ssrf

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.net.InetAddress

/** 부하 테스트 예외가 정해 둔 IP 하나만 열고, 정해 둔 프로필에서만 만들어지는지 확인한다. */
class LoadTestAddressConfigTest {

    private val dns = HostResolver { host ->
        when (host) {
            "target.localhost" -> listOf(ip("127.0.0.1"))
            "other-loopback.localhost" -> listOf(ip("127.0.0.2"))
            "internal.example" -> listOf(ip("10.0.0.5"))
            else -> listOf(ip("169.254.169.254"))
        }
    }

    private fun runner(vararg profiles: String) = ApplicationContextRunner()
        .withInitializer { it.environment.setActiveProfiles(*profiles) }
        .withBean(HostResolver::class.java, { dns })
        .withBean(SafeAddressResolver::class.java, { SafeAddressResolver(dns) })
        .withUserConfiguration(LoadTestAddressConfig::class.java)
        .withPropertyValues("loadtest.allowed-addresses=127.0.0.1")

    @Test
    fun `loadtest와 local이 함께 켜지면 허용한 IP 하나만 통과한다`() {
        runner("local", "loadtest").run { context ->
            val resolver = context.getBean(SafeAddressResolver::class.java)

            assertThat(resolver.resolveSafely("target.localhost")).containsExactly(ip("127.0.0.1"))
            assertThat(resolver.isBlockedLiteral("127.0.0.1")).isFalse()
            // 같은 루프백 대역이라도 허용 목록에 없으면 막는다.
            assertThatThrownBy { resolver.resolveSafely("other-loopback.localhost") }.isInstanceOf(BlockedAddressException::class.java)
            assertThatThrownBy { resolver.resolveSafely("internal.example") }.isInstanceOf(BlockedAddressException::class.java)
            assertThatThrownBy { resolver.resolveSafely("metadata.example") }.isInstanceOf(BlockedAddressException::class.java)
        }
    }

    @Test
    fun `local 없이 loadtest만 켜거나 loadtest가 없으면 예외를 만들지 않는다`() {
        listOf(arrayOf("loadtest"), arrayOf("local"), arrayOf("prod", "loadtest")).forEach { profiles ->
            runner(*profiles).run { context ->
                assertThat(context).doesNotHaveBean(LoadTestAddressConfig::class.java)
                assertThat(context.getBean(SafeAddressResolver::class.java).isBlockedLiteral("127.0.0.1")).isTrue()
            }
        }
    }

    @Test
    fun `허용 목록은 IP만 받고 비어 있으면 시작하지 않는다`() {
        assertThatThrownBy { LoadTestAddressConfig.parseAllowed(listOf("localhost")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { LoadTestAddressConfig.parseAllowed(emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(LoadTestAddressConfig.parseAllowed(listOf(" 127.0.0.1 "))).containsExactly(ip("127.0.0.1"))
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}
