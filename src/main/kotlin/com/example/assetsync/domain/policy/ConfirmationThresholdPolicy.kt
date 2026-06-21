package com.example.assetsync.domain.policy

import com.example.assetsync.domain.model.IncomingObservedTransaction
import com.example.assetsync.domain.model.TransactionStatus

object ConfirmationThresholdPolicy {

    fun effectiveStatus(
        incoming: IncomingObservedTransaction,
        requiredConfirmations: Int,
    ): TransactionStatus {
        require(requiredConfirmations >= 0) { "requiredConfirmations must be non-negative." }

        return when {
            incoming.status == TransactionStatus.REVERTED -> TransactionStatus.REVERTED
            incoming.status == TransactionStatus.CONFIRMED -> TransactionStatus.CONFIRMED
            incoming.confirmations >= requiredConfirmations -> TransactionStatus.CONFIRMED
            else -> TransactionStatus.SEEN
        }
    }
}
