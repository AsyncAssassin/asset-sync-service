package com.example.assetsync.domain.state

import com.example.assetsync.domain.model.CurrentTransactionSnapshot
import com.example.assetsync.domain.model.IncomingObservedTransaction
import com.example.assetsync.domain.model.OutboxEventType
import com.example.assetsync.domain.model.TransactionLifecycleState
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.model.TransactionTransitionResult
import com.example.assetsync.domain.policy.ConfirmationThresholdPolicy

class TransactionStateMachine {

    fun evaluate(
        current: CurrentTransactionSnapshot?,
        incoming: IncomingObservedTransaction,
        requiredConfirmations: Int,
    ): TransactionTransitionResult {
        val effectiveIncomingStatus = ConfirmationThresholdPolicy.effectiveStatus(
            incoming = incoming,
            requiredConfirmations = requiredConfirmations,
        )

        if (current == null) {
            return TransactionTransitionResult.Created(
                state = incoming.lifecycleState(effectiveIncomingStatus),
                effectiveIncomingStatus = effectiveIncomingStatus,
                outboxEventType = lifecycleOutboxEvent(effectiveIncomingStatus),
            )
        }

        val conflictingFields = current.immutableFields().conflictingFields(incoming.immutableFields())
        if (conflictingFields.isNotEmpty()) {
            return TransactionTransitionResult.Conflict(
                state = current.lifecycleState(),
                effectiveIncomingStatus = effectiveIncomingStatus,
                current = current.immutableFields(),
                incoming = incoming.immutableFields(),
                conflictingFields = conflictingFields,
            )
        }

        return when (current.status) {
            TransactionStatus.SEEN -> evaluateSeen(current, incoming, effectiveIncomingStatus)
            TransactionStatus.CONFIRMED -> evaluateConfirmed(current, incoming, effectiveIncomingStatus)
            TransactionStatus.REVERTED -> TransactionTransitionResult.NoChange(
                state = current.lifecycleState(),
                effectiveIncomingStatus = effectiveIncomingStatus,
            )
        }
    }

    private fun evaluateSeen(
        current: CurrentTransactionSnapshot,
        incoming: IncomingObservedTransaction,
        effectiveIncomingStatus: TransactionStatus,
    ): TransactionTransitionResult =
        when (effectiveIncomingStatus) {
            TransactionStatus.SEEN -> {
                if (incoming.confirmations > current.confirmations) {
                    TransactionTransitionResult.Updated(
                        state = incoming.lifecycleState(TransactionStatus.SEEN),
                        effectiveIncomingStatus = effectiveIncomingStatus,
                        outboxEventType = null,
                    )
                } else {
                    TransactionTransitionResult.NoChange(
                        state = current.lifecycleState(),
                        effectiveIncomingStatus = effectiveIncomingStatus,
                    )
                }
            }

            TransactionStatus.CONFIRMED -> TransactionTransitionResult.Updated(
                state = current.updatedLifecycleState(incoming, TransactionStatus.CONFIRMED),
                effectiveIncomingStatus = effectiveIncomingStatus,
                outboxEventType = OutboxEventType.TRANSACTION_CONFIRMED,
            )

            TransactionStatus.REVERTED -> TransactionTransitionResult.Updated(
                state = current.updatedLifecycleState(incoming, TransactionStatus.REVERTED),
                effectiveIncomingStatus = effectiveIncomingStatus,
                outboxEventType = OutboxEventType.TRANSACTION_REVERTED,
            )
        }

    private fun evaluateConfirmed(
        current: CurrentTransactionSnapshot,
        incoming: IncomingObservedTransaction,
        effectiveIncomingStatus: TransactionStatus,
    ): TransactionTransitionResult =
        when (effectiveIncomingStatus) {
            TransactionStatus.SEEN -> TransactionTransitionResult.NoChange(
                state = current.lifecycleState(),
                effectiveIncomingStatus = effectiveIncomingStatus,
            )

            TransactionStatus.CONFIRMED -> {
                if (incoming.confirmations > current.confirmations) {
                    TransactionTransitionResult.Updated(
                        state = incoming.lifecycleState(TransactionStatus.CONFIRMED),
                        effectiveIncomingStatus = effectiveIncomingStatus,
                        outboxEventType = null,
                    )
                } else {
                    TransactionTransitionResult.NoChange(
                        state = current.lifecycleState(),
                        effectiveIncomingStatus = effectiveIncomingStatus,
                    )
                }
            }

            TransactionStatus.REVERTED -> TransactionTransitionResult.Updated(
                state = current.updatedLifecycleState(incoming, TransactionStatus.REVERTED),
                effectiveIncomingStatus = effectiveIncomingStatus,
                outboxEventType = OutboxEventType.TRANSACTION_REVERTED,
            )
        }

    private fun CurrentTransactionSnapshot.updatedLifecycleState(
        incoming: IncomingObservedTransaction,
        status: TransactionStatus,
    ): TransactionLifecycleState =
        TransactionLifecycleState(
            blockHeight = incoming.blockHeight,
            confirmations = maxOf(confirmations, incoming.confirmations),
            status = status,
        )

    private fun lifecycleOutboxEvent(status: TransactionStatus): OutboxEventType =
        when (status) {
            TransactionStatus.SEEN -> OutboxEventType.TRANSACTION_SEEN
            TransactionStatus.CONFIRMED -> OutboxEventType.TRANSACTION_CONFIRMED
            TransactionStatus.REVERTED -> OutboxEventType.TRANSACTION_REVERTED
        }
}
