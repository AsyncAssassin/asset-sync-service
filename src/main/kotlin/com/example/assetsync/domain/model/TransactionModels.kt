package com.example.assetsync.domain.model

import java.math.BigDecimal
import java.util.UUID

/**
 * A value of an observed transaction that the domain model refuses, such as a blank transaction
 * hash or a negative amount: bad data from whoever reported the transaction. A broken invariant of
 * the service's own logic stays a plain [IllegalArgumentException], so it is never taken for bad
 * provider data.
 */
class DomainInvariantException(message: String) : IllegalArgumentException(message)

/** Like [require], for a value the domain model refuses: throws [DomainInvariantException]. */
private inline fun requireValid(value: Boolean, lazyMessage: () -> String) {
    if (!value) {
        throw DomainInvariantException(lazyMessage())
    }
}

enum class TransactionStatus {
    SEEN,
    CONFIRMED,
    REVERTED,
}

enum class Direction {
    INBOUND,
    OUTBOUND,
}

enum class OutboxEventType {
    TRANSACTION_SEEN,
    TRANSACTION_CONFIRMED,
    TRANSACTION_REVERTED,
}

enum class TransitionOutcome {
    CREATED,
    UPDATED,
    NO_CHANGE,
    CONFLICT,
}

enum class ImmutableTransactionField {
    CHAIN_ID,
    TX_HASH,
    EVENT_INDEX,
    ADDRESS,
    ASSET,
    DIRECTION,
    AMOUNT,
}

data class ObservedTransactionNaturalKey(
    val chainId: String,
    val txHash: String,
    val eventIndex: Int,
    val address: String,
    val asset: String,
) {
    init {
        requireValid(chainId.isNotBlank()) { "chainId must not be blank." }
        requireValid(txHash.isNotBlank()) { "txHash must not be blank." }
        requireValid(eventIndex >= 0) { "eventIndex must be non-negative." }
        requireValid(address.isNotBlank()) { "address must not be blank." }
        requireValid(asset.isNotBlank()) { "asset must not be blank." }
    }
}

/**
 * Unique key of one lifecycle outbox event: the observed transaction row id, the status it reached,
 * and the row version of that change. The id is a UUID, so a transaction hash or address that
 * contains ':' can no longer make two transactions share a key, and no key of this format equals
 * one built from the natural key by earlier versions.
 */
fun outboxIdempotencyKey(transactionId: UUID, status: TransactionStatus, version: Long): String {
    require(version >= 0) { "version must be non-negative." }
    return "observed-tx:$transactionId:status:${status.name}:v:$version"
}

data class TransactionImmutableFields(
    val naturalKey: ObservedTransactionNaturalKey,
    val direction: Direction,
    val amount: BigDecimal,
) {
    init {
        requireValid(amount.signum() >= 0) { "amount must be non-negative." }
    }

    fun conflictingFields(other: TransactionImmutableFields): Set<ImmutableTransactionField> =
        buildSet {
            if (naturalKey.chainId != other.naturalKey.chainId) {
                add(ImmutableTransactionField.CHAIN_ID)
            }
            if (naturalKey.txHash != other.naturalKey.txHash) {
                add(ImmutableTransactionField.TX_HASH)
            }
            if (naturalKey.eventIndex != other.naturalKey.eventIndex) {
                add(ImmutableTransactionField.EVENT_INDEX)
            }
            if (naturalKey.address != other.naturalKey.address) {
                add(ImmutableTransactionField.ADDRESS)
            }
            if (naturalKey.asset != other.naturalKey.asset) {
                add(ImmutableTransactionField.ASSET)
            }
            if (direction != other.direction) {
                add(ImmutableTransactionField.DIRECTION)
            }
            if (amount.compareTo(other.amount) != 0) {
                add(ImmutableTransactionField.AMOUNT)
            }
        }
}

data class TransactionLifecycleState(
    val blockHeight: Long,
    val confirmations: Int,
    val status: TransactionStatus,
) {
    init {
        requireValid(blockHeight >= 0) { "blockHeight must be non-negative." }
        requireValid(confirmations >= 0) { "confirmations must be non-negative." }
    }
}

data class CurrentTransactionSnapshot(
    val chainId: String,
    val txHash: String,
    val eventIndex: Int,
    val address: String,
    val asset: String,
    val direction: Direction,
    val amount: BigDecimal,
    val blockHeight: Long,
    val confirmations: Int,
    val status: TransactionStatus,
) {
    init {
        validateObservedTransactionFields(
            chainId = chainId,
            txHash = txHash,
            eventIndex = eventIndex,
            address = address,
            asset = asset,
            amount = amount,
            blockHeight = blockHeight,
            confirmations = confirmations,
        )
    }

    fun naturalKey(): ObservedTransactionNaturalKey =
        ObservedTransactionNaturalKey(
            chainId = chainId,
            txHash = txHash,
            eventIndex = eventIndex,
            address = address,
            asset = asset,
        )

    fun immutableFields(): TransactionImmutableFields =
        TransactionImmutableFields(
            naturalKey = naturalKey(),
            direction = direction,
            amount = amount,
        )

    fun lifecycleState(): TransactionLifecycleState =
        TransactionLifecycleState(
            blockHeight = blockHeight,
            confirmations = confirmations,
            status = status,
        )
}

data class IncomingObservedTransaction(
    val chainId: String,
    val txHash: String,
    val eventIndex: Int,
    val address: String,
    val asset: String,
    val direction: Direction,
    val amount: BigDecimal,
    val blockHeight: Long,
    val confirmations: Int,
    val status: TransactionStatus,
) {
    init {
        validateObservedTransactionFields(
            chainId = chainId,
            txHash = txHash,
            eventIndex = eventIndex,
            address = address,
            asset = asset,
            amount = amount,
            blockHeight = blockHeight,
            confirmations = confirmations,
        )
    }

    fun naturalKey(): ObservedTransactionNaturalKey =
        ObservedTransactionNaturalKey(
            chainId = chainId,
            txHash = txHash,
            eventIndex = eventIndex,
            address = address,
            asset = asset,
        )

    fun immutableFields(): TransactionImmutableFields =
        TransactionImmutableFields(
            naturalKey = naturalKey(),
            direction = direction,
            amount = amount,
        )

    fun lifecycleState(status: TransactionStatus): TransactionLifecycleState =
        TransactionLifecycleState(
            blockHeight = blockHeight,
            confirmations = confirmations,
            status = status,
        )
}

sealed class TransactionTransitionResult {
    abstract val outcome: TransitionOutcome
    abstract val state: TransactionLifecycleState
    abstract val effectiveIncomingStatus: TransactionStatus
    abstract val shouldPersist: Boolean
    abstract val outboxEventType: OutboxEventType?

    val resultingStatus: TransactionStatus
        get() = state.status

    val storedConfirmations: Int
        get() = state.confirmations

    val storedBlockHeight: Long
        get() = state.blockHeight

    data class Created(
        override val state: TransactionLifecycleState,
        override val effectiveIncomingStatus: TransactionStatus,
        override val outboxEventType: OutboxEventType,
    ) : TransactionTransitionResult() {
        override val outcome: TransitionOutcome = TransitionOutcome.CREATED
        override val shouldPersist: Boolean = true
    }

    data class Updated(
        override val state: TransactionLifecycleState,
        override val effectiveIncomingStatus: TransactionStatus,
        override val outboxEventType: OutboxEventType?,
    ) : TransactionTransitionResult() {
        override val outcome: TransitionOutcome = TransitionOutcome.UPDATED
        override val shouldPersist: Boolean = true
    }

    data class NoChange(
        override val state: TransactionLifecycleState,
        override val effectiveIncomingStatus: TransactionStatus,
    ) : TransactionTransitionResult() {
        override val outcome: TransitionOutcome = TransitionOutcome.NO_CHANGE
        override val shouldPersist: Boolean = false
        override val outboxEventType: OutboxEventType? = null
    }

    data class Conflict(
        override val state: TransactionLifecycleState,
        override val effectiveIncomingStatus: TransactionStatus,
        val current: TransactionImmutableFields,
        val incoming: TransactionImmutableFields,
        val conflictingFields: Set<ImmutableTransactionField>,
    ) : TransactionTransitionResult() {
        init {
            require(conflictingFields.isNotEmpty()) { "conflictingFields must not be empty." }
        }

        override val outcome: TransitionOutcome = TransitionOutcome.CONFLICT
        override val shouldPersist: Boolean = false
        override val outboxEventType: OutboxEventType? = null

        val naturalKey: ObservedTransactionNaturalKey = current.naturalKey
    }
}

private fun validateObservedTransactionFields(
    chainId: String,
    txHash: String,
    eventIndex: Int,
    address: String,
    asset: String,
    amount: BigDecimal,
    blockHeight: Long,
    confirmations: Int,
) {
    requireValid(chainId.isNotBlank()) { "chainId must not be blank." }
    requireValid(txHash.isNotBlank()) { "txHash must not be blank." }
    requireValid(eventIndex >= 0) { "eventIndex must be non-negative." }
    requireValid(address.isNotBlank()) { "address must not be blank." }
    requireValid(asset.isNotBlank()) { "asset must not be blank." }
    requireValid(amount.signum() >= 0) { "amount must be non-negative." }
    requireValid(blockHeight >= 0) { "blockHeight must be non-negative." }
    requireValid(confirmations >= 0) { "confirmations must be non-negative." }
}
