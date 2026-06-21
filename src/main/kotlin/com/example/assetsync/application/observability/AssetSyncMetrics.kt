package com.example.assetsync.application.observability

import com.example.assetsync.application.outbox.OutboxEventRepository
import com.example.assetsync.application.sync.SyncRunStatus
import com.example.assetsync.application.sync.SyncTargetType
import com.example.assetsync.domain.model.OutboxEventType
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.model.TransitionOutcome
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
