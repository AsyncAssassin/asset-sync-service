package com.example.assetsync.infrastructure.persistence

import com.example.assetsync.application.transaction.NewObservedTransaction
import com.example.assetsync.application.transaction.ObservedTransaction
import com.example.assetsync.application.transaction.ObservedTransactionLifecycleUpdate
import com.example.assetsync.application.transaction.ObservedTransactionRepository
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.ObservedTransactionNaturalKey
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.OBSERVED_TRANSACTIONS
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository

@Repository
class JooqObservedTransactionRepository(
    private val dsl: DSLContext,
) : ObservedTransactionRepository {

    override fun findByNaturalKeyForUpdate(key: ObservedTransactionNaturalKey): ObservedTransaction? =
        dsl
            .select(
                OBSERVED_TRANSACTIONS.ID,
                OBSERVED_TRANSACTIONS.CHAIN_ID,
                OBSERVED_TRANSACTIONS.TX_HASH,
                OBSERVED_TRANSACTIONS.EVENT_INDEX,
                OBSERVED_TRANSACTIONS.WATCHED_ADDRESS_ID,
                OBSERVED_TRANSACTIONS.ADDRESS,
                OBSERVED_TRANSACTIONS.ASSET,
                OBSERVED_TRANSACTIONS.DIRECTION,
                OBSERVED_TRANSACTIONS.AMOUNT,
                OBSERVED_TRANSACTIONS.BLOCK_HEIGHT,
                OBSERVED_TRANSACTIONS.CONFIRMATIONS,
                OBSERVED_TRANSACTIONS.STATUS,
                OBSERVED_TRANSACTIONS.FIRST_SEEN_AT,
                OBSERVED_TRANSACTIONS.LAST_SEEN_AT,
                OBSERVED_TRANSACTIONS.CONFIRMED_AT,
                OBSERVED_TRANSACTIONS.REVERTED_AT,
                OBSERVED_TRANSACTIONS.VERSION,
                OBSERVED_TRANSACTIONS.CREATED_AT,
                OBSERVED_TRANSACTIONS.UPDATED_AT,
            )
            .from(OBSERVED_TRANSACTIONS)
            .where(OBSERVED_TRANSACTIONS.CHAIN_ID.eq(key.chainId))
            .and(OBSERVED_TRANSACTIONS.TX_HASH.eq(key.txHash))
            .and(OBSERVED_TRANSACTIONS.EVENT_INDEX.eq(key.eventIndex))
            .and(OBSERVED_TRANSACTIONS.ADDRESS.eq(key.address))
            .and(OBSERVED_TRANSACTIONS.ASSET.eq(key.asset))
            .forUpdate()
            .fetchOne { it.toObservedTransaction() }

    override fun insertOrNull(transaction: NewObservedTransaction): ObservedTransaction? =
        dsl
            .insertInto(OBSERVED_TRANSACTIONS)
            .set(OBSERVED_TRANSACTIONS.ID, transaction.id)
            .set(OBSERVED_TRANSACTIONS.CHAIN_ID, transaction.chainId)
            .set(OBSERVED_TRANSACTIONS.TX_HASH, transaction.txHash)
            .set(OBSERVED_TRANSACTIONS.EVENT_INDEX, transaction.eventIndex)
            .set(OBSERVED_TRANSACTIONS.WATCHED_ADDRESS_ID, transaction.watchedAddressId)
            .set(OBSERVED_TRANSACTIONS.ADDRESS, transaction.address)
            .set(OBSERVED_TRANSACTIONS.ASSET, transaction.asset)
            .set(OBSERVED_TRANSACTIONS.DIRECTION, transaction.direction.name)
            .set(OBSERVED_TRANSACTIONS.AMOUNT, transaction.amount)
            .set(OBSERVED_TRANSACTIONS.BLOCK_HEIGHT, transaction.lifecycleState.blockHeight)
            .set(OBSERVED_TRANSACTIONS.CONFIRMATIONS, transaction.lifecycleState.confirmations)
            .set(OBSERVED_TRANSACTIONS.STATUS, transaction.lifecycleState.status.name)
            .set(OBSERVED_TRANSACTIONS.FIRST_SEEN_AT, transaction.firstSeenAt.toOffsetDateTime())
            .set(OBSERVED_TRANSACTIONS.LAST_SEEN_AT, transaction.lastSeenAt.toOffsetDateTime())
            .set(OBSERVED_TRANSACTIONS.CONFIRMED_AT, transaction.confirmedAt?.toOffsetDateTime())
            .set(OBSERVED_TRANSACTIONS.REVERTED_AT, transaction.revertedAt?.toOffsetDateTime())
            .set(OBSERVED_TRANSACTIONS.VERSION, transaction.version)
            .set(OBSERVED_TRANSACTIONS.CREATED_AT, transaction.createdAt.toOffsetDateTime())
            .set(OBSERVED_TRANSACTIONS.UPDATED_AT, transaction.updatedAt.toOffsetDateTime())
            .onConflict(
                OBSERVED_TRANSACTIONS.CHAIN_ID,
                OBSERVED_TRANSACTIONS.TX_HASH,
                OBSERVED_TRANSACTIONS.EVENT_INDEX,
                OBSERVED_TRANSACTIONS.ADDRESS,
                OBSERVED_TRANSACTIONS.ASSET,
            )
            .doNothing()
            .returningResult(
                OBSERVED_TRANSACTIONS.ID,
                OBSERVED_TRANSACTIONS.CHAIN_ID,
                OBSERVED_TRANSACTIONS.TX_HASH,
                OBSERVED_TRANSACTIONS.EVENT_INDEX,
                OBSERVED_TRANSACTIONS.WATCHED_ADDRESS_ID,
                OBSERVED_TRANSACTIONS.ADDRESS,
                OBSERVED_TRANSACTIONS.ASSET,
                OBSERVED_TRANSACTIONS.DIRECTION,
                OBSERVED_TRANSACTIONS.AMOUNT,
                OBSERVED_TRANSACTIONS.BLOCK_HEIGHT,
                OBSERVED_TRANSACTIONS.CONFIRMATIONS,
                OBSERVED_TRANSACTIONS.STATUS,
                OBSERVED_TRANSACTIONS.FIRST_SEEN_AT,
                OBSERVED_TRANSACTIONS.LAST_SEEN_AT,
                OBSERVED_TRANSACTIONS.CONFIRMED_AT,
                OBSERVED_TRANSACTIONS.REVERTED_AT,
                OBSERVED_TRANSACTIONS.VERSION,
                OBSERVED_TRANSACTIONS.CREATED_AT,
                OBSERVED_TRANSACTIONS.UPDATED_AT,
            )
            .fetchOne { it.toObservedTransaction() }

    override fun updateLifecycle(update: ObservedTransactionLifecycleUpdate): ObservedTransaction =
        requireNotNull(
            dsl
                .update(OBSERVED_TRANSACTIONS)
                .set(OBSERVED_TRANSACTIONS.BLOCK_HEIGHT, update.lifecycleState.blockHeight)
                .set(OBSERVED_TRANSACTIONS.CONFIRMATIONS, update.lifecycleState.confirmations)
                .set(OBSERVED_TRANSACTIONS.STATUS, update.lifecycleState.status.name)
                .set(OBSERVED_TRANSACTIONS.LAST_SEEN_AT, update.lastSeenAt.toOffsetDateTime())
                .set(OBSERVED_TRANSACTIONS.CONFIRMED_AT, update.confirmedAt?.toOffsetDateTime())
                .set(OBSERVED_TRANSACTIONS.REVERTED_AT, update.revertedAt?.toOffsetDateTime())
                .set(OBSERVED_TRANSACTIONS.VERSION, update.version)
                .set(OBSERVED_TRANSACTIONS.UPDATED_AT, update.updatedAt.toOffsetDateTime())
                .where(OBSERVED_TRANSACTIONS.ID.eq(update.id))
                .returningResult(
                    OBSERVED_TRANSACTIONS.ID,
                    OBSERVED_TRANSACTIONS.CHAIN_ID,
                    OBSERVED_TRANSACTIONS.TX_HASH,
                    OBSERVED_TRANSACTIONS.EVENT_INDEX,
                    OBSERVED_TRANSACTIONS.WATCHED_ADDRESS_ID,
                    OBSERVED_TRANSACTIONS.ADDRESS,
                    OBSERVED_TRANSACTIONS.ASSET,
                    OBSERVED_TRANSACTIONS.DIRECTION,
                    OBSERVED_TRANSACTIONS.AMOUNT,
                    OBSERVED_TRANSACTIONS.BLOCK_HEIGHT,
                    OBSERVED_TRANSACTIONS.CONFIRMATIONS,
                    OBSERVED_TRANSACTIONS.STATUS,
                    OBSERVED_TRANSACTIONS.FIRST_SEEN_AT,
                    OBSERVED_TRANSACTIONS.LAST_SEEN_AT,
                    OBSERVED_TRANSACTIONS.CONFIRMED_AT,
                    OBSERVED_TRANSACTIONS.REVERTED_AT,
                    OBSERVED_TRANSACTIONS.VERSION,
                    OBSERVED_TRANSACTIONS.CREATED_AT,
                    OBSERVED_TRANSACTIONS.UPDATED_AT,
                )
                .fetchOne { it.toObservedTransaction() },
        ) { "Observed transaction ${update.id} was not found for lifecycle update." }

    private fun Record.toObservedTransaction(): ObservedTransaction =
        ObservedTransaction(
            id = requireNotNull(get(OBSERVED_TRANSACTIONS.ID)),
            chainId = requireNotNull(get(OBSERVED_TRANSACTIONS.CHAIN_ID)),
            txHash = requireNotNull(get(OBSERVED_TRANSACTIONS.TX_HASH)),
            eventIndex = requireNotNull(get(OBSERVED_TRANSACTIONS.EVENT_INDEX)),
            watchedAddressId = requireNotNull(get(OBSERVED_TRANSACTIONS.WATCHED_ADDRESS_ID)),
            address = requireNotNull(get(OBSERVED_TRANSACTIONS.ADDRESS)),
            asset = requireNotNull(get(OBSERVED_TRANSACTIONS.ASSET)),
            direction = Direction.valueOf(requireNotNull(get(OBSERVED_TRANSACTIONS.DIRECTION))),
            amount = requireNotNull(get(OBSERVED_TRANSACTIONS.AMOUNT)),
            blockHeight = requireNotNull(get(OBSERVED_TRANSACTIONS.BLOCK_HEIGHT)),
            confirmations = requireNotNull(get(OBSERVED_TRANSACTIONS.CONFIRMATIONS)),
            status = TransactionStatus.valueOf(requireNotNull(get(OBSERVED_TRANSACTIONS.STATUS))),
            firstSeenAt = requireNotNull(get(OBSERVED_TRANSACTIONS.FIRST_SEEN_AT)).toInstant(),
            lastSeenAt = requireNotNull(get(OBSERVED_TRANSACTIONS.LAST_SEEN_AT)).toInstant(),
            confirmedAt = get(OBSERVED_TRANSACTIONS.CONFIRMED_AT)?.toInstant(),
            revertedAt = get(OBSERVED_TRANSACTIONS.REVERTED_AT)?.toInstant(),
            version = requireNotNull(get(OBSERVED_TRANSACTIONS.VERSION)),
            createdAt = requireNotNull(get(OBSERVED_TRANSACTIONS.CREATED_AT)).toInstant(),
            updatedAt = requireNotNull(get(OBSERVED_TRANSACTIONS.UPDATED_AT)).toInstant(),
        )
}
