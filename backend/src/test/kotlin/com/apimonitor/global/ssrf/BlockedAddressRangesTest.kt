package com.apimonitor.global.ssrf

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress

class BlockedAddressRangesTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "0.0.0.0", "0.1.2.3",
            "10.0.0.1", "10.255.255.255",
            "100.64.0.1", "100.127.255.255",
            "127.0.0.1", "127.255.255.254",
            "169.254.169.254", // 클라우드 메타데이터
            "172.16.0.1", "172.31.255.255",
            "192.0.0.1",
            "192.168.0.1",
            "198.18.0.1", "198.19.255.255",
            "224.0.0.1", "239.255.255.255",
            "240.0.0.1",
            "255.255.255.255",
        ],
    )
    fun `IPv4 차단 대역은 막는다`(ip: String) {
        assertThat(BlockedAddressRanges.isBlocked(InetAddress.getByName(ip))).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "::", "::1",
            "::7f00:1", // IPv4-compatible 127.0.0.1
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", // IPv4-mapped
            "64:ff9b::7f00:1", // NAT64
            "2002:7f00:1::", // 6to4
            "fc00::1", "fd12:3456::1",
            "fe80::1",
            "ff02::1",
        ],
    )
    fun `IPv6 차단 대역과 IPv4를 숨긴 형식은 막는다`(ip: String) {
        assertThat(BlockedAddressRanges.isBlocked(InetAddress.getByName(ip))).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "8.8.8.8", "1.1.1.1", "93.184.216.34",
            "100.63.255.255", "100.128.0.0", // 100.64/10 바로 바깥
            "172.15.255.255", "172.32.0.0", // 172.16/12 바로 바깥
            "198.17.255.255", "198.20.0.0", // 198.18/15 바로 바깥
            "2606:4700:4700::1111", "2001:4860:4860::8888",
        ],
    )
    fun `공인 주소는 허용한다`(ip: String) {
        assertThat(BlockedAddressRanges.isBlocked(InetAddress.getByName(ip))).isFalse()
    }
}
