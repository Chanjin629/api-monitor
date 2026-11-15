package com.apimonitor.auth.service

import com.apimonitor.MutableClock
import com.apimonitor.TestcontainersConfiguration
import com.apimonitor.auth.dto.RefreshResult
import com.apimonitor.member.dto.GithubUser
import com.apimonitor.member.service.MemberService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class TokenServiceTest(
    @Autowired private val tokenService: TokenService,
    @Autowired private val memberService: MemberService,
    @Autowired private val clock: MutableClock,
) {

    @AfterEach
    fun resetClock() = clock.reset()

    @Test
    fun `재발급하면 Access Token과 새 Refresh Token을 받고 이전 토큰은 다시 쓸 수 없다`() {
        val first = tokenService.issueRefreshToken(newMemberId())

        val result = tokenService.refresh(first)

        assertThat(result).isInstanceOf(RefreshResult.Issued::class.java)
        val issued = result as RefreshResult.Issued
        assertThat(issued.refreshToken).isNotEqualTo(first)
        assertThat(issued.accessToken.expiresIn).isEqualTo(900)
        assertThat(tokenService.refresh(first)).isEqualTo(RefreshResult.Rejected)
    }

    @Test
    fun `이미 쓴 토큰이 10초 이내에 다시 오면 거절만 하고 새 토큰은 유지한다`() {
        val first = tokenService.issueRefreshToken(newMemberId())
        val second = (tokenService.refresh(first) as RefreshResult.Issued).refreshToken

        clock.advance(Duration.ofSeconds(5))
        assertThat(tokenService.refresh(first)).isEqualTo(RefreshResult.Rejected)

        assertThat(tokenService.refresh(second)).isInstanceOf(RefreshResult.Issued::class.java)
    }

    @Test
    fun `이미 쓴 토큰이 10초 뒤에 다시 오면 탈취로 보고 같은 묶음을 모두 무효화한다`() {
        val first = tokenService.issueRefreshToken(newMemberId())
        val second = (tokenService.refresh(first) as RefreshResult.Issued).refreshToken

        clock.advance(Duration.ofSeconds(11))
        assertThat(tokenService.refresh(first)).isEqualTo(RefreshResult.Rejected)

        assertThat(tokenService.refresh(second)).isEqualTo(RefreshResult.Rejected)
    }

    @Test
    fun `만료된 토큰은 거절한다`() {
        val token = tokenService.issueRefreshToken(newMemberId())

        clock.advance(Duration.ofDays(15))

        assertThat(tokenService.refresh(token)).isEqualTo(RefreshResult.Rejected)
    }

    @Test
    fun `로그아웃하면 그 로그인의 토큰은 무효화되고 다른 기기는 유지된다`() {
        val memberId = newMemberId()
        val thisDevice = tokenService.issueRefreshToken(memberId)
        val otherDevice = tokenService.issueRefreshToken(memberId)

        tokenService.revoke(thisDevice)

        assertThat(tokenService.refresh(thisDevice)).isEqualTo(RefreshResult.Rejected)
        assertThat(tokenService.refresh(otherDevice)).isInstanceOf(RefreshResult.Issued::class.java)
    }

    @Test
    fun `모든 기기 로그아웃은 회원의 토큰을 전부 무효화한다`() {
        val memberId = newMemberId()
        val a = tokenService.issueRefreshToken(memberId)
        val b = tokenService.issueRefreshToken(memberId)

        tokenService.revokeAll(memberId)

        assertThat(tokenService.refresh(a)).isEqualTo(RefreshResult.Rejected)
        assertThat(tokenService.refresh(b)).isEqualTo(RefreshResult.Rejected)
    }

    @Test
    fun `탈퇴한 회원의 토큰은 거절한다`() {
        val memberId = newMemberId()
        val token = tokenService.issueRefreshToken(memberId)

        memberService.withdraw(memberId)

        assertThat(tokenService.refresh(token)).isEqualTo(RefreshResult.Rejected)
    }

    @Test
    fun `없는 토큰은 거절한다`() {
        assertThat(tokenService.refresh("unknown-token")).isEqualTo(RefreshResult.Rejected)
    }

    private fun newMemberId(): Long {
        val id = System.nanoTime().toString()
        return requireNotNull(memberService.loginWithGithub(GithubUser(id, "user$id", null, null)).id)
    }
}
