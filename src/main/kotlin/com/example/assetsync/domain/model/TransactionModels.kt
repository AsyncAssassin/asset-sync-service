package com.example.assetsync.domain.model

import java.math.BigDecimal

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
        require(chainId.isNotBlank()) { "chainId must not be blank." }
        require(txHash.isNotBlank()) { "txHash must not be blank." }
        require(eventIndex >= 0) { "eventIndex must be non-negative." }
        require(address.isNotBlank()) { "address must not be blank." }
        require(asset.isNotBlank()) { "asset must not be blank." }
    }

    fun outboxIdempotencyKey(status: TransactionStatus): String =
        "observed-tx:$chainId:$txHash:$eventIndex:$address:$asset:status:${status.name}"
}

data class TransactionImmutableFields(
    val naturalKey: ObservedTransactionNaturalKey,
    val direction: Direction,
    val amount: BigDecimal,
) {
    init {
        require(amount.signum() >= 0) { "amount must be non-negative." }
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
        require(blockHeight >= 0) { "blockHeight must be non-negative." }
        require(confirmations >= 0) { "confirmations must be non-negative." }
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
    require(chainId.isNotBlank()) { "chainId must not be blank." }
    require(txHash.isNotBlank()) { "txHash must not be blank." }
    require(eventIndex >= 0) { "eventIndex must be non-negative." }
    require(address.isNotBlank()) { "address must not be blank." }
    require(asset.isNotBlank()) { "asset must not be blank." }
    require(amount.signum() >= 0) { "amount must be non-negative." }
    require(blockHeight >= 0) { "blockHeight must be non-negative." }
    require(confirmations >= 0) { "confirmations must be non-negative." }
}
