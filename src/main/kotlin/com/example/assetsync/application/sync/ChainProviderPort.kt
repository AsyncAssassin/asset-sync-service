package com.example.assetsync.application.sync

import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.transaction.IngestObservedEventCommand
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
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
    fun toIngestCommand(): IngestObservedEventCommand =
        IngestObservedEventCommand(
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

class ChainProviderUnavailableException(
    override val message: String = "Provider is unavailable.",
) : RuntimeException(message)
