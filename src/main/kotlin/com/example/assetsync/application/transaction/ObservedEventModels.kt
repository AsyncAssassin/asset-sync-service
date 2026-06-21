package com.example.assetsync.application.transaction

import com.example.assetsync.domain.model.CurrentTransactionSnapshot
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.ImmutableTransactionField
import com.example.assetsync.domain.model.ObservedTransactionNaturalKey
import com.example.assetsync.domain.model.OutboxEventType
import com.example.assetsync.domain.model.TransactionLifecycleState
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.model.TransitionOutcome
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class IngestObservedEventCommand(
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
)

data class ObservedEventIngestionResult(
    val transactionId: UUID,
    val result: TransitionOutcome,
    val status: TransactionStatus,
    val outboxEvents: List<OutboxEventType>,
)

data class ObservedTransaction(
    val id: UUID,
    val chainId: String,
    val txHash: String,
    val eventIndex: Int,
    val watchedAddressId: UUID,
    val address: String,
    val asset: String,
    val direction: Direction,
    val amount: BigDecimal,
    val blockHeight: Long,
    val confirmations: Int,
    val status: TransactionStatus,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val confirmedAt: Instant?,
    val revertedAt: Instant?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun toCurrentSnapshot(): CurrentTransactionSnapshot =
        CurrentTransactionSnapshot(
            chainId = chainId,
            txHash = txHash,
            eventIndex = eventIndex,
            address = address,
            asset = asset,
            direction = direction,
            amount = amount,
            blockHeight = blockHeight,
            confirmations = confirmations,
            status = status,
        )
}

data class NewObservedTransaction(
    val id: UUID,
    val chainId: String,
    val txHash: String,
    val eventIndex: Int,
    val watchedAddressId: UUID,
    val address: String,
    val asset: String,
    val direction: Direction,
    val amount: BigDecimal,
    val lifecycleState: TransactionLifecycleState,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val confirmedAt: Instant?,
    val revertedAt: Instant?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ObservedTransactionLifecycleUpdate(
    val id: UUID,
    val lifecycleState: TransactionLifecycleState,
    val lastSeenAt: Instant,
    val confirmedAt: Instant?,
    val revertedAt: Instant?,
    val version: Long,
    val updatedAt: Instant,
)

data class ObservedTransactionOutboxPayload(
    val eventId: UUID,
    val eventType: String,
    val occurredAt: Instant,
    val transactionId: UUID,
    val chainId: String,
    val txHash: String,
    val eventIndex: Int,
    val address: String,
    val asset: String,
    val amount: String,
    val direction: String,
    val status: String,
    val confirmations: Int,
    val blockHeight: Long,
)

data class NewOutboxEvent(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: OutboxEventType,
    val idempotencyKey: String,
    val payload: ObservedTransactionOutboxPayload,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface ObservedTransactionRepository {
    fun findByNaturalKeyForUpdate(key: ObservedTransactionNaturalKey): ObservedTransaction?

    fun insertOrNull(transaction: NewObservedTransaction): ObservedTransaction?

    fun updateLifecycle(update: ObservedTransactionLifecycleUpdate): ObservedTransaction
}

interface OutboxRepository {
    fun insert(event: NewOutboxEvent): Boolean
}

class WatchedAddressNotFoundException(
    val chainId: String,
    val address: String,
    val asset: String,
) : RuntimeException("Active watched address was not found.")

class ObservedTransactionConflictException(
    val naturalKey: ObservedTransactionNaturalKey,
    val conflictingFields: Set<ImmutableTransactionField>,
) : RuntimeException("Observed transaction immutable fields conflict.")

class InvalidObservedEventRequestException(
    override val message: String,
) : RuntimeException(message)
