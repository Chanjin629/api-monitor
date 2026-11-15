package com.apimonitor.member.controller

import com.apimonitor.auth.service.CookieUtil
import com.apimonitor.global.response.ApiResponse
import com.apimonitor.global.security.AuthMember
import com.apimonitor.member.dto.MemberResponse
import com.apimonitor.member.service.MemberService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "회원")
@RestController
@RequestMapping("/api/v1/members")
class MemberController(
    private val memberService: MemberService,
    private val cookieUtil: CookieUtil,
) {

    @Operation(summary = "내 정보 조회")
    @GetMapping("/me")
    fun me(@Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember): ApiResponse<MemberResponse> =
        ApiResponse.ok(memberService.getMe(member.id))

    @Operation(summary = "회원 탈퇴", description = "모니터와 장애 이력 등 모든 데이터를 삭제한다. 데모 계정은 사용할 수 없다.")
    @DeleteMapping("/me")
    fun withdraw(@Parameter(hidden = true) @AuthenticationPrincipal member: AuthMember): ResponseEntity<ApiResponse<Nothing>> {
        memberService.withdraw(member.id)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookieUtil.deleteRefreshTokenCookie().toString())
            .body(ApiResponse.ok())
    }
}
