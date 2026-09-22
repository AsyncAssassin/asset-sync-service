package com.example.assetsync.infrastructure.provider.alchemy

import com.example.assetsync.application.sync.ChainProviderEventsPage
import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.config.AlchemyAuthMode
import com.example.assetsync.config.AlchemyProviderProperties
import org.slf4j.LoggerFactory

/**
 * The Alchemy-backed [ChainProviderPort]. In this version it owns the validated configuration,
 * the startup preflight result, and the health state; the ERC-20 transfer fetch itself is not
 * implemented yet, so every page request fails with a terminal [ProviderConfigurationException]
 * rather than burning the retry budget as if the provider were merely unavailable.
 */
class AlchemyChainProvider(
    private val properties: AlchemyProviderProperties,
    preflight: AlchemyPreflightReport,
) : ChainProviderPort {

    private val logger = LoggerFactory.getLogger(AlchemyChainProvider::class.java)
    private val scrubber = AlchemySecretScrubber(properties.apiKey)

    /** Alchemy networks proven reachable by the startup probe, never endpoints or credentials. */
    val networks: List<String> = preflight.probedNetworks

    val authMode: AlchemyAuthMode
        get() = properties.authMode

    @Volatile
    private var state: AlchemyProviderState =
        if (networks.isEmpty()) AlchemyProviderState.NO_REQUIRED_NETWORKS else AlchemyProviderState.PROBE_SUCCEEDED

    @Volatile
    private var lastError: String? = null

    override fun fetchObservedEventsPage(request: ChainProviderEventsPageRequest): ChainProviderEventsPage {
        val network = properties.networkFor(request.chainId)?.network
        val failure = if (network == null) {
            ProviderConfigurationException(
                "No Alchemy network is mapped for chain ${request.chainId}; add " +
                    "${AlchemyProviderProperties.PREFIX}.networks.${request.chainId}.network or disable the chain.",
            )
        } else {
            ProviderConfigurationException(FETCH_NOT_AVAILABLE_MESSAGE)
        }
        recordFailure(request, failure)
        throw failure
    }

    fun state(): AlchemyProviderState = state

    fun lastError(): String? = lastError

    private fun recordFailure(request: ChainProviderEventsPageRequest, exception: RuntimeException) {
        val error = scrubber.scrub(exception.message).take(MAX_ERROR_LENGTH)
        state = AlchemyProviderState.FETCH_FAILED
        lastError = error
        logger.warn(
            "alchemy_provider_page_fetch_failed chainId={} address={} asset={} limit={} error={}",
            request.chainId,
            request.address,
            request.asset,
            request.limit,
            error,
        )
    }

    companion object {
        const val FETCH_NOT_AVAILABLE_MESSAGE =
            "Alchemy transfer fetch is not available in this version; keep asset-sync.provider.type=http for syncing."
        private const val MAX_ERROR_LENGTH = 240
    }
}

enum class AlchemyProviderState {
    PROBE_SUCCEEDED,
    NO_REQUIRED_NETWORKS,
    FETCH_FAILED,
}
