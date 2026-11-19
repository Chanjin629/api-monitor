package com.apimonitor.global.ssrf

import com.apimonitor.global.error.BusinessException
import com.apimonitor.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

/**
 * 사용자가 등록한 URL이 검사해도 안전한지 확인한다. (docs/01-requirements.md N-S1~N-S5)
 *
 * - http, https만 허용하고 URL 안의 사용자 정보(user:pass@)는 거부한다.
 * - 포트는 80, 443, 1024 이상만 허용한다.
 * - 숫자로만 쓴 모호한 IP(2130706433, 0x7f.1 등)는 해석이 환경마다 달라 거부한다.
 * - DNS로 조회한 IP가 하나라도 차단 대역이면 거부한다.
 * - DNS 조회에 실패하면 허용한다. 위험한 주소라는 근거가 없고, 실패는 검사 결과로 드러난다.
 */
@Component
class UrlSafetyValidator(
    private val hostResolver: HostResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun validate(rawUrl: String) {
        val host = validateSyntax(rawUrl)
        val addresses = try {
            hostResolver.resolve(host)
        } catch (e: UnknownHostException) {
            return
        }
        if (addresses.any(BlockedAddressRanges::isBlocked)) throw blocked("blocked address")
    }

    /**
     * DNS 조회 없이 URL 형식만 검사하고 호스트를 돌려준다. IPv6 호스트는 대괄호를 뗀다.
     * 검사 시점에도 같은 규칙을 쓰도록 분리했다. IP 확인은 SafeAddressResolver가 연결할 때 한다.
     */
    fun validateSyntax(rawUrl: String): String {
        val uri = try {
            URI(rawUrl.trim())
        } catch (e: URISyntaxException) {
            throw blocked("invalid syntax")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw blocked("scheme")
        if (uri.rawUserInfo != null) throw blocked("user info")

        val host = uri.host?.removePrefix("[")?.removeSuffix("]")
        if (host.isNullOrBlank()) throw blocked("no host")
        if (!isAllowedPort(uri.port)) throw blocked("port")
        if (isAmbiguousNumericHost(host)) throw blocked("ambiguous numeric host")
        return host
    }

    private fun isAllowedPort(port: Int): Boolean =
        port == DEFAULT_PORT || port == 80 || port == 443 || port in 1024..65535

    /** 정상적인 점 네 개짜리 IPv4가 아닌데 숫자·16진수로만 이루어진 호스트 */
    private fun isAmbiguousNumericHost(host: String): Boolean {
        val lower = host.lowercase()
        if (!NUMERIC_HOST.matches(lower)) return false
        return !CANONICAL_IPV4.matches(lower)
    }

    private fun blocked(reason: String): BusinessException {
        // 사용자가 입력한 URL 전체는 쿼리에 키가 들어 있을 수 있어 로그에 남기지 않는다.
        log.info("URL blocked by SSRF rule: {}", reason)
        return BusinessException(ErrorCode.URL_BLOCKED)
    }

    companion object {
        private const val DEFAULT_PORT = -1
        private val NUMERIC_HOST = Regex("^(0x[0-9a-f]*|[0-9]+)(\\.(0x[0-9a-f]*|[0-9]+))*\\.?$")
        private val CANONICAL_IPV4 =
            Regex("^((25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$")
    }
}
