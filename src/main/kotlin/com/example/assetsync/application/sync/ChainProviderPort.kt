package com.example.assetsync.application.sync

import com.example.assetsync.application.transaction.IngestObservedEventCommand
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.policy.ChainIdentityNormalizer
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface ChainProviderPort {
    /** Short provider type, recorded as the `provider:<name>` source of the events it returns. */
    val providerName: String

    fun fetchObservedEventsPage(request: ChainProviderEventsPageRequest): ChainProviderEventsPage
}

data class ChainProviderEventsPageRequest(
    val watchedAddressId: UUID,
    val accountId: UUID,
    val chainId: String,
    val address: String,
    val asset: String,
    val cursor: String?,
    val limit: Int,
    /** The durable checkpoint (last processed event); a provider without a cursor resumes from it. */
    val fromBlockHeight: Long? = null,
    val fromEventIndex: Int? = null,
    val toBlockHeight: Long? = null,
    val safeBlockHeight: Long? = null,
    val checkpoint: JsonNode? = null,
)

data class ChainProviderEventsPage(
    val events: List<ChainProviderObservedEvent>,
    /**
     * Durable provider resume token. A final page may omit it only when the page supplies durable
     * block high-water (`safeBlockHeight`, or `latestBlockHeight` when no safe height exists).
     */
    val nextCursor: String?,
    val hasMore: Boolean,
    val latestBlockHeight: Long? = null,
    val safeBlockHeight: Long? = null,
    val metadata: ObjectNode? = null,
)

data class ChainProviderObservedEvent(
    val chainId: String,
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
    fun toIngestCommand(source: String): IngestObservedEventCommand {
        val identity = ChainIdentityNormalizer.normalize(
            chainId = chainId,
            address = address,
            asset = asset,
        )
        return IngestObservedEventCommand(
            chainId = identity.chainId,
            txHash = ChainIdentityNormalizer.normalizeTxHash(identity.chainId, txHash),
            eventIndex = eventIndex,
            address = identity.address,
            asset = identity.asset,
            amount = amount,
            blockHeight = blockHeight,
            confirmations = confirmations,
            direction = direction,
            status = status,
            source = source,
        )
    }
}

class ChainProviderUnavailableException(
    message: String = "Provider is unavailable.",
    cause: Throwable? = null,
    val retryAfter: Instant? = null,
) : RuntimeException(message, cause)

class ProviderDataInvalidException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Deterministic provider configuration, credential, or rollout failure: a rejected API key, a
 * chain without a provider mapping, a fetch the configured provider cannot serve. Retrying cannot
 * fix it, so it is terminal for a sync run and never consumes the failure budget as a transient
 * outage would. Messages must already be scrubbed of secrets when the exception is created.
 */
class ProviderConfigurationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
