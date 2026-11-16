package com.apimonitor.global.ssrf

import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 호스트 이름을 IP 목록으로 바꾼다. 조회에 실패하면 UnknownHostException을 던진다.
 * 테스트에서 DNS 응답을 흉내 낼 수 있도록 인터페이스로 둔다.
 */
fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>
}

/** 운영체제 DNS로 조회한다. 응답이 늦으면 3초 뒤 실패로 처리한다. */
@Component
class SystemHostResolver : HostResolver {

    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    override fun resolve(host: String): List<InetAddress> =
        try {
            CompletableFuture.supplyAsync({ InetAddress.getAllByName(host).toList() }, executor)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw UnknownHostException("DNS lookup timed out: $host")
        } catch (e: ExecutionException) {
            throw e.cause as? UnknownHostException ?: UnknownHostException("DNS lookup failed: $host")
        }

    companion object {
        private const val TIMEOUT_SECONDS = 3L
    }
}
