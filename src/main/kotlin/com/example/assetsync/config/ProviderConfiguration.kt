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
 * Wires the HTTP bridge chain provider: the default `asset-sync.provider.type=http` for every
 * non-local/test profile (demo, e2e, prod). Under `local`/`test` the fake provider is active, and
 * under `type=alchemy` the beans in `AlchemyProviderConfiguration` replace it; in both cases this
 * RestClient bean is not created and the mandatory `base-url` is not required.
 */
@Configuration
@EnableConfigurationProperties(ProviderProperties::class)
class ProviderConfiguration {

    @Bean
    @Profile("!local & !test")
    @ConditionalOnHttpChainProvider
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

/** Which `ChainProviderPort` implementation serves non-local/test profiles. */
enum class ProviderType { HTTP, ALCHEMY }

@ConfigurationProperties(prefix = "asset-sync.provider")
data class ProviderProperties(
    /** Selector for the provider beans; the raw property value also drives the bean conditions. */
    val type: ProviderType = ProviderType.HTTP,
    /** HTTP bridge endpoint; required only when [type] is [ProviderType.HTTP]. */
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
