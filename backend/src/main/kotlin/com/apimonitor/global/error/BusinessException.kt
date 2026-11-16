package com.apimonitor.global.error

import com.apimonitor.global.response.ErrorDetail

/**
 * 서비스 규칙을 어겼을 때 던지는 예외. 전역 예외 처리기가 errorCode에 맞는 HTTP 상태와 응답으로 바꾼다.
 * 입력값 규칙 위반처럼 필드별 원인이 있으면 details에 담는다.
 */
open class BusinessException(
    val errorCode: ErrorCode,
    message: String = errorCode.message,
    val details: List<ErrorDetail> = emptyList(),
) : RuntimeException(message)
