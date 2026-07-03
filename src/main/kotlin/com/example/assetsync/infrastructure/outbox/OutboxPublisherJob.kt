package com.example.assetsync.infrastructure.outbox

import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.application.outbox.OutboxBatchProcessor
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "asset-sync.outbox.scheduler",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class OutboxPublisherJob(
    private val outboxBatchProcessor: OutboxBatchProcessor,
    private val metrics: AssetSyncMetrics,
) {
    private val logger = LoggerFactory.getLogger(OutboxPublisherJob::class.java)

    @Scheduled(
        fixedDelayString = "\${asset-sync.outbox.scheduler.fixed-delay:5s}",
        initialDelayString = "\${asset-sync.outbox.scheduler.initial-delay:10s}",
    )
    fun publishDueEvents() {
        try {
            outboxBatchProcessor.processDueBatch()
        } catch (exception: Exception) {
            metrics.recordOutboxSchedulerTickFailed()
            logger.error(
                "outbox_scheduler_tick_failed exceptionClass={} error={}",
                exception.javaClass.simpleName,
                exception.message ?: "outbox scheduler tick failed",
                exception,
            )
        }
    }
}
