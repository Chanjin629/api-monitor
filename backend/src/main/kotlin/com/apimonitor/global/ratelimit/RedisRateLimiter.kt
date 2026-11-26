package com.apimonitor.global.ratelimit

import io.lettuce.core.ClientOptions
import io.micrometer.core.instrument.Counter
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * Redis 연결이 끊긴 동안 명령을 쌓아 두지 않고 바로 실패시킨다.
 * 기본 동작은 재연결을 기다리며 명령을 대기시켜, 장애 중 모든 API 요청이 응답 대기 시간(200ms)만큼 느려진다.
 */
@Configuration(proxyBeanMethods = false)
class RedisClientConfig {
    @Bean
    fun rejectWhileDisconnected(): LettuceClientOptionsBuilderCustomizer = LettuceClientOptionsBuilderCustomizer {
        it.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
    }
}

sealed interface RateLimitDecision {
    data object Allowed : RateLimitDecision

    /** 한도를 넘었다. [retryAfterSeconds]는 다음 1분 칸이 시작될 때까지 남은 초(1~60)다. */
    data class Rejected(val retryAfterSeconds: Long) : RateLimitDecision
}

/**
 * Redis에서 1분 단위 고정 창(fixed window)으로 요청 수를 센다. (docs/02-architecture.md 6.1)
 *
 * - 키 rl:{규칙}:{대상}:{분 번호}를 INCR로 1씩 올리고, 첫 요청이면 60초 뒤 사라지게 한다.
 *   두 명령을 Lua 스크립트 하나로 실행해, 중간에 끊겨 만료 없이 남는 키가 생기지 않게 한다.
 * - Redis에 연결하지 못하면 요청을 통과시킨다(fail-open). 요청 제한 때문에 서비스가 멈추면 안 된다.
 */
@Component
class RedisRateLimiter(
    private val redisTemplate: StringRedisTemplate,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    private val errorCounter: Counter = Counter.builder("apimonitor.ratelimit.errors")
        .description("Redis 오류로 요청 제한을 건너뛴 횟수")
        .register(meterRegistry)

    /** Redis 장애 동안 요청마다 경고 로그가 쌓이지 않도록, 경고는 1분에 한 번만 남긴다. */
    private val lastWarnAt = AtomicLong(0)

    fun tryAcquire(target: RateLimitTarget): RateLimitDecision {
        val nowSeconds = clock.instant().epochSecond
        val window = nowSeconds / WINDOW_SECONDS
        val key = "rl:${target.rule.id}:${target.subject}:$window"

        val count = try {
            redisTemplate.execute(INCREMENT_SCRIPT, listOf(key), WINDOW_SECONDS.toString())
        } catch (e: RuntimeException) {
            // 연결 실패, 응답 대기 초과 등. 어떤 오류든 요청은 통과시킨다.
            errorCounter.increment()
            warnOncePerMinute(nowSeconds, e)
            return RateLimitDecision.Allowed
        }

        if (count == null || count <= target.rule.limitPerMinute) {
            return RateLimitDecision.Allowed
        }
        meterRegistry.counter("apimonitor.ratelimit.rejected", "rule", target.rule.id).increment()
        return RateLimitDecision.Rejected(WINDOW_SECONDS - nowSeconds % WINDOW_SECONDS)
    }

    private fun warnOncePerMinute(nowSeconds: Long, e: RuntimeException) {
        val last = lastWarnAt.get()
        if (nowSeconds - last >= WINDOW_SECONDS && lastWarnAt.compareAndSet(last, nowSeconds)) {
            log.warn("Rate limit skipped because Redis is unavailable: {}", e.javaClass.simpleName)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisRateLimiter::class.java)
        private const val WINDOW_SECONDS = 60L

        private val INCREMENT_SCRIPT: RedisScript<Long> = RedisScript.of(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """.trimIndent(),
            Long::class.java,
        )
    }
}
