package com.example.assetsync.config

import com.example.assetsync.api.error.ProblemDetailAccessDeniedHandler
import com.example.assetsync.api.error.ProblemDetailAuthenticationEntryPoint
import jakarta.servlet.DispatcherType
import java.time.Clock
import javax.sql.DataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.JdbcUserDetailsManager
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.security.web.SecurityFilterChain

/**
 * The methods that only read the API, which `READ` may call like `OPERATOR`; every other method on
 * the API needs `OPERATOR`. The OpenAPI document declares its 403 from the same set.
 */
val API_READ_METHODS: Set<HttpMethod> = setOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)

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
     * stay open. The simulator path is open only under `demo`, the one profile that serves the
     * in-process simulator; elsewhere it falls through to the authenticated default.
     * Error dispatches are permitted so a status the container renders through `/error`, such as
     * the 400 for a request StrictHttpFirewall rejected before authentication, is not replaced by
     * a 401 challenge; a direct request to `/error` still needs credentials.
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
        environment: Environment,
        userDetailsManager: UserDetailsManager,
        passwordEncoder: PasswordEncoder,
        userStoreCache: UserStoreCache,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                    ).permitAll()
                if (environment.matchesProfiles("demo")) {
                    it.requestMatchers("/simulator/**").permitAll()
                }
                API_READ_METHODS.forEach { method -> it.requestMatchers(method, "/api/**").hasAnyRole("READ", "OPERATOR") }
                it
                    // Any other method on the API (POST/PUT/PATCH/DELETE) changes state and requires OPERATOR.
                    .requestMatchers("/api/**").hasRole("OPERATOR")
                    .requestMatchers("/actuator/**").hasAnyRole("READ", "OPERATOR")
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint(authenticationEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            // The chain's own manager, so HTTP Basic goes through the user cache and nothing else.
            .authenticationManager(ProviderManager(UserStoreAuthenticationProvider(userDetailsManager, passwordEncoder, userStoreCache)))
            .httpBasic { it.authenticationEntryPoint(authenticationEntryPoint) }
            .build()

    @Bean
    @Profile("!local & !test")
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /** The users HTTP Basic has read, described in [UserStoreCache]. */
    @Bean
    @Profile("!local & !test")
    fun userStoreCache(clock: Clock): UserStoreCache = UserStoreCache(clock)

    @Bean
    @Profile("!local & !test")
    fun userDetailsManager(dataSource: DataSource, userStoreCache: UserStoreCache): UserDetailsManager =
        CachingUserDetailsManager(delegate = JdbcUserDetailsManager(dataSource), cache = userStoreCache)
}
