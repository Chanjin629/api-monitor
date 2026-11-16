package com.apimonitor.monitor.dto

import com.apimonitor.monitor.domain.MonitorHttpMethod
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

// 필드를 null 허용으로 두는 이유: 값이 빠졌을 때 JSON 변환 오류(INVALID_REQUEST_BODY) 대신
// 어떤 필드가 문제인지 알려 주는 VALIDATION_FAILED로 응답하기 위해서다.

data class CreateMonitorRequest(
    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(example = "결제 API")
    val name: String?,

    @field:NotBlank
    @field:Size(max = 2048)
    @field:Schema(example = "https://api.example.com/health")
    val url: String?,

    @field:Schema(description = "기본 GET", example = "GET")
    val method: MonitorHttpMethod? = null,

    @field:NotNull
    @field:Schema(description = "1, 5, 10, 30, 60 중 하나", example = "5")
    val intervalMinutes: Int?,

    @field:Min(1)
    @field:Max(30)
    @field:Schema(description = "기본 10", example = "10")
    val timeoutSeconds: Int? = null,

    @field:Size(max = 5)
    @field:Schema(description = "100~599, 최대 5개. 비우면 2xx 전체", example = "[200]")
    val expectedStatusCodes: List<Int>? = null,

    @field:Size(max = 10)
    @field:Valid
    val headers: List<HeaderRequest>? = null,
)

/** 보낸 필드만 바꾼다. headers는 보내면 전체를 교체하고, 빈 배열이면 모두 지운다. */
data class UpdateMonitorRequest(
    @field:Size(min = 1, max = 100)
    @field:Pattern(regexp = ".*\\S.*", message = "공백만 입력할 수 없습니다.")
    val name: String? = null,

    @field:Size(min = 1, max = 2048)
    val url: String? = null,

    val method: MonitorHttpMethod? = null,

    @field:Schema(description = "1, 5, 10, 30, 60 중 하나")
    val intervalMinutes: Int? = null,

    @field:Min(1)
    @field:Max(30)
    val timeoutSeconds: Int? = null,

    @field:Size(max = 5)
    @field:Schema(description = "빈 배열이면 2xx 전체")
    val expectedStatusCodes: List<Int>? = null,

    @field:Size(max = 10)
    @field:Valid
    val headers: List<HeaderRequest>? = null,
)

data class HeaderRequest(
    // HTTP 헤더 이름에 쓸 수 있는 문자만 허용한다 (RFC 9110 token).
    @field:NotBlank
    @field:Size(max = 100)
    @field:Pattern(regexp = "^[!#$%&'*+.^_`|~0-9A-Za-z-]+$", message = "헤더 이름 형식이 올바르지 않습니다.")
    @field:Schema(example = "Authorization")
    val name: String?,

    // 줄바꿈이 들어가면 다른 헤더를 끼워 넣을 수 있으므로 막는다.
    @field:NotNull
    @field:Size(max = 1000)
    @field:Pattern(regexp = "^[^\\r\\n]*$", message = "줄바꿈은 넣을 수 없습니다.")
    @field:Schema(example = "Bearer secret-token")
    val value: String?,
)
