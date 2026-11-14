package com.apimonitor.global.error

import com.apimonitor.global.response.ErrorDetail
import com.apimonitor.global.response.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.util.UUID

/**
 * 모든 예외를 공통 실패 응답 형식으로 바꾼다.
 * 내부 예외 메시지와 스택 트레이스는 응답에 넣지 않고, traceId와 함께 로그에만 남긴다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ErrorResponse> =
        respond(e.errorCode, message = e.message ?: e.errorCode.message)

    /** @RequestBody 검증 실패 */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleInvalidBody(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = e.bindingResult.fieldErrors.map {
            ErrorDetail(field = it.field, reason = it.defaultMessage ?: INVALID_VALUE)
        }
        return respond(ErrorCode.VALIDATION_FAILED, details = details)
    }

    /** @RequestParam, @PathVariable 검증 실패 */
    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleInvalidParameter(e: HandlerMethodValidationException): ResponseEntity<ErrorResponse> {
        val details = e.parameterValidationResults.flatMap { result ->
            val field = result.methodParameter.parameterName ?: "unknown"
            result.resolvableErrors.map { ErrorDetail(field = field, reason = it.defaultMessage ?: INVALID_VALUE) }
        }
        return respond(ErrorCode.VALIDATION_FAILED, details = details)
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.VALIDATION_FAILED, details = listOf(ErrorDetail(field = e.name, reason = "값의 형식이 올바르지 않습니다.")))

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParameter(e: MissingServletRequestParameterException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.VALIDATION_FAILED, details = listOf(ErrorDetail(field = e.parameterName, reason = "필수 값입니다.")))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.INVALID_REQUEST_BODY)

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(e: NoResourceFoundException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.RESOURCE_NOT_FOUND)

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(e: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.METHOD_NOT_ALLOWED)

    /** 처리하지 못한 예외. 원인은 로그에만 남긴다. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        val traceId = newTraceId()
        log.error("Unhandled exception traceId={}", traceId, e)
        return respond(ErrorCode.INTERNAL_ERROR, traceId = traceId)
    }

    private fun respond(
        errorCode: ErrorCode,
        message: String = errorCode.message,
        details: List<ErrorDetail> = emptyList(),
        traceId: String = newTraceId(),
    ): ResponseEntity<ErrorResponse> {
        if (!errorCode.status.is5xxServerError) {
            log.info("Request failed code={} traceId={}", errorCode, traceId)
        }
        return ResponseEntity.status(errorCode.status)
            .body(ErrorResponse.of(errorCode, message, details, traceId))
    }

    private fun newTraceId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    companion object {
        private const val INVALID_VALUE = "올바르지 않은 값입니다."
    }
}
