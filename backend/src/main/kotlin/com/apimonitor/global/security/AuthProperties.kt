package com.apimonitor.global.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** application.yml의 auth 설정 */
@ConfigurationProperties("auth")
data class AuthProperties(
    val jwt: Jwt,
    val refreshTokenTtl: Duration,
    val cookie: Cookie,
) {
    data class Jwt(
        /** Base64로 인코딩한 32바이트 이상의 서명 키 */
        val secret: String,
        val issuer: String,
        val accessTokenTtl: Duration,
        val demoTokenTtl: Duration,
    )

    data class Cookie(
        val secure: Boolean,
    )
}
