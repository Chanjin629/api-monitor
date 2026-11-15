package com.apimonitor.member.service

import com.apimonitor.global.error.BusinessException
import com.apimonitor.global.error.ErrorCode
import com.apimonitor.member.domain.AuthProvider
import com.apimonitor.member.domain.Member
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.dto.MemberResponse
import com.apimonitor.member.repository.MemberRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(readOnly = true)
class MemberService(
    private val memberRepository: MemberRepository,
    private val clock: Clock,
) {

    /** GitHub 로그인. 처음이면 회원을 만들고, 이미 있으면 프로필을 갱신한다. */
    @Transactional
    fun loginWithGithub(githubUser: GithubUser): Member {
        val now = clock.instant()
        val member = memberRepository.findByProviderAndProviderUserId(AuthProvider.GITHUB, githubUser.id)
            ?: memberRepository.save(Member.github(githubUser.id, githubUser.login, now))
        member.login(githubUser.login, githubUser.name, githubUser.avatarUrl, now)
        return member
    }

    fun getMe(memberId: Long): MemberResponse {
        // 탈퇴 직후 아직 만료되지 않은 Access Token으로 요청한 경우
        val member = memberRepository.findByIdOrNull(memberId)
            ?: throw BusinessException(ErrorCode.AUTH_TOKEN_INVALID)
        return MemberResponse.from(member)
    }

    fun getDemoMember(): Member =
        memberRepository.findByProviderAndProviderUserId(AuthProvider.DEMO, DEMO_PROVIDER_USER_ID)
            ?: error("데모 계정이 없습니다. Flyway V2 마이그레이션을 확인하세요.")

    /** 회원 탈퇴. 모니터, 토큰 등 관련 데이터는 DB의 ON DELETE CASCADE로 함께 삭제된다. */
    @Transactional
    fun withdraw(memberId: Long) {
        memberRepository.deleteById(memberId)
    }

    companion object {
        private const val DEMO_PROVIDER_USER_ID = "demo"
    }
}
