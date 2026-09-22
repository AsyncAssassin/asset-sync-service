package com.example.assetsync.config

import com.example.assetsync.api.error.ProblemDetailAccessDeniedHandler
import com.example.assetsync.api.error.ProblemDetailAuthenticationEntryPoint
import javax.sql.DataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.JdbcUserDetailsManager
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfiguration {

    /** local/test: fully permissive so the existing MVP tests and local runs stay friction-free. */
    @Bean
    @Profile("local", "test")
    fun localSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()

    /**
     * Every non-local/test profile (demo, e2e, prod): HTTP Basic against the DB-backed user store,
     * with role-scoped access. Reads need READ or OPERATOR; mutations need OPERATOR. Health probes
     * stay open; the simulator path is open because it is a demo-only in-process aid (absent in prod).
     * CSRF is disabled deliberately — this is a stateless API with no browser session/cookie auth.
     * 401 and 403 are written as ProblemDetail by the entry point and access-denied handler from
     * `api.error`, so security failures share the error shape of the API layer.
     */
    @Bean
    @Profile("!local & !test")
    fun protectedSecurityFilterChain(
        http: HttpSecurity,
        authenticationEntryPoint: ProblemDetailAuthenticationEntryPoint,
        accessDeniedHandler: ProblemDetailAccessDeniedHandler,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                    ).permitAll()
                    .requestMatchers("/simulator/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("READ", "OPERATOR")
                    // Any mutating method (POST/PUT/PATCH/DELETE) on the API requires OPERATOR.
                    .requestMatchers("/api/**").hasRole("OPERATOR")
                    .requestMatchers("/actuator/**").hasAnyRole("READ", "OPERATOR")
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint(authenticationEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .httpBasic { it.authenticationEntryPoint(authenticationEntryPoint) }
            .build()

    @Bean
    @Profile("!local & !test")
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    @Profile("!local & !test")
    fun userDetailsManager(dataSource: DataSource): UserDetailsManager =
        JdbcUserDetailsManager(dataSource)
}
