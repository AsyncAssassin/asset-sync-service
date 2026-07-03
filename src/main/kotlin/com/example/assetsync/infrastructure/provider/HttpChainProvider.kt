package com.example.assetsync.infrastructure.provider

import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import java.math.BigDecimal
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Real chain provider: fetches observed events over HTTP from a configured endpoint (a real
 * indexer in prod; the bundled simulator in demo; a WireMock stub in the e2e tests). Active on
 * every non-local/test profile — this is the bean that makes a prod context bootable.
 */
@Component
@Profile("!local & !test")
class HttpChainProvider(
    private val chainProviderRestClient: RestClient,
) : ChainProviderPort {

    private val logger = LoggerFactory.getLogger(HttpChainProvider::class.java)

    @Volatile
    private var lastFetchHealthy: Boolean? = null

    @Volatile
    private var lastError: String? = null

    override fun fetchObservedEvents(watchedAddress: WatchedAddress): Sequence<ChainProviderObservedEvent> =
        try {
            val response = chainProviderRestClient
                .get()
                .uri(
                    "/v1/chains/{chainId}/addresses/{address}/events?asset={asset}",
                    watchedAddress.chainId,
                    watchedAddress.address,
                    watchedAddress.asset,
                )
                .retrieve()
                .body(ProviderEventsResponse::class.java)
                ?: ProviderEventsResponse()
            lastFetchHealthy = true
            lastError = null
            logger.info(
                "http_provider_fetch_succeeded chainId={} address={} asset={} events={}",
                watchedAddress.chainId,
                watchedAddress.address,
                watchedAddress.asset,
                response.events.size,
            )
            response.events.asSequence().map { it.toDomain(watchedAddress.chainId) }
        } catch (exception: RuntimeException) {
            // Covers RestClientException (4xx/5xx, timeouts) and a malformed body
            // (HttpMessageNotReadableException), so lastError/health tracking stays consistent.
            lastFetchHealthy = false
            lastError = exception.message?.take(240)
            logger.warn(
                "http_provider_fetch_failed chainId={} address={} asset={} error={}",
                watchedAddress.chainId,
                watchedAddress.address,
                watchedAddress.asset,
                lastError,
            )
            throw ChainProviderUnavailableException(exception.message ?: "Provider request failed.", exception)
        }

    fun lastFetchHealthy(): Boolean? = lastFetchHealthy

    fun lastError(): String? = lastError
}

data class ProviderEventsResponse(
    val events: List<ProviderEvent> = emptyList(),
)

data class ProviderEvent(
    val txHash: String,
    val eventIndex: Int,
    val address: String,
    val asset: String,
    val amount: BigDecimal,
    val blockHeight: Long,
    val confirmations: Int,
    val direction: Direction,
    val status: TransactionStatus,
) {
    fun toDomain(chainId: String): ChainProviderObservedEvent =
        ChainProviderObservedEvent(
            chainId = chainId,
            txHash = txHash,
            eventIndex = eventIndex,
            address = address,
            asset = asset,
            amount = amount,
            blockHeight = blockHeight,
            confirmations = confirmations,
            direction = direction,
            status = status,
        )
}
