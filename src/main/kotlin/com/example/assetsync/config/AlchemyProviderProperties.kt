package com.example.assetsync.config

import com.example.assetsync.application.sync.ProviderConfigurationException
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `asset-sync.provider.alchemy.*`: bound only when `asset-sync.provider.type=alchemy` (the
 * configuration class that enables it carries that condition), so an HTTP deployment never has to
 * satisfy Alchemy rules. The API key is a secret: it has no default, it is redacted from
 * `toString`, and every message built from these values goes through `AlchemySecretScrubber`.
 *
 * Templates default in code rather than in YAML because `{network}` braces cannot live inside a
 * `${ENV:default}` placeholder; a blank bound value means "use the default".
 */
@ConfigurationProperties(prefix = "asset-sync.provider.alchemy")
class AlchemyProviderProperties(
    apiKey: String = "",
    val authMode: AlchemyAuthMode = AlchemyAuthMode.HEADER,
    endpointTemplate: String = "",
    pathEndpointTemplate: String = "",
    val startMode: AlchemyStartMode = AlchemyStartMode.REGISTRATION_SAFE,
    val networks: Map<String, AlchemyNetworkProperties> = emptyMap(),
    val finalityMode: AlchemyFinalityMode = AlchemyFinalityMode.SAFE,
    val finalityDepthFallback: Long = 64,
    val maxWindowBlocks: Int = 5000,
    val maxRpcCallsPerFetch: Int = 8,
    val rateLimitCapacity: Int = 6,
    val rateLimitRefillPerSecond: Double = 3.0,
) {
    val apiKey: String = apiKey.trim()
    val endpointTemplate: String = endpointTemplate.trim().ifEmpty { DEFAULT_ENDPOINT_TEMPLATE }
    val pathEndpointTemplate: String = pathEndpointTemplate.trim().ifEmpty { DEFAULT_PATH_ENDPOINT_TEMPLATE }

    /** The Alchemy network mapped to a service chain id, or null when the chain is not served. */
    fun networkFor(chainId: String): AlchemyNetworkProperties? = networks[chainId]

    /** Fails fast on every static rule at once; database-dependent rules live in the startup preflight. */
    fun validate() {
        val violations = staticViolations()
        if (violations.isNotEmpty()) {
            throw ProviderConfigurationException(
                "Alchemy provider configuration is invalid: ${violations.joinToString("; ")}",
            )
        }
    }

    fun staticViolations(): List<String> {
        val violations = mutableListOf<String>()
        if (apiKey.isBlank()) {
            violations += "$PREFIX.api-key must be set (ASSET_SYNC_PROVIDER_ALCHEMY_API_KEY)"
        }
        when (authMode) {
            AlchemyAuthMode.HEADER -> {
                if (!endpointTemplate.contains(NETWORK_PLACEHOLDER)) {
                    violations += "$PREFIX.endpoint-template must contain $NETWORK_PLACEHOLDER"
                }
                if (endpointTemplate.contains(API_KEY_PLACEHOLDER)) {
                    violations += "$PREFIX.endpoint-template must not contain $API_KEY_PLACEHOLDER in header auth mode"
                }
                if (!isHttpUrl(endpointTemplate)) {
                    violations += "$PREFIX.endpoint-template must be an http(s) URL"
                }
            }
            AlchemyAuthMode.PATH -> {
                if (!pathEndpointTemplate.contains(NETWORK_PLACEHOLDER) || !pathEndpointTemplate.contains(API_KEY_PLACEHOLDER)) {
                    violations += "$PREFIX.path-endpoint-template must contain both $NETWORK_PLACEHOLDER and $API_KEY_PLACEHOLDER"
                }
                if (!isHttpUrl(pathEndpointTemplate)) {
                    violations += "$PREFIX.path-endpoint-template must be an http(s) URL"
                }
            }
        }
        networks.forEach { (chainId, network) ->
            if (chainId.isBlank()) {
                violations += "$PREFIX.networks has a blank chain id key"
            }
            if (!NETWORK_SLUG.matches(network.network)) {
                violations += "$PREFIX.networks.$chainId.network must be a non-blank Alchemy network slug such as eth-sepolia"
            }
            if (network.startBlock != null && network.startBlock < 0) {
                violations += "$PREFIX.networks.$chainId.start-block must not be negative"
            }
        }
        if (finalityDepthFallback <= 0) {
            violations += "$PREFIX.finality-depth-fallback must be positive"
        }
        if (maxWindowBlocks <= 0) {
            violations += "$PREFIX.max-window-blocks must be positive"
        }
        if (maxRpcCallsPerFetch < MIN_RPC_CALLS_PER_FETCH) {
            violations += "$PREFIX.max-rpc-calls-per-fetch must be at least $MIN_RPC_CALLS_PER_FETCH " +
                "(latest block, finality block, inbound transfers, outbound transfers)"
        }
        if (rateLimitCapacity <= 0) {
            violations += "$PREFIX.rate-limit-capacity must be positive"
        }
        if (!(rateLimitRefillPerSecond > 0.0) || !rateLimitRefillPerSecond.isFinite()) {
            violations += "$PREFIX.rate-limit-refill-per-second must be positive"
        }
        return violations
    }

    override fun toString(): String =
        "AlchemyProviderProperties(apiKey=${if (apiKey.isBlank()) "<unset>" else "***"}, authMode=$authMode, " +
            "endpointTemplate=$endpointTemplate, pathEndpointTemplate=$pathEndpointTemplate, startMode=$startMode, " +
            "networks=$networks, finalityMode=$finalityMode, finalityDepthFallback=$finalityDepthFallback, " +
            "maxWindowBlocks=$maxWindowBlocks, maxRpcCallsPerFetch=$maxRpcCallsPerFetch, " +
            "rateLimitCapacity=$rateLimitCapacity, rateLimitRefillPerSecond=$rateLimitRefillPerSecond)"

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")

    companion object {
        const val PREFIX = "asset-sync.provider.alchemy"
        const val NETWORK_PLACEHOLDER = "{network}"
        const val API_KEY_PLACEHOLDER = "{apiKey}"
        const val DEFAULT_ENDPOINT_TEMPLATE = "https://$NETWORK_PLACEHOLDER.g.alchemy.com/v2/"
        const val DEFAULT_PATH_ENDPOINT_TEMPLATE = "https://$NETWORK_PLACEHOLDER.g.alchemy.com/v2/$API_KEY_PLACEHOLDER"

        /** One fetch needs the latest block, the finality block, and one transfer call per direction. */
        const val MIN_RPC_CALLS_PER_FETCH = 4

        private val NETWORK_SLUG = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    }
}

/** One service chain mapped onto an Alchemy network subdomain, with an optional backfill start. */
data class AlchemyNetworkProperties(
    val network: String = "",
    val startBlock: Long? = null,
)

/** `header` sends `Authorization: Bearer`; `path` is the compatibility mode with the key in the URL. */
enum class AlchemyAuthMode { HEADER, PATH }

/** Where a watched address without an Alchemy cursor starts: the current safe block, or a configured one. */
enum class AlchemyStartMode { REGISTRATION_SAFE, CONFIGURED_BLOCK }

/** How the safe high-water is resolved: the `safe` tag, the `finalized` tag, or latest minus a depth. */
enum class AlchemyFinalityMode { SAFE, FINALIZED, DEPTH }
