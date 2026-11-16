package com.apimonitor.global.ssrf

import java.net.Inet4Address
import java.net.InetAddress

/**
 * SSRF 차단 대역 판별. 목록은 CLAUDE.md 보안 규칙을 따른다.
 * 등록 시점 검증과 검사 시점(11단계) 연결 검증이 이 규칙을 함께 쓴다.
 */
object BlockedAddressRanges {

    private val ipv4Ranges = listOf(
        "0.0.0.0/8", // 현재 네트워크
        "10.0.0.0/8", // 사설망
        "100.64.0.0/10", // 통신사·클라우드 공유 대역
        "127.0.0.0/8", // loopback
        "169.254.0.0/16", // link-local, 클라우드 메타데이터
        "172.16.0.0/12", // 사설망
        "192.0.0.0/24", // IETF 예약
        "192.168.0.0/16", // 사설망
        "198.18.0.0/15", // 벤치마크
        "224.0.0.0/4", // 멀티캐스트
        "240.0.0.0/4", // 미래 예약
        "255.255.255.255/32", // 브로드캐스트
    ).map(Cidr::parse)

    private val ipv6Ranges = listOf(
        "::/128", // 지정되지 않은 주소
        "::1/128", // loopback
        "::/96", // IPv4-compatible IPv6
        "::ffff:0:0/96", // IPv4-mapped IPv6
        "64:ff9b::/96", // NAT64
        "2002::/16", // 6to4
        "fc00::/7", // 사설망
        "fe80::/10", // link-local
        "ff00::/8", // 멀티캐스트
    ).map(Cidr::parse)

    fun isBlocked(address: InetAddress): Boolean {
        val ranges = if (address is Inet4Address) ipv4Ranges else ipv6Ranges
        return ranges.any { it.contains(address.address) }
    }

    private class Cidr(private val network: ByteArray, private val prefixLength: Int) {

        fun contains(address: ByteArray): Boolean {
            if (address.size != network.size) return false
            val fullBytes = prefixLength / 8
            for (i in 0 until fullBytes) {
                if (address[i] != network[i]) return false
            }
            val remainingBits = prefixLength % 8
            if (remainingBits == 0) return true
            val mask = (0xFF shl (8 - remainingBits)) and 0xFF
            return (address[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
        }

        companion object {
            fun parse(cidr: String): Cidr {
                val (ip, prefix) = cidr.split("/")
                // 상수 IP 리터럴이라 DNS 조회가 일어나지 않는다.
                return Cidr(InetAddress.getByName(ip).address, prefix.toInt())
            }
        }
    }
}
