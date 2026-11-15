package com.apimonitor.global.error

import java.util.UUID

/** 에러 응답과 로그를 연결하는 추적 ID */
object TraceIds {
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
}
