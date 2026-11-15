package com.apimonitor.global.security

import com.apimonitor.auth.oauth.OAuth2LoginFailureHandler
import com.apimonitor.auth.oauth.OAuth2LoginSuccessHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository

/**
 * 보안 설정을 두 갈래로 나눈다. (docs/02-architecture.md 2절)
 *
 * 1. OAuth 로그인 구간: GitHub에 다녀오는 동안 state를 보관하려고 세션을 잠깐 쓴다.
 * 2. 그 외 API: 세션 없이 Access Token(JWT)으로만 인증한다.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtTokenProvider: JwtTokenProvider,
    private val authenticationHandler: JwtAuthenticationHandler,
    private val authorizationHandler: JwtAuthorizationHandler,
    private val loginSuccessHandler: OAuth2LoginSuccessHandler,
    private val loginFailureHandler: OAuth2LoginFailureHandler,
) {

    @Bean
    @Order(1)
    fun oauth2LoginFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/oauth2/**", "/login/oauth2/**")
            // GET 요청과 GitHub 리다이렉트만 처리하며, CSRF 방어는 state 검증이 맡는다.
            .csrf { it.disable() }
            // 로그인 결과는 세션에 저장하지 않는다. 성공 처리기가 Refresh Token 쿠키를 대신 발급한다.
            .securityContext { it.securityContextRepository(RequestAttributeSecurityContextRepository()) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .oauth2Login {
                it.successHandler(loginSuccessHandler)
                it.failureHandler(loginFailureHandler)
            }
        return http.build()
    }

    @Bean
    @Order(2)
    fun apiFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(JwtTokenFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling {
                it.authenticationEntryPoint(authenticationHandler)
                it.accessDeniedHandler(authorizationHandler)
            }
            .authorizeHttpRequests {
                it.requestMatchers("/error", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/demo")
                    .permitAll()
                // 조회는 로그인한 모든 사용자, 변경은 일반 회원만 할 수 있다. 데모 계정의 변경 요청은 403이 된다.
                it.requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                it.requestMatchers("/api/**").hasRole("USER")
                it.anyRequest().denyAll()
            }
        return http.build()
    }
}
