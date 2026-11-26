package com.apimonitor.global.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Swagger UI(/swagger-ui.html)에 표시할 API 문서 정보.
 * Authorize 버튼에 Access Token을 넣으면 로그인이 필요한 API를 호출할 수 있다.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("API Monitor")
                .description(
                    "API를 주기적으로 검사해 장애와 복구를 알려 주는 서비스의 API 문서\n\n" +
                        "모든 API에 요청 제한이 있다. 한도를 넘으면 `429 RATE_LIMITED`와 `Retry-After`(초) 헤더로 응답한다. " +
                        "데모 로그인·GitHub 로그인 시작은 IP당 분당 10회, 토큰 재발급 30회, 알림 테스트 발송은 회원당 5회, " +
                        "그 밖의 API는 회원당(데모·비로그인은 IP당) 300회다.",
                )
                .version("v1"),
        )
        .components(
            Components().addSecuritySchemes(
                BEARER_AUTH,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("POST /api/v1/auth/demo 또는 /api/v1/auth/refresh로 받은 accessToken"),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER_AUTH))

    companion object {
        const val BEARER_AUTH = "bearerAuth"
    }
}
