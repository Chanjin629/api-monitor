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

    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다."),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증 정보입니다."),
    AUTH_REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
    DEMO_READ_ONLY(HttpStatus.FORBIDDEN, "데모에서는 변경할 수 없습니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "권한이 없습니다."),

    MONITOR_NOT_FOUND(HttpStatus.NOT_FOUND, "모니터를 찾을 수 없습니다."),
    MONITOR_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "모니터는 20개까지 등록할 수 있습니다."),
    MONITOR_ALREADY_PAUSED(HttpStatus.CONFLICT, "이미 일시 정지된 모니터입니다."),
    MONITOR_NOT_PAUSED(HttpStatus.CONFLICT, "일시 정지 상태가 아닌 모니터입니다."),
    URL_BLOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "검사할 수 없는 URL입니다. 공개된 주소인지 확인해 주세요."),
    HEADER_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "지정할 수 없는 요청 헤더입니다."),

    CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "알림 채널을 찾을 수 없습니다."),
    CHANNEL_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "알림 채널은 3개까지 등록할 수 있습니다."),
    WEBHOOK_URL_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Discord Webhook 주소를 입력해 주세요."),
    NOTIFICATION_SEND_FAILED(HttpStatus.BAD_GATEWAY, "테스트 메시지를 보내지 못했습니다. 주소를 확인해 주세요."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
}
