package com.apimonitor.global.error

import org.slf4j.MDC
import java.util.UUID

/**
 * 에러 응답과 로그를 연결하는 추적 ID.
 * 요청마다 [TraceIdFilter]가 MDC에 넣어 두므로, 같은 요청의 모든 로그와 오류 응답이 같은 값을 쓴다.
 */
object TraceIds {
    const val MDC_KEY = "traceId"

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /** 현재 요청의 traceId. 요청 밖(주기 작업 등)에서는 새로 만든다. */
    fun current(): String = MDC.get(MDC_KEY) ?: newId()
}
