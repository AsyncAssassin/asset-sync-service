package com.example.assetsync.application.transaction

import com.example.assetsync.application.account.ChainConfigRepository
import com.example.assetsync.application.account.UnsupportedChainException
import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.account.WatchedAddressRepository
import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.domain.model.IncomingObservedTransaction
import com.example.assetsync.domain.model.ObservedTransactionNaturalKey
import com.example.assetsync.domain.model.OutboxEventType
import com.example.assetsync.domain.model.TransactionLifecycleState
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.model.TransactionTransitionResult
import com.example.assetsync.domain.model.TransitionOutcome
import com.example.assetsync.domain.state.TransactionStateMachine
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ObservedEventApplicationService(
    private val watchedAddressRepository: WatchedAddressRepository,
    private val chainConfigRepository: ChainConfigRepository,
    private val observedTransactionRepository: ObservedTransactionRepository,
    private val outboxRepository: OutboxRepository,
    private val metrics: AssetSyncMetrics,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(ObservedEventApplicationService::class.java)
    private val stateMachine = TransactionStateMachine()

    @Transactional
    fun ingest(command: IngestObservedEventCommand): ObservedEventIngestionResult {
        val incoming = command.toIncomingObservedTransaction()
        val naturalKey = incoming.naturalKey()

        val watchedAddress = watchedAddressRepository.findActiveByNaturalKey(
            chainId = naturalKey.chainId,
            address = naturalKey.address,
            asset = naturalKey.asset,
        ) ?: throw WatchedAddressNotFoundException(
            chainId = naturalKey.chainId,
            address = naturalKey.address,
            asset = naturalKey.asset,
        )

        val chainConfig = chainConfigRepository.findEnabledByChainId(naturalKey.chainId)
            ?: throw UnsupportedChainException(naturalKey.chainId)

        val current = observedTransactionRepository.findByNaturalKeyForUpdate(naturalKey)
        return if (current == null) {
            createOrReloadAfterInsertRace(
                watchedAddress = watchedAddress,
                incoming = incoming,
                requiredConfirmations = chainConfig.requiredConfirmations,
            )
        } else {
            evaluateExisting(
                watchedAddress = watchedAddress,
                current = current,
                incoming = incoming,
                requiredConfirmations = chainConfig.requiredConfirmations,
            )
        }
    }

    private fun createOrReloadAfterInsertRace(
        watchedAddress: WatchedAddress,
        incoming: IncomingObservedTransaction,
        requiredConfirmations: Int,
    ): ObservedEventIngestionResult {
        val transition = stateMachine.evaluate(
            current = null,
            incoming = incoming,
            requiredConfirmations = requiredConfirmations,
        )
        val now = Instant.now(clock)
        val inserted = observedTransactionRepository.insertOrNull(
            NewObservedTransaction(
                id = UUID.randomUUID(),
                chainId = incoming.chainId,
                txHash = incoming.txHash,
                eventIndex = incoming.eventIndex,
                watchedAddressId = watchedAddress.id,
                address = incoming.address,
                asset = incoming.asset,
                direction = incoming.direction,
                amount = incoming.amount,
                lifecycleState = transition.state,
                firstSeenAt = now,
                lastSeenAt = now,
                confirmedAt = transition.state.confirmedAt(now),
                revertedAt = transition.state.revertedAt(now),
                version = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )

        if (inserted == null) {
            val existing = observedTransactionRepository.findByNaturalKeyForUpdate(incoming.naturalKey())
                ?: error("Observed transaction insert conflicted but no row could be reloaded.")
            logger.info(
                "observed_event_insert_race accountId={} watchedAddressId={} chainId={} address={} asset={} txHash={} eventIndex={}",
                watchedAddress.accountId,
                watchedAddress.id,
                incoming.chainId,
                incoming.address,
                incoming.asset,
                incoming.txHash,
                incoming.eventIndex,
            )
            return evaluateExisting(
                watchedAddress = watchedAddress,
                current = existing,
                incoming = incoming,
                requiredConfirmations = requiredConfirmations,
            )
        }

        return resultForPersistedTransition(
            watchedAddress = watchedAddress,
            transaction = inserted,
            transition = transition,
            occurredAt = now,
        )
    }

    private fun evaluateExisting(
        watchedAddress: WatchedAddress,
        current: ObservedTransaction,
        incoming: IncomingObservedTransaction,
        requiredConfirmations: Int,
    ): ObservedEventIngestionResult {
        val transition = stateMachine.evaluate(
            current = current.toCurrentSnapshot(),
            incoming = incoming,
            requiredConfirmations = requiredConfirmations,
        )

        if (transition is TransactionTransitionResult.Conflict) {
            metrics.recordObservedEventIngested(
                result = TransitionOutcome.CONFLICT,
                status = transition.resultingStatus,
            )
            metrics.recordImmutableConflict()
            logger.warn(
                "observed_event_ingested result={} status={} accountId={} watchedAddressId={} transactionId={} chainId={} address={} asset={} txHash={} eventIndex={} conflictingFields={}",
                TransitionOutcome.CONFLICT,
                transition.resultingStatus,
                watchedAddress.accountId,
                watchedAddress.id,
                current.id,
                incoming.chainId,
                incoming.address,
                incoming.asset,
                incoming.txHash,
                incoming.eventIndex,
                transition.conflictingFields.map { it.name },
            )
            throw ObservedTransactionConflictException(
                naturalKey = transition.naturalKey,
                conflictingFields = transition.conflictingFields,
            )
        }

        if (!transition.shouldPersist) {
            val result = ObservedEventIngestionResult(
                transactionId = current.id,
                result = TransitionOutcome.NO_CHANGE,
                status = transition.resultingStatus,
                outboxEvents = emptyList(),
            )
            recordIngestionResult(
                watchedAddress = watchedAddress,
                naturalKey = incoming.naturalKey(),
                result = result,
            )
            return result
        }

        val now = Instant.now(clock)
        val updated = observedTransactionRepository.updateLifecycle(
            ObservedTransactionLifecycleUpdate(
                id = current.id,
                lifecycleState = transition.state,
                lastSeenAt = now,
                confirmedAt = current.confirmedAt ?: transition.state.confirmedAt(now),
                revertedAt = current.revertedAt ?: transition.state.revertedAt(now),
                version = current.version + 1,
                updatedAt = now,
            ),
        )

        return resultForPersistedTransition(
            watchedAddress = watchedAddress,
            transaction = updated,
            transition = transition,
            occurredAt = now,
        )
    }

    private fun resultForPersistedTransition(
        watchedAddress: WatchedAddress,
        transaction: ObservedTransaction,
        transition: TransactionTransitionResult,
        occurredAt: Instant,
    ): ObservedEventIngestionResult {
        val eventType = transition.outboxEventType
        var outboxEventId: UUID? = null
        var outboxCreated = false
        val outboxEvents = if (eventType == null) {
            emptyList()
        } else {
            val outboxEvent = transaction.toOutboxEvent(
                eventType = eventType,
                occurredAt = occurredAt,
            )
            val inserted = outboxRepository.insert(outboxEvent)
            outboxEventId = outboxEvent.id.takeIf { inserted }
            outboxCreated = inserted
            if (inserted) listOf(eventType) else emptyList()
        }

        val result = ObservedEventIngestionResult(
            transactionId = transaction.id,
            result = transition.outcome,
            status = transition.resultingStatus,
            outboxEvents = outboxEvents,
        )
        recordIngestionResult(
            watchedAddress = watchedAddress,
            naturalKey = transaction.naturalKey(),
            result = result,
        )
        if (eventType != null) {
            metrics.recordObservedTransactionTransition(
                eventType = eventType,
                status = transition.resultingStatus,
            )
            logger.info(
                "observed_transaction_transition result={} status={} accountId={} watchedAddressId={} transactionId={} chainId={} address={} asset={} txHash={} eventIndex={} eventType={} outboxCreated={} outboxEventId={}",
                transition.outcome,
                transition.resultingStatus,
                watchedAddress.accountId,
                watchedAddress.id,
                transaction.id,
                transaction.chainId,
                transaction.address,
                transaction.asset,
                transaction.txHash,
                transaction.eventIndex,
                eventType,
                outboxCreated,
                outboxEventId,
            )
        }
        return result
    }

    private fun recordIngestionResult(
        watchedAddress: WatchedAddress,
        naturalKey: ObservedTransactionNaturalKey,
        result: ObservedEventIngestionResult,
    ) {
        metrics.recordObservedEventIngested(
            result = result.result,
            status = result.status,
        )
        logger.info(
            "observed_event_ingested result={} status={} accountId={} watchedAddressId={} transactionId={} chainId={} address={} asset={} txHash={} eventIndex={} outboxEventTypes={}",
            result.result,
            result.status,
            watchedAddress.accountId,
            watchedAddress.id,
            result.transactionId,
            naturalKey.chainId,
            naturalKey.address,
            naturalKey.asset,
            naturalKey.txHash,
            naturalKey.eventIndex,
            result.outboxEvents.map { it.name },
        )
    }

    private fun IngestObservedEventCommand.toIncomingObservedTransaction(): IncomingObservedTransaction =
        IncomingObservedTransaction(
            chainId = chainId.trim(),
            txHash = txHash.trim(),
            eventIndex = eventIndex,
            address = address.trim(),
            asset = asset.trim(),
            direction = direction,
            amount = amount,
            blockHeight = blockHeight,
            confirmations = confirmations,
            status = status,
        )

    private fun TransactionLifecycleState.confirmedAt(now: Instant): Instant? =
        now.takeIf { status == TransactionStatus.CONFIRMED }

    private fun TransactionLifecycleState.revertedAt(now: Instant): Instant? =
        now.takeIf { status == TransactionStatus.REVERTED }

    private fun ObservedTransaction.toOutboxEvent(
        eventType: OutboxEventType,
        occurredAt: Instant,
    ): NewOutboxEvent {
        val eventId = UUID.randomUUID()
        return NewOutboxEvent(
            id = eventId,
            aggregateType = "OBSERVED_TRANSACTION",
            aggregateId = id,
            eventType = eventType,
            idempotencyKey = naturalKey().outboxIdempotencyKey(status),
            payload = ObservedTransactionOutboxPayload(
                eventId = eventId,
                eventType = eventType.name,
                occurredAt = occurredAt,
                transactionId = id,
                chainId = chainId,
                txHash = txHash,
                eventIndex = eventIndex,
                address = address,
                asset = asset,
                amount = amount.toPlainString(),
                direction = direction.name,
                status = status.name,
                confirmations = confirmations,
                blockHeight = blockHeight,
            ),
            createdAt = occurredAt,
            updatedAt = occurredAt,
        )
    }

    private fun ObservedTransaction.naturalKey(): ObservedTransactionNaturalKey =
        ObservedTransactionNaturalKey(
            chainId = chainId,
            txHash = txHash,
            eventIndex = eventIndex,
            address = address,
            asset = asset,
        )
}
