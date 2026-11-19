package com.apimonitor.check.dto

/**
 * 검사 한 번에 필요한 값. monitor의 엔티티 대신 이 값만 주고받아,
 * 나중에 검사를 다른 프로세스(Kafka Worker)로 옮겨도 그대로 보낼 수 있게 한다.
 */
data class CheckCommand(
    val monitorId: Long,
    val url: String,
    /** GET 또는 HEAD */
    val method: String,
    val timeoutSeconds: Int,
    /** 비어 있으면 2xx 전체가 정상 */
    val expectedStatusCodes: List<Int>,
    /** 복호화한 요청 헤더. 인증 토큰이 들어 있을 수 있다. */
    val headers: List<Header>,
) {
    fun isExpected(statusCode: Int): Boolean =
        if (expectedStatusCodes.isEmpty()) statusCode in 200..299 else statusCode in expectedStatusCodes

    data class Header(val name: String, val value: String)

    // 로그에 찍혀도 URL의 쿼리 키나 헤더 값이 드러나지 않게 한다.
    override fun toString(): String = "CheckCommand(monitorId=$monitorId, method=$method, timeoutSeconds=$timeoutSeconds)"
}
