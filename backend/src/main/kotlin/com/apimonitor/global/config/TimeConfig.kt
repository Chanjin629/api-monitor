package com.apimonitor.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

/**
 * 현재 시각은 Clock으로만 얻는다. 테스트에서 시각을 바꿔 만료 같은 상황을 재현할 수 있다.
 */
@Configuration
@EnableScheduling
class TimeConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
