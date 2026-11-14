package com.apimonitor.global.response

import com.apimonitor.global.error.ErrorCode

/**
 * 실패 응답 형식. 예: {"success": false, "error": {"code": "...", "message": "...", "details": [], "traceId": "..."}}
 */
data class ErrorResponse(
    val success: Boolean,
    val error: ErrorBody,
) {
    companion object {
        fun of(
            errorCode: ErrorCode,
            message: String,
            details: List<ErrorDetail>,
            traceId: String,
        ): ErrorResponse = ErrorResponse(
            success = false,
            error = ErrorBody(code = errorCode.name, message = message, details = details, traceId = traceId),
        )
    }
}

data class ErrorBody(
    val code: String,
    val message: String,
    val details: List<ErrorDetail>,
    /** 사용자가 문의할 때 서버 로그를 찾기 위한 값 */
    val traceId: String,
)

data class ErrorDetail(
    val field: String,
    val reason: String,
)
