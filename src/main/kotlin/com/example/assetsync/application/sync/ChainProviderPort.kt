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
    val fromBlockHeight: Long? = null,
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
    fun toIngestCommand(): IngestObservedEventCommand {
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
