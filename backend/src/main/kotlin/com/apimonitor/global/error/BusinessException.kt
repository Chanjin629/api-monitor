package com.apimonitor.global.error

/**
 * 서비스 규칙을 어겼을 때 던지는 예외. 전역 예외 처리기가 errorCode에 맞는 HTTP 상태와 응답으로 바꾼다.
 */
open class BusinessException(
    val errorCode: ErrorCode,
    message: String = errorCode.message,
) : RuntimeException(message)
