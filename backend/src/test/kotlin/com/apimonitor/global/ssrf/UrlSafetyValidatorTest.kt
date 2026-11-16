package com.apimonitor.global.ssrf

import com.apimonitor.global.error.BusinessException
import com.apimonitor.global.error.ErrorCode
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import java.net.UnknownHostException

class UrlSafetyValidatorTest {

    /** 실제 DNS 대신 정해 둔 응답을 돌려준다. IP 리터럴은 그대로 해석한다. */
    private val fakeDns = HostResolver { host ->
        when (host) {
            "public.example" -> listOf(ip("93.184.216.34"))
            "internal.example" -> listOf(ip("10.0.0.5"))
            "metadata.example" -> listOf(ip("169.254.169.254"))
            // 공인 IP와 내부 IP를 섞어 돌려주는 도메인
            "mixed.example" -> listOf(ip("93.184.216.34"), ip("127.0.0.1"))
            "v6-mapped.example" -> listOf(ip("::ffff:192.168.0.1"))
            else -> if (host.contains(':') || host.first().isDigit()) {
                InetAddress.getAllByName(host).toList()
            } else {
                throw UnknownHostException(host)
            }
        }
    }
    private val validator = UrlSafetyValidator(fakeDns)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://public.example/health",
            "http://public.example",
            "https://public.example:8443/status?key=value",
            "http://93.184.216.34:80/",
            "https://[2606:4700:4700::1111]/",
        ],
    )
    fun `공개된 주소는 통과한다`(url: String) {
        assertThatCode { validator.validate(url) }.doesNotThrowAnyException()
    }

    @Test
    fun `DNS 조회에 실패한 주소는 통과한다`() {
        assertThatCode { validator.validate("https://not-deployed-yet.example/health") }.doesNotThrowAnyException()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ftp://public.example/file",
            "file:///etc/passwd",
            "gopher://public.example",
            "https://user:pass@public.example/",
            "https://public.example:22/",
            "https://public.example:25/",
            "https:///no-host",
        ],
    )
    fun `허용하지 않는 스킴, 사용자 정보, 포트는 막는다`(url: String) {
        assertBlocked(url)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://127.0.0.1/",
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]/",
            "http://[::ffff:127.0.0.1]/",
            "http://10.0.0.1:8080/",
        ],
    )
    fun `내부 IP를 직접 쓴 주소는 막는다`(url: String) {
        assertBlocked(url)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://2130706433/", // 숫자 하나로 쓴 127.0.0.1
            "http://0x7f000001/", // 16진수로 쓴 127.0.0.1
            "http://0177.0.0.1/", // 8진수로 쓴 127.0.0.1
            "http://127.1/", // 줄여 쓴 127.0.0.1
        ],
    )
    fun `숫자로만 쓴 모호한 IP는 막는다`(url: String) {
        assertBlocked(url)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://internal.example/",
            "https://metadata.example/",
            "https://mixed.example/",
            "https://v6-mapped.example/",
        ],
    )
    fun `내부 IP로 연결되는 도메인은 막는다`(url: String) {
        assertBlocked(url)
    }

    private fun assertBlocked(url: String) {
        assertThatThrownBy { validator.validate(url) }
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.URL_BLOCKED)
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}
