package com.apimonitor.global.error

import com.apimonitor.global.response.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@WebMvcTest(controllers = [GlobalExceptionHandlerTest.ErrorTestController::class])
@Import(GlobalExceptionHandlerTest.ErrorTestController::class)
class GlobalExceptionHandlerTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `성공 응답은 success와 data를 담는다`() {
        mockMvc.get("/test/errors/ok").andExpect {
            status { isOk() }
            content { string("""{"success":true,"data":{"value":1}}""") }
        }
    }

    @Test
    fun `데이터가 없는 성공 응답은 data가 null이다`() {
        mockMvc.post("/test/errors/empty").andExpect {
            status { isOk() }
            content { string("""{"success":true,"data":null}""") }
        }
    }

    @Test
    fun `입력값 검증에 실패하면 400과 필드별 상세를 반환한다`() {
        mockMvc.post("/test/errors/validation") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"","count":0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.error.code") { value("VALIDATION_FAILED") }
            jsonPath("$.error.details.length()") { value(2) }
            jsonPath("$.error.details[?(@.field == 'name')]") { exists() }
            jsonPath("$.error.details[?(@.field == 'count')]") { exists() }
            jsonPath("$.error.traceId") { isNotEmpty() }
        }
    }

    @Test
    fun `JSON 형식이 잘못되면 INVALID_REQUEST_BODY를 반환한다`() {
        mockMvc.post("/test/errors/validation") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_REQUEST_BODY") }
        }
    }

    @Test
    fun `비즈니스 예외는 에러 코드의 상태와 예외 메시지를 반환한다`() {
        mockMvc.get("/test/errors/business").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_FAILED") }
            jsonPath("$.error.message") { value("테스트용 비즈니스 예외") }
        }
    }

    @Test
    fun `예상하지 못한 예외는 500을 반환하고 내부 메시지를 노출하지 않는다`() {
        mockMvc.get("/test/errors/unexpected").andExpect {
            status { isInternalServerError() }
            jsonPath("$.error.code") { value("INTERNAL_ERROR") }
            content { string(not(containsString("secret-detail"))) }
        }
    }

    @Test
    fun `없는 주소는 404 RESOURCE_NOT_FOUND를 반환한다`() {
        mockMvc.get("/test/errors/not-exists").andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("RESOURCE_NOT_FOUND") }
        }
    }

    @Test
    fun `지원하지 않는 메서드는 405 METHOD_NOT_ALLOWED를 반환한다`() {
        mockMvc.get("/test/errors/empty").andExpect {
            status { isMethodNotAllowed() }
            jsonPath("$.error.code") { value("METHOD_NOT_ALLOWED") }
        }
    }

    /** 예외 처리기를 검증하기 위한 테스트 전용 Controller */
    @RestController
    @RequestMapping("/test/errors")
    class ErrorTestController {

        data class SampleRequest(
            @field:NotBlank val name: String?,
            @field:Min(1) val count: Int?,
        )

        @GetMapping("/ok")
        fun ok() = ApiResponse.ok(mapOf("value" to 1))

        @PostMapping("/empty")
        fun empty() = ApiResponse.ok()

        @PostMapping("/validation")
        fun validation(@Valid @RequestBody request: SampleRequest) = ApiResponse.ok(request.name)

        @GetMapping("/business")
        fun business(): ApiResponse<Nothing> = throw BusinessException(ErrorCode.VALIDATION_FAILED, "테스트용 비즈니스 예외")

        @GetMapping("/unexpected")
        fun unexpected(): ApiResponse<Nothing> = throw IllegalStateException("secret-detail")
    }
}
