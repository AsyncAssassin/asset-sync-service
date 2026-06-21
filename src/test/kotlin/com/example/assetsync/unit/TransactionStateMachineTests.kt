package com.example.assetsync.unit

import com.example.assetsync.domain.model.CurrentTransactionSnapshot
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.ImmutableTransactionField
import com.example.assetsync.domain.model.IncomingObservedTransaction
import com.example.assetsync.domain.model.ObservedTransactionNaturalKey
import com.example.assetsync.domain.model.OutboxEventType
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.model.TransactionTransitionResult
import com.example.assetsync.domain.model.TransitionOutcome
import com.example.assetsync.domain.state.TransactionStateMachine
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionStateMachineTests {

    private val stateMachine = TransactionStateMachine()

    @Test
    fun `new events create lifecycle rows with status specific outbox events`() {
        assertTransition(
            result = stateMachine.evaluate(
                current = null,
                incoming = incoming(status = TransactionStatus.SEEN, confirmations = 1),
                requiredConfirmations = 3,
            ),
            outcome = TransitionOutcome.CREATED,
            resultingStatus = TransactionStatus.SEEN,
            storedConfirmations = 1,
            shouldPersist = true,
            outboxEventType = OutboxEventType.TRANSACTION_SEEN,
        )

        assertTransition(
            result = stateMachine.evaluate(
                current = null,
                incoming = incoming(status = TransactionStatus.CONFIRMED, confirmations = 0),
                requiredConfirmations = 3,
            ),
            outcome = TransitionOutcome.CREATED,
            resultingStatus = TransactionStatus.CONFIRMED,
            storedConfirmations = 0,
            shouldPersist = true,
            outboxEventType = OutboxEventType.TRANSACTION_CONFIRMED,
        )

        assertTransition(
            result = stateMachine.evaluate(
                current = null,
                incoming = incoming(status = TransactionStatus.REVERTED, confirmations = 10),
                requiredConfirmations = 3,
            ),
            outcome = TransitionOutcome.CREATED,
            resultingStatus = TransactionStatus.REVERTED,
            storedConfirmations = 10,
            shouldPersist = true,
            outboxEventType = OutboxEventType.TRANSACTION_REVERTED,
        )
    }

    @Test
    fun `seen row updates confirmations below threshold without outbox event`() {
        val result = stateMachine.evaluate(
            current = current(status = TransactionStatus.SEEN, confirmations = 1, blockHeight = 100),
            incoming = incoming(status = TransactionStatus.SEEN, confirmations = 2, blockHeight = 101),
            requiredConfirmations = 3,
        )

        assertIs<TransactionTransitionResult.Updated>(result)
        assertTransition(
            result = result,
            outcome = TransitionOutcome.UPDATED,
            resultingStatus = TransactionStatus.SEEN,
            storedConfirmations = 2,
            storedBlockHeight = 101,
            shouldPersist = true,
            outboxEventType = null,
        )
    }

    @Test
    fun `seen duplicate and lower stale confirmations are no change`() {
        val duplicate = stateMachine.evaluate(
            current = current(status = TransactionStatus.SEEN, confirmations = 2),
            incoming = incoming(status = TransactionStatus.SEEN, confirmations = 2),
            requiredConfirmations = 3,
        )
        val staleLower = stateMachine.evaluate(
            current = current(status = TransactionStatus.SEEN, confirmations = 2),
            incoming = incoming(status = TransactionStatus.SEEN, confirmations = 1),
            requiredConfirmations = 3,
        )

        assertIs<TransactionTransitionResult.NoChange>(duplicate)
        assertIs<TransactionTransitionResult.NoChange>(staleLower)
        assertTransition(
            result = duplicate,
            outcome = TransitionOutcome.NO_CHANGE,
            resultingStatus = TransactionStatus.SEEN,
            storedConfirmations = 2,
            shouldPersist = false,
            outboxEventType = null,
        )
        assertTransition(
            result = staleLower,
            outcome = TransitionOutcome.NO_CHANGE,
            resultingStatus = TransactionStatus.SEEN,
            storedConfirmations = 2,
            shouldPersist = false,
            outboxEventType = null,
        )
    }

    @Test
    fun `seen row transitions to confirmed by threshold or provider status`() {
        val thresholdReached = stateMachine.evaluate(
            current = current(status = TransactionStatus.SEEN, confirmations = 2),
            incoming = incoming(status = TransactionStatus.SEEN, confirmations = 3),
            requiredConfirmations = 3,
        )
        val providerConfirmed = stateMachine.evaluate(
            current = current(status = TransactionStatus.SEEN, confirmations = 2),
            incoming = incoming(status = TransactionStatus.CONFIRMED, confirmations = 1),
            requiredConfirmations = 3,
        )

        assertIs<TransactionTransitionResult.Updated>(thresholdReached)
        assertIs<TransactionTransitionResult.Updated>(providerConfirmed)
        assertTransition(
            result = thresholdReached,
            outcome = TransitionOutcome.UPDATED,
            resultingStatus = TransactionStatus.CONFIRMED,
            storedConfirmations = 3,
            shouldPersist = true,
            outboxEventType = OutboxEventType.TRANSACTION_CONFIRMED,
        )
        assertTransition(
            result = providerConfirmed,
            outcome = TransitionOutcome.UPDATED,
            resultingStatus = TransactionStatus.CONFIRMED,
            storedConfirmations = 2,
            shouldPersist = true,
            outboxEventType = OutboxEventType.TRANSACTION_CONFIRMED,
        )
    }

    @Test
    fun `seen row transitions to reverted`() {
        val result = stateMachine.evaluate(
            current = current(status = TransactionStatus.SEEN, confirmations = 2),
            incoming = incoming(status = TransactionStatus.REVERTED, confirmations = 0),
            requiredConfirmations = 3,
        )

        assertIs<TransactionTransitionResult.Updated>(result)
        assertTransition(
            result = result,
            outcome = TransitionOutcome.UPDATED,
            resultingStatus = TransactionStatus.REVERTED,
            storedConfirmations = 2,
            shouldPersist = true,
            outboxEventType = OutboxEventType.TRANSACTION_REVERTED,
        )
    }

    @Test
    fun `confirmed row updates higher confirmations without another outbox event`() {
        val result = stateMachine.evaluate(
            current = current(status = TransactionStatus.CONFIRMED, confirmations = 3, blockHeight = 100),
            incoming = incoming(status = TransactionStatus.CONFIRMED, confirmations = 4, blockHeight = 102),
            requiredConfirmations = 3,
        )

        assertIs<TransactionTransitionResult.Updated>(result)
        assertTransition(
            result = result,
            outcome = TransitionOutcome.UPDATED,
            resultingStatus = TransactionStatus.CONFIRMED,
            storedConfirmations = 4,
            storedBlockHeight = 102,
            shouldPersist = true,
            outboxEventType = null,
        )
    }

    @Test
    fun `confirmed duplicate and stale seen events are no change`() {
        val duplicate = stateMachine.evaluate(
            current = current(status = TransactionStatus.CONFIRMED, confirmations = 3),
            incoming = incoming(status = TransactionStatus.CONFIRMED, confirmations = 3),
            requiredConfirmations = 3,
        )
        val staleSeen = stateMachine.evaluate(
            current = current(status = TransactionStatus.CONFIRMED, confirmations = 3),
            incoming = incoming(status = TransactionStatus.SEEN, confirmations = 2),
            requiredConfirmations = 3,
        )
        val lowerConfirmed = stateMachine.evaluate(
            current = current(status = TransactionStatus.CONFIRMED, confirmations = 5),
            incoming = incoming(status = TransactionStatus.CONFIRMED, confirmations = 4),
            requiredConfirmations = 3,
        )

        listOf(duplicate, staleSeen, lowerConfirmed).forEach { result ->
            assertIs<TransactionTransitionResult.NoChange>(result)
            assertTransition(
                result = result,
                outcome = TransitionOutcome.NO_CHANGE,
                resultingStatus = TransactionStatus.CONFIRMED,
                storedConfirmations = result.storedConfirmations,
                shouldPersist = false,
                outboxEventType = null,
            )
        }
        assertEquals(3, duplicate.storedConfirmations)
        assertEquals(3, staleSeen.storedConfirmations)
        assertEquals(5, lowerConfirmed.storedConfirmations)
    }

    @Test
    fun `confirmed row transitions to reverted`() {
        val result = stateMachine.evaluate(
            current = current(status = TransactionStatus.CONFIRMED, confirmations = 5),
            incoming = incoming(status = TransactionStatus.REVERTED, confirmations = 1),
            requiredConfirmations = 3,
        )

        assertIs<TransactionTransitionResult.Updated>(result)
        assertTransition(
            result = result,
            outcome = TransitionOutcome.UPDATED,
            resultingStatus = TransactionStatus.REVERTED,
            storedConfirmations = 5,
            shouldPersist = true,
            outboxEventType = OutboxEventType.TRANSACTION_REVERTED,
        )
    }

    @Test
    fun `reverted is terminal for seen confirmed and reverted incoming statuses`() {
        val incomingSeen = stateMachine.evaluate(
            current = current(status = TransactionStatus.REVERTED, confirmations = 5),
            incoming = incoming(status = TransactionStatus.SEEN, confirmations = 6),
            requiredConfirmations = 10,
        )
        val incomingConfirmed = stateMachine.evaluate(
            current = current(status = TransactionStatus.REVERTED, confirmations = 5),
            incoming = incoming(status = TransactionStatus.CONFIRMED, confirmations = 6),
            requiredConfirmations = 3,
        )
        val incomingReverted = stateMachine.evaluate(
            current = current(status = TransactionStatus.REVERTED, confirmations = 5),
            incoming = incoming(status = TransactionStatus.REVERTED, confirmations = 6),
            requiredConfirmations = 3,
        )

        listOf(incomingSeen, incomingConfirmed, incomingReverted).forEach { result ->
            assertIs<TransactionTransitionResult.NoChange>(result)
            assertTransition(
                result = result,
                outcome = TransitionOutcome.NO_CHANGE,
                resultingStatus = TransactionStatus.REVERTED,
                storedConfirmations = 5,
                shouldPersist = false,
                outboxEventType = null,
            )
        }
    }

    @Test
    fun `threshold zero immediately confirms non reverted events`() {
        val result = stateMachine.evaluate(
            current = null,
            incoming = incoming(status = TransactionStatus.SEEN, confirmations = 0),
            requiredConfirmations = 0,
        )

        assertIs<TransactionTransitionResult.Created>(result)
        assertTransition(
            result = result,
            outcome = TransitionOutcome.CREATED,
            resultingStatus = TransactionStatus.CONFIRMED,
            storedConfirmations = 0,
            shouldPersist = true,
            outboxEventType = OutboxEventType.TRANSACTION_CONFIRMED,
        )
    }

    @Test
    fun `amount mismatch returns conflict without outbox event`() {
        val result = stateMachine.evaluate(
            current = current(amount = BigDecimal("12.34")),
            incoming = incoming(amount = BigDecimal("12.35")),
            requiredConfirmations = 3,
        )

        val conflict = assertIs<TransactionTransitionResult.Conflict>(result)
        assertTransition(
            result = conflict,
            outcome = TransitionOutcome.CONFLICT,
            resultingStatus = TransactionStatus.SEEN,
            storedConfirmations = 1,
            shouldPersist = false,
            outboxEventType = null,
        )
        assertEquals(setOf(ImmutableTransactionField.AMOUNT), conflict.conflictingFields)
    }

    @Test
    fun `direction mismatch returns conflict without outbox event`() {
        val result = stateMachine.evaluate(
            current = current(direction = Direction.INBOUND),
            incoming = incoming(direction = Direction.OUTBOUND),
            requiredConfirmations = 3,
        )

        val conflict = assertIs<TransactionTransitionResult.Conflict>(result)
        assertFalse(conflict.shouldPersist)
        assertNull(conflict.outboxEventType)
        assertEquals(setOf(ImmutableTransactionField.DIRECTION), conflict.conflictingFields)
    }

    @Test
    fun `amount comparison uses BigDecimal compareTo semantics`() {
        val result = stateMachine.evaluate(
            current = current(amount = BigDecimal("12.340")),
            incoming = incoming(amount = BigDecimal("12.340000000000000000"), confirmations = 2),
            requiredConfirmations = 3,
        )

        assertIs<TransactionTransitionResult.Updated>(result)
        assertTrue(result.shouldPersist)
        assertNull(result.outboxEventType)
        assertEquals(TransactionStatus.SEEN, result.resultingStatus)
    }

    @Test
    fun `no outbox event is emitted on no change or conflict`() {
        val noChange = stateMachine.evaluate(
            current = current(status = TransactionStatus.CONFIRMED, confirmations = 5),
            incoming = incoming(status = TransactionStatus.SEEN, confirmations = 1),
            requiredConfirmations = 3,
        )
        val conflict = stateMachine.evaluate(
            current = current(amount = BigDecimal("1.00")),
            incoming = incoming(amount = BigDecimal("2.00")),
            requiredConfirmations = 3,
        )

        assertIs<TransactionTransitionResult.NoChange>(noChange)
        assertIs<TransactionTransitionResult.Conflict>(conflict)
        assertNull(noChange.outboxEventType)
        assertNull(conflict.outboxEventType)
        assertFalse(noChange.shouldPersist)
        assertFalse(conflict.shouldPersist)
    }

    @Test
    fun `natural key builds status specific outbox idempotency keys`() {
        val naturalKey = ObservedTransactionNaturalKey(
            chainId = "local-evm",
            txHash = "0xdeadbeef",
            eventIndex = 7,
            address = "0xabc123",
            asset = "USDC",
        )

        assertEquals(
            "observed-tx:local-evm:0xdeadbeef:7:0xabc123:USDC:status:SEEN",
            naturalKey.outboxIdempotencyKey(TransactionStatus.SEEN),
        )
        assertEquals(
            "observed-tx:local-evm:0xdeadbeef:7:0xabc123:USDC:status:CONFIRMED",
            naturalKey.outboxIdempotencyKey(TransactionStatus.CONFIRMED),
        )
        assertEquals(
            "observed-tx:local-evm:0xdeadbeef:7:0xabc123:USDC:status:REVERTED",
            naturalKey.outboxIdempotencyKey(TransactionStatus.REVERTED),
        )
    }

    private fun assertTransition(
        result: TransactionTransitionResult,
        outcome: TransitionOutcome,
        resultingStatus: TransactionStatus,
        storedConfirmations: Int,
        storedBlockHeight: Long = result.storedBlockHeight,
        shouldPersist: Boolean,
        outboxEventType: OutboxEventType?,
    ) {
        assertEquals(outcome, result.outcome)
        assertEquals(resultingStatus, result.resultingStatus)
        assertEquals(storedConfirmations, result.storedConfirmations)
        assertEquals(storedBlockHeight, result.storedBlockHeight)
        assertEquals(shouldPersist, result.shouldPersist)
        assertEquals(outboxEventType, result.outboxEventType)
    }

    private fun current(
        chainId: String = "local-evm",
        txHash: String = "0xdeadbeef",
        eventIndex: Int = 0,
        address: String = "0xabc123",
        asset: String = "USDC",
        direction: Direction = Direction.INBOUND,
        amount: BigDecimal = BigDecimal("12.340000000000000000"),
        blockHeight: Long = 100,
        confirmations: Int = 1,
        status: TransactionStatus = TransactionStatus.SEEN,
    ): CurrentTransactionSnapshot =
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

    private fun incoming(
        chainId: String = "local-evm",
        txHash: String = "0xdeadbeef",
        eventIndex: Int = 0,
        address: String = "0xabc123",
        asset: String = "USDC",
        direction: Direction = Direction.INBOUND,
        amount: BigDecimal = BigDecimal("12.340000000000000000"),
        blockHeight: Long = 101,
        confirmations: Int = 1,
        status: TransactionStatus = TransactionStatus.SEEN,
    ): IncomingObservedTransaction =
        IncomingObservedTransaction(
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
