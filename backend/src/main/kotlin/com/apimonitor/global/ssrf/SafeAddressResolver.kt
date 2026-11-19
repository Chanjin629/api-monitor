package com.apimonitor.global.ssrf

import io.netty.resolver.AddressResolver
import io.netty.resolver.AddressResolverGroup
import io.netty.resolver.InetNameResolver
import io.netty.resolver.InetSocketAddressResolver
import io.netty.util.NetUtil
import io.netty.util.concurrent.EventExecutor
import io.netty.util.concurrent.Promise
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.Executors

/**
 * 검사 요청이 연결할 IP를 정하고 검증한다. (docs/01-requirements.md N-S2, F-C2)
 *
 * WebClient(Reactor Netty)는 연결 직전에 이 리졸버로 도메인을 IP로 바꾼다.
 * 조회한 IP를 모두 검사해 하나라도 차단 대역이면 연결하지 않고, 통과한 IP로만 연결한다.
 * 검증한 IP와 실제 연결하는 IP가 같으므로, 그 사이에 DNS 응답을 바꾸는 DNS 리바인딩 공격이 통하지 않는다.
 * Host 헤더와 HTTPS 인증서 확인은 원래 도메인으로 하므로 정상적인 사이트는 그대로 동작한다.
 */
@Component
class SafeAddressResolver(
    private val hostResolver: HostResolver,
    private val isBlocked: (InetAddress) -> Boolean,
) {
    /** 운영 코드는 실제 차단 대역을 쓴다. 두 번째 생성자는 차단 규칙을 바꿔 끼우는 테스트용이다. */
    @Autowired
    constructor(hostResolver: HostResolver) : this(hostResolver, BlockedAddressRanges::isBlocked)

    // DNS 조회는 응답을 기다리는 블로킹 작업이라 Netty 이벤트 스레드가 아닌 가상 스레드에서 한다.
    private val lookupExecutor = Executors.newVirtualThreadPerTaskExecutor()

    /** WebClient의 HttpClient에 끼워 넣을 리졸버 */
    val resolverGroup: AddressResolverGroup<InetSocketAddress> = object : AddressResolverGroup<InetSocketAddress>() {
        override fun newResolver(executor: EventExecutor): AddressResolver<InetSocketAddress> =
            InetSocketAddressResolver(executor, SafeNameResolver(executor))
    }

    /**
     * URL에 IP를 직접 쓴 경우 리졸버를 거치지 않고 바로 연결하므로, 요청 전에 이 함수로 따로 확인한다.
     * 도메인이면 false를 돌려주고 판단을 리졸버에 맡긴다.
     */
    fun isBlockedLiteral(host: String): Boolean {
        val bytes = NetUtil.createByteArrayFromIpAddressString(host) ?: return false
        return isBlocked(InetAddress.getByAddress(bytes))
    }

    /** 조회 결과를 검증한다. 하나라도 차단 대역이면 [BlockedAddressException]으로 실패시킨다. */
    internal fun resolveSafely(host: String): List<InetAddress> {
        val addresses = hostResolver.resolve(host)
        if (addresses.isEmpty()) throw UnknownHostException(host)
        if (addresses.any(isBlocked)) throw BlockedAddressException()
        return addresses
    }

    private inner class SafeNameResolver(executor: EventExecutor) : InetNameResolver(executor) {

        override fun doResolve(inetHost: String, promise: Promise<InetAddress>) {
            lookup(inetHost, promise) { it.first() }
        }

        override fun doResolveAll(inetHost: String, promise: Promise<List<InetAddress>>) {
            lookup(inetHost, promise) { it }
        }

        private fun <T> lookup(host: String, promise: Promise<T>, pick: (List<InetAddress>) -> T) {
            lookupExecutor.execute {
                try {
                    promise.trySuccess(pick(resolveSafely(host)))
                } catch (e: Exception) {
                    promise.tryFailure(e)
                }
            }
        }
    }
}

/** 연결하려던 IP가 차단 대역이다. 어떤 IP였는지는 내부망 구조가 드러나지 않도록 담지 않는다. */
class BlockedAddressException : IOException("Blocked address")
