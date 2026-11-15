package com.apimonitor.auth.controller

import com.apimonitor.auth.dto.AccessTokenResponse
import com.apimonitor.auth.dto.RefreshResult
import com.apimonitor.auth.service.CookieUtil
import com.apimonitor.auth.service.TokenService
import com.apimonitor.global.error.BusinessException
import com.apimonitor.global.error.ErrorCode
import com.apimonitor.global.response.ApiResponse
import com.apimonitor.global.security.AuthMember
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인 유지와 로그아웃. GitHub 로그인 시작은 /oauth2/authorization/github(Spring Security)가 맡는다.
 */
@Tag(name = "인증")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val tokenService: TokenService,
    private val cookieUtil: CookieUtil,
) {

    @Operation(
        summary = "Access Token 재발급",
        description = "refreshToken 쿠키로 Access Token을 재발급하고, Refresh Token을 새것으로 교체한다.",
    )
    @SecurityRequirements
    @PostMapping("/refresh")
    fun refresh(
        @Parameter(hidden = true) @CookieValue(CookieUtil.REFRESH_TOKEN_COOKIE, required = false) refreshToken: String?,
    ): ResponseEntity<ApiResponse<AccessTokenResponse>> {
        val result = refreshToken?.let(tokenService::refresh) ?: RefreshResult.Rejected
        // 거절해도 쿠키는 지우지 않는다. 다른 탭이 방금 새 토큰으로 교체한 쿠키일 수 있다.
        if (result !is RefreshResult.Issued) throw BusinessException(ErrorCode.AUTH_REFRESH_INVALID)

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookieUtil.createRefreshTokenCookie(result.refreshToken).toString())
            .body(ApiResponse.ok(result.accessToken))
    }

    @Operation(summary = "로그아웃", description = "현재 기기의 Refresh Token을 무효화하고 쿠키를 삭제한다.")
    @SecurityRequirements
    @PostMapping("/logout")
    fun logout(
        @Parameter(hidden = true) @CookieValue(CookieUtil.REFRESH_TOKEN_COOKIE, required = false) refreshToken: String?,
    ): ResponseEntity<ApiResponse<Nothing>> {
        refreshToken?.let(tokenService::revoke)
        return deleteCookieResponse()
    }

    @Operation(summary = "모든 기기 로그아웃")
    @PostMapping("/logout-all")
    fun logoutAll(@Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember): ResponseEntity<ApiResponse<Nothing>> {
        tokenService.revokeAll(member.id)
        return deleteCookieResponse()
    }

    @Operation(summary = "데모 로그인", description = "읽기 전용 데모 계정의 Access Token(60분)을 발급한다. Refresh Token은 없다.")
    @SecurityRequirements
    @PostMapping("/demo")
    fun demo(): ApiResponse<AccessTokenResponse> = ApiResponse.ok(tokenService.issueDemoAccessToken())

    private fun deleteCookieResponse(): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookieUtil.deleteRefreshTokenCookie().toString())
            .body(ApiResponse.ok())
}
