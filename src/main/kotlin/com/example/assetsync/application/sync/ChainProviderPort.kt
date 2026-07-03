package com.example.assetsync.application.sync

import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.transaction.IngestObservedEventCommand
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.policy.ChainIdentityNormalizer
import java.math.BigDecimal

interface ChainProviderPort {
    fun fetchObservedEvents(watchedAddress: WatchedAddress): Sequence<ChainProviderObservedEvent>
}

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
) : RuntimeException(message, cause)
