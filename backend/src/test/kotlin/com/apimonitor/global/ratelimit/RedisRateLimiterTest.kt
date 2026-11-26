package com.apimonitor.global.ratelimit

import com.apimonitor.MutableClock
import com.apimonitor.TestcontainersConfiguration
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 실제 Redis 컨테이너로 1분 고정 창 횟수 세기를 검증한다.
 * 테스트끼리 같은 Redis를 쓰므로 대상(subject)은 매번 새로 만든다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class RedisRateLimiterTest(
    @Autowired private val rateLimiter: RedisRateLimiter,
    @Autowired private val redisTemplate: StringRedisTemplate,
    @Autowired private val clock: MutableClock,
) {

    @AfterEach
    fun resetClock() = clock.reset()

    @Test
    fun `한도까지는 통과하고, 넘으면 다음 분까지 남은 초와 함께 거절한다`() {
        // 분의 15초 지점이면 다음 분까지 45초 남았다.
        clock.fixAt(Instant.parse("2026-11-26T10:00:15Z"))
        val target = newTarget(RateLimitRule.NOTIFICATION_TEST)

        repeat(5) { assertThat(rateLimiter.tryAcquire(target)).isEqualTo(RateLimitDecision.Allowed) }

        assertThat(rateLimiter.tryAcquire(target)).isEqualTo(RateLimitDecision.Rejected(45))
    }

    @Test
    fun `다음 1분 칸이 되면 다시 통과한다`() {
        clock.fixAt(Instant.parse("2026-11-26T10:00:59Z"))
        val target = newTarget(RateLimitRule.NOTIFICATION_TEST)
        repeat(5) { rateLimiter.tryAcquire(target) }
        assertThat(rateLimiter.tryAcquire(target)).isEqualTo(RateLimitDecision.Rejected(1))

        clock.advance(Duration.ofSeconds(1))

        assertThat(rateLimiter.tryAcquire(target)).isEqualTo(RateLimitDecision.Allowed)
    }

    @Test
    fun `횟수 키에는 60초 만료가 걸려 쌓이지 않는다`() {
        clock.fixAt(Instant.parse("2026-11-26T10:00:00Z"))
        val target = newTarget(RateLimitRule.API)

        rateLimiter.tryAcquire(target)

        val window = clock.instant().epochSecond / 60
        val key = "rl:api:${target.subject}:$window"
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("1")
        assertThat(redisTemplate.getExpire(key)).isBetween(1L, 60L)
    }

    @Test
    fun `규칙과 대상이 다르면 따로 센다`() {
        clock.fixAt(Instant.parse("2026-11-26T10:00:00Z"))
        val first = newTarget(RateLimitRule.NOTIFICATION_TEST)
        repeat(6) { rateLimiter.tryAcquire(first) }
        assertThat(rateLimiter.tryAcquire(first)).isInstanceOf(RateLimitDecision.Rejected::class.java)

        val otherSubject = newTarget(RateLimitRule.NOTIFICATION_TEST)
        val otherRule = RateLimitTarget(RateLimitRule.DEMO_LOGIN, first.subject)

        assertThat(rateLimiter.tryAcquire(otherSubject)).isEqualTo(RateLimitDecision.Allowed)
        assertThat(rateLimiter.tryAcquire(otherRule)).isEqualTo(RateLimitDecision.Allowed)
    }

    @Test
    fun `Redis에 연결하지 못하면 요청을 통과시키고 오류 수를 센다`() {
        val brokenRedis = mockk<StringRedisTemplate>()
        every { brokenRedis.execute(any<RedisScript<Long>>(), any<List<String>>(), *anyVararg()) } throws
            RedisConnectionFailureException("connection refused")
        val meterRegistry = SimpleMeterRegistry()
        val limiter = RedisRateLimiter(brokenRedis, Clock.systemUTC(), meterRegistry)
        val target = newTarget(RateLimitRule.DEMO_LOGIN)

        repeat(20) { assertThat(limiter.tryAcquire(target)).isEqualTo(RateLimitDecision.Allowed) }

        assertThat(meterRegistry.counter("apimonitor.ratelimit.errors").count()).isEqualTo(20.0)
    }

    private fun newTarget(rule: RateLimitRule) = RateLimitTarget(rule, "test:${UUID.randomUUID()}")
}
