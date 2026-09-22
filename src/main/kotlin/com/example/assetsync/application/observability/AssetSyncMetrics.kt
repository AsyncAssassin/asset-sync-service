package com.example.assetsync.application.observability

import com.example.assetsync.application.outbox.OutboxEventRepository
import com.example.assetsync.application.sync.SyncRunRequeueReason
import com.example.assetsync.application.sync.SyncRunStatus
import com.example.assetsync.application.sync.SyncTargetType
import com.example.assetsync.domain.model.OutboxEventType
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.model.TransitionOutcome
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class AssetSyncMetrics(
    private val meterRegistry: MeterRegistry,
    outboxEventRepository: OutboxEventRepository,
) {

    init {
        Gauge
            .builder("asset.sync.outbox.backlog.total", outboxEventRepository) { repository ->
                repository.countBacklog().toDouble()
            }
            .description("Total outbox events with NEW or FAILED status.")
            .register(meterRegistry)
        Gauge
            .builder("asset.sync.outbox.dead.total", outboxEventRepository) { repository ->
                repository.countDead().toDouble()
            }
            .description("Total outbox events with DEAD status.")
            .register(meterRegistry)
    }

    fun recordObservedEventIngested(result: TransitionOutcome, status: TransactionStatus) {
        meterRegistry
            .counter(
                "asset.sync.observed.events.ingested",
                "result",
                result.name,
                "status",
                status.name,
            )
            .increment()
    }

    fun recordObservedTransactionTransition(eventType: OutboxEventType, status: TransactionStatus) {
        meterRegistry
            .counter(
                "asset.sync.observed.transaction.transitions",
                "eventType",
                eventType.name,
                "status",
                status.name,
            )
            .increment()
    }

    fun recordImmutableConflict() {
        meterRegistry
            .counter("asset.sync.observed.transaction.immutable.conflicts")
            .increment()
    }

    fun recordSyncRun(targetType: SyncTargetType, status: SyncRunStatus) {
        meterRegistry
            .counter(
                "asset.sync.sync.runs",
                "targetType",
                targetType.name,
                "status",
                status.name,
            )
            .increment()
    }

    fun startProviderFetchTimer(): Timer.Sample =
        Timer.start(meterRegistry)

    fun recordProviderFetchAttempt(targetType: SyncTargetType) {
        meterRegistry
            .counter(
                "asset.sync.provider.fetches",
                "targetType",
                targetType.name,
                "status",
                "ATTEMPTED",
            )
            .increment()
    }

    fun recordProviderFetchSuccess(targetType: SyncTargetType, sample: Timer.Sample) {
        recordProviderFetchResult(targetType = targetType, status = "SUCCEEDED", sample = sample)
    }

    fun recordProviderFetchFailure(targetType: SyncTargetType, sample: Timer.Sample) {
        recordProviderFetchResult(targetType = targetType, status = "FAILED", sample = sample)
    }

    fun recordCursorLease(result: String) {
        meterRegistry
            .counter("asset.sync.cursor.leases", "result", result)
            .increment()
    }

    fun recordProviderPage(targetType: SyncTargetType, result: String) {
        meterRegistry
            .counter(
                "asset.sync.provider.pages",
                "targetType",
                targetType.name,
                "result",
                result,
            )
            .increment()
    }

    fun recordProviderPageEvents(targetType: SyncTargetType, count: Int) {
        DistributionSummary
            .builder("asset.sync.provider.page.events")
            .description("Events returned in one provider page.")
            .tag("targetType", targetType.name)
            .register(meterRegistry)
            .record(count.toDouble())
    }

    fun recordCursorCheckpoint(result: String) {
        meterRegistry
            .counter("asset.sync.cursor.checkpoints", "result", result)
            .increment()
    }

    fun recordSyncContinuation(reason: SyncRunRequeueReason, targetType: SyncTargetType) {
        meterRegistry
            .counter(
                "asset.sync.sync.continuations",
                "reason",
                reason.name,
                "targetType",
                targetType.name,
            )
            .increment()
    }

    fun recordOutboxBatch(claimed: Int, published: Int, failed: Int) {
        meterRegistry
            .counter(
                "asset.sync.outbox.batches",
                "result",
                outboxBatchResult(claimed = claimed, published = published, failed = failed),
            )
            .increment()
    }

    fun recordOutboxEventPublished(eventType: String) {
        recordOutboxEvent(eventType = eventType, status = "PUBLISHED")
    }

    fun recordOutboxEventFailed(eventType: String) {
        recordOutboxEvent(eventType = eventType, status = "FAILED")
    }

    fun recordOutboxEventDead(eventType: String) {
        recordOutboxEvent(eventType = eventType, status = "DEAD")
    }

    fun recordOutboxEventCompletionFailed(eventType: String) {
        recordOutboxEvent(eventType = eventType, status = "COMPLETION_FAILED")
    }

    fun recordOutboxSchedulerTickFailed() {
        meterRegistry
            .counter("asset.sync.outbox.scheduler.ticks", "result", "FAILED")
            .increment()
    }

    fun startAlchemyRpcTimer(): Timer.Sample =
        Timer.start(meterRegistry)

    /** One Alchemy JSON-RPC call; `result` is `SUCCEEDED`, `UNAVAILABLE`, `INVALID`, or `CONFIGURATION`. */
    fun recordAlchemyRpc(network: String, method: String, result: String, sample: Timer.Sample) {
        meterRegistry
            .counter(
                "asset.sync.provider.alchemy.rpc",
                "network",
                network,
                "method",
                method,
                "result",
                result,
            )
            .increment()
        sample.stop(
            Timer
                .builder("asset.sync.provider.alchemy.rpc.duration")
                .description("Alchemy JSON-RPC call duration.")
                .tag("network", network)
                .tag("method", method)
                .tag("result", result)
                .register(meterRegistry),
        )
    }

    /** A block the Alchemy adapter had to drain alone because its window came back paged. */
    fun recordAlchemyBlockFallback(network: String) {
        meterRegistry
            .counter("asset.sync.provider.alchemy.block.fallbacks", "network", network)
            .increment()
    }

    /** A paged window the Alchemy adapter narrowed to the blocks before its page boundary. */
    fun recordAlchemyNarrowing(network: String) {
        meterRegistry
            .counter("asset.sync.provider.alchemy.narrowings", "network", network)
            .increment()
    }

    /** Transfer rows the Alchemy adapter skipped before emission; `reason` names the policy. */
    fun recordAlchemySkippedRows(network: String, reason: String, count: Int) {
        if (count <= 0) {
            return
        }
        meterRegistry
            .counter(
                "asset.sync.provider.alchemy.skipped.rows",
                "network",
                network,
                "reason",
                reason,
            )
            .increment(count.toDouble())
    }

    private fun recordProviderFetchResult(
        targetType: SyncTargetType,
        status: String,
        sample: Timer.Sample,
    ) {
        meterRegistry
            .counter(
                "asset.sync.provider.fetches",
                "targetType",
                targetType.name,
                "status",
                status,
            )
            .increment()

        sample.stop(
            Timer
                .builder("asset.sync.provider.fetch.duration")
                .description("Provider fetch duration.")
                .tag("targetType", targetType.name)
                .tag("status", status)
                .register(meterRegistry),
        )
    }

    private fun recordOutboxEvent(eventType: String, status: String) {
        meterRegistry
            .counter(
                "asset.sync.outbox.events",
                "eventType",
                eventType,
                "status",
                status,
            )
            .increment()
    }

    private fun outboxBatchResult(claimed: Int, published: Int, failed: Int): String =
        when {
            claimed == 0 -> "EMPTY"
            failed == 0 -> "SUCCEEDED"
            published == 0 -> "FAILED"
            else -> "PARTIAL_FAILURE"
        }
}
