package com.apimonitor.auth.dto

/** Access Token 응답. expiresIn은 초 단위다. */
data class AccessTokenResponse(
    val accessToken: String,
    val expiresIn: Long,
)

/** 재발급 결과. 거절은 예외 대신 값으로 돌려줘 재사용 감지로 무효화한 내용이 롤백되지 않게 한다. */
sealed interface RefreshResult {
    data class Issued(
        val accessToken: AccessTokenResponse,
        val refreshToken: String,
    ) : RefreshResult

    data object Rejected : RefreshResult
}
