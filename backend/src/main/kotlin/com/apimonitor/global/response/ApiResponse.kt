package com.apimonitor.global.response

/**
 * 성공 응답 형식. 예: {"success": true, "data": {...}}
 * 응답할 데이터가 없으면 data는 null로 내려간다.
 */
data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
) {
    companion object {
        fun <T> ok(data: T): ApiResponse<T> = ApiResponse(success = true, data = data)

        fun ok(): ApiResponse<Nothing> = ApiResponse(success = true, data = null)
    }
}
