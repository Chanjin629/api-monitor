package com.apimonitor.global.error

import org.springframework.http.HttpStatus

/**
 * 에러 코드 목록. 프론트엔드는 메시지 문구가 아니라 코드 이름으로 분기한다.
 * 기능별 코드(MONITOR_NOT_FOUND 등)는 해당 기능을 구현하는 단계에서 추가한다. 전체 목록: docs/05-api-spec.md
 */
enum class ErrorCode(
    val status: HttpStatus,
    val message: String,
) {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
}
