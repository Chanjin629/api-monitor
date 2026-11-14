package com.apimonitor.global.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Swagger UI(/swagger-ui.html)에 표시할 API 문서 정보.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("API Monitor")
            .description("API를 주기적으로 검사해 장애와 복구를 알려 주는 서비스의 API 문서")
            .version("v1"),
    )
}
