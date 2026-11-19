package com.apimonitor.check.dto

import java.time.Instant

/** 검사 결과. 실행한 곳(같은 프로세스, Worker)과 상관없이 결과 처리에 같은 형태로 전달된다. */
data class CheckResult(
    val monitorId: Long,
    /** 검사를 시작한 시각 */
    val checkedAt: Instant,
    val type: CheckResultType,
    /** 응답을 받았을 때만 있다 */
    val statusCode: Int? = null,
    /** 요청 시작부터 응답 헤더 수신까지. 응답을 받았을 때만 있다 */
    val responseTimeMs: Int? = null,
)

/** 검사 결과의 종류 (docs/01-requirements.md 2.4) */
enum class CheckResultType {
    /** 기대한 상태 코드 */
    SUCCESS,

    /** 제한 시간 초과 */
    TIMEOUT,

    /** 연결 거부, 연결 끊김 */
    CONNECTION_ERROR,

    /** 도메인 조회 실패 */
    DNS_ERROR,

    /** HTTPS 인증서 오류 */
    TLS_ERROR,

    /** 기대하지 않은 상태 코드. Redirect(3xx)도 따라가지 않고 여기서 판정한다 */
    UNEXPECTED_STATUS,

    /** 연결하려던 IP가 차단 대역. 고객 장애가 아니므로 연속 실패에 넣지 않는다 */
    BLOCKED,
}
