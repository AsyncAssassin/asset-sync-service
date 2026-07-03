package com.example.assetsync.unit

import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.application.outbox.OutboxBatchProcessor
import com.example.assetsync.application.outbox.OutboxEvent
import com.example.assetsync.application.outbox.OutboxEventRepository
import com.example.assetsync.application.outbox.OutboxFailedUpdate
import com.example.assetsync.application.outbox.OutboxProcessingResult
import com.example.assetsync.application.outbox.OutboxPublishedUpdate
import com.example.assetsync.infrastructure.outbox.OutboxPublisherJob
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class OutboxPublisherJobTests {

    @Test
    fun `scheduler tick failure is counted and swallowed`() {
        val registry = SimpleMeterRegistry()
        val processor = ThrowingOutboxBatchProcessor()
        val job = OutboxPublisherJob(
            outboxBatchProcessor = processor,
            metrics = AssetSyncMetrics(registry, EmptyOutboxEventRepository),
        )

        job.publishDueEvents()

        assertEquals(1, processor.calls)
        assertEquals(
            1.0,
            registry.counter("asset.sync.outbox.scheduler.ticks", "result", "FAILED").count(),
        )
    }

    private class ThrowingOutboxBatchProcessor : OutboxBatchProcessor {
        var calls: Int = 0

        override fun processDueBatch(): OutboxProcessingResult {
            calls += 1
            throw IllegalStateException("boom")
        }
    }

    private object EmptyOutboxEventRepository : OutboxEventRepository {
        override fun claimDueEvents(limit: Int, now: Instant, leaseUntil: Instant): List<OutboxEvent> =
            emptyList()

        override fun markPublished(update: OutboxPublishedUpdate): Boolean =
            true

        override fun markFailed(update: OutboxFailedUpdate): Boolean =
            true

        override fun countBacklog(): Int =
            0

        override fun countDead(): Int =
            0

        override fun deletePublishedBefore(cutoff: Instant, limit: Int): Int =
            0
    }
}
