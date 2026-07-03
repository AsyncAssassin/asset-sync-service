package com.example.assetsync.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/**
 * Wires the real HTTP chain provider used by every non-local/test profile (demo, e2e, prod).
 * Under `local`/`test` the fake provider is active instead, so this RestClient bean is not created
 * there and the mandatory `base-url` is not required.
 */
@Configuration
@EnableConfigurationProperties(ProviderProperties::class)
class ProviderConfiguration {

    @Bean
    @Profile("!local & !test")
    fun chainProviderRestClient(properties: ProviderProperties): RestClient {
        require(properties.baseUrl.isNotBlank()) {
            "asset-sync.provider.base-url must be set for the active profile."
        }
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(properties.connectTimeout)
            setReadTimeout(properties.readTimeout)
        }
        return RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(requestFactory)
            .build()
    }
}

@ConfigurationProperties(prefix = "asset-sync.provider")
data class ProviderProperties(
    val baseUrl: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
) {
    init {
        require(!connectTimeout.isNegative && !connectTimeout.isZero) {
            "asset-sync.provider.connect-timeout must be positive."
        }
        require(!readTimeout.isNegative && !readTimeout.isZero) {
            "asset-sync.provider.read-timeout must be positive."
        }
    }
}
