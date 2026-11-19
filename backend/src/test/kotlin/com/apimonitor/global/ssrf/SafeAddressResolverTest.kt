package com.apimonitor.global.ssrf

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class SafeAddressResolverTest {

    private val fakeDns = HostResolver { host ->
        when (host) {
            "public.example" -> listOf(ip("93.184.216.34"))
            "internal.example" -> listOf(ip("10.0.0.5"))
            "mixed.example" -> listOf(ip("93.184.216.34"), ip("169.254.169.254"))
            else -> throw UnknownHostException(host)
        }
    }
    private val resolver = SafeAddressResolver(fakeDns)

    @Test
    fun `공인 IP만 돌려주는 도메인은 그 IP로 연결한다`() {
        assertThat(resolver.resolveSafely("public.example")).containsExactly(ip("93.184.216.34"))
    }

    @Test
    fun `내부 IP가 하나라도 섞이면 연결하지 않는다`() {
        assertThatThrownBy { resolver.resolveSafely("internal.example") }.isInstanceOf(BlockedAddressException::class.java)
        assertThatThrownBy { resolver.resolveSafely("mixed.example") }.isInstanceOf(BlockedAddressException::class.java)
    }

    @Test
    fun `조회에 실패하면 UnknownHostException이다`() {
        assertThatThrownBy { resolver.resolveSafely("nowhere.example") }.isInstanceOf(UnknownHostException::class.java)
    }

    @Test
    fun `연결할 때마다 다시 조회해 DNS 리바인딩을 막는다`() {
        // 처음에는 공인 IP, 다음부터는 내부 IP를 돌려주는 공격자의 DNS
        val calls = AtomicInteger()
        val rebindingDns = HostResolver {
            if (calls.getAndIncrement() == 0) listOf(ip("93.184.216.34")) else listOf(ip("127.0.0.1"))
        }
        val rebindingResolver = SafeAddressResolver(rebindingDns)

        assertThat(rebindingResolver.resolveSafely("rebind.example")).containsExactly(ip("93.184.216.34"))
        assertThatThrownBy { rebindingResolver.resolveSafely("rebind.example") }
            .isInstanceOf(BlockedAddressException::class.java)
    }

    @Test
    fun `URL에 직접 쓴 IP는 리졸버를 거치지 않으므로 따로 검사한다`() {
        assertThat(resolver.isBlockedLiteral("127.0.0.1")).isTrue()
        assertThat(resolver.isBlockedLiteral("169.254.169.254")).isTrue()
        assertThat(resolver.isBlockedLiteral("::1")).isTrue()
        assertThat(resolver.isBlockedLiteral("::ffff:10.0.0.1")).isTrue()
        assertThat(resolver.isBlockedLiteral("93.184.216.34")).isFalse()
        // 도메인은 리졸버가 판단한다.
        assertThat(resolver.isBlockedLiteral("internal.example")).isFalse()
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}
