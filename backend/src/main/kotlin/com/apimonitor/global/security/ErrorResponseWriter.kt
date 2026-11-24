package com.apimonitor.global.security

import com.apimonitor.global.error.ErrorCode
import com.apimonitor.global.error.TraceIds
import com.apimonitor.global.response.ErrorResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Controller 밖(보안 필터)에서 발생한 오류를 공통 실패 응답 형식으로 쓴다.
 */
@Component
class ErrorResponseWriter(
    private val jsonMapper: JsonMapper,
) {
    fun write(response: HttpServletResponse, errorCode: ErrorCode) {
        response.status = errorCode.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        val body = ErrorResponse.of(errorCode, errorCode.message, emptyList(), TraceIds.current())
        response.writer.write(jsonMapper.writeValueAsString(body))
    }
}
