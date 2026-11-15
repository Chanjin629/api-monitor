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
                .description("API를 주기적으로 검사해 장애와 복구를 알려 주는 서비스의 API 문서")
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
