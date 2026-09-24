package com.example.assetsync.config

import com.example.assetsync.infrastructure.provider.ProviderHttpSupport
import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
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
        // The HTTP client never sends a user name or password from the URL, so credentials there
        // would only look configured; the message leaves the URL out, because it holds them.
        require(runCatching { URI(properties.baseUrl).rawUserInfo }.getOrNull() == null) {
            "asset-sync.provider.base-url must not carry a user name or password: the HTTP client never sends them."
        }
        return RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(ProviderHttpSupport.requestFactory(properties.connectTimeout, properties.readTimeout))
            // RestClient.Builder.apply(Consumer), not Kotlin's apply: the builder is `it`.
            .apply {
                if (properties.authHeaderValue.isNotEmpty()) {
                    it.defaultHeader(properties.effectiveAuthHeaderName, properties.authHeaderValue)
                }
            }
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
    /** Header that carries the bridge credential, such as `Authorization` or `X-API-Key`. */
    val authHeaderName: String = DEFAULT_AUTH_HEADER_NAME,
    /**
     * The bridge credential, sent in [authHeaderName] on every bridge request when set. A secret:
     * no message, log line, or health detail quotes it, and [toString] masks it.
     */
    val authHeaderValue: String = "",
) {
    init {
        require(!connectTimeout.isNegative && !connectTimeout.isZero) {
            "asset-sync.provider.connect-timeout must be positive."
        }
        require(!readTimeout.isNegative && !readTimeout.isZero) {
            "asset-sync.provider.read-timeout must be positive."
        }
        // Checked only with a credential to send; a blank name, as an empty variable gives, is the default.
        require(authHeaderValue.isEmpty() || effectiveAuthHeaderName.all { it in HEADER_NAME_CHARACTERS }) {
            "asset-sync.provider.auth-header-name must be an HTTP header name."
        }
        // The message leaves the value out: it is the credential.
        require(authHeaderValue.none { it == '\r' || it == '\n' }) {
            "asset-sync.provider.auth-header-value must not contain a line break."
        }
    }

    /** The header the credential goes in: [authHeaderName], or `Authorization` when it is blank. */
    val effectiveAuthHeaderName: String
        get() = authHeaderName.ifBlank { DEFAULT_AUTH_HEADER_NAME }

    override fun toString(): String =
        "ProviderProperties(type=$type, baseUrl=${if (baseUrl.isBlank()) "<unset>" else "<set>"}, " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout, authHeaderName=$effectiveAuthHeaderName, " +
            "authHeaderValue=${if (authHeaderValue.isEmpty()) "<unset>" else "***"})"

    private companion object {
        const val DEFAULT_AUTH_HEADER_NAME = "Authorization"

        /** RFC 9110 token characters, which a header name consists of. */
        val HEADER_NAME_CHARACTERS: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "!#$%&'*+-.^_`|~".toList()).toSet()
    }
}
