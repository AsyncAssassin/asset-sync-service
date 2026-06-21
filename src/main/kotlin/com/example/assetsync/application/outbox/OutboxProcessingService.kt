package com.example.assetsync.application.outbox

import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.config.OutboxProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OutboxProcessingService(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventPublisher: OutboxEventPublisher,
    private val outboxProperties: OutboxProperties,
    private val metrics: AssetSyncMetrics,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(OutboxProcessingService::class.java)

    @Transactional
    fun processDueBatch(): OutboxProcessingResult =
        processDueBatch(outboxProperties.batchSize)

    @Transactional
    fun processDueBatch(batchSize: Int): OutboxProcessingResult {
        require(batchSize > 0) { "batchSize must be positive." }

        val events = outboxEventRepository.claimDueEvents(
            limit = batchSize,
            now = Instant.now(clock),
        )
        if (events.isEmpty()) {
            logger.debug("outbox_batch_claimed claimed={} batchSize={}", events.size, batchSize)
        } else {
            logger.info("outbox_batch_claimed claimed={} batchSize={}", events.size, batchSize)
        }
        var published = 0
        var failed = 0

        events.forEach { event ->
            val logFields = event.toLogFields()
            try {
                outboxEventPublisher.publish(event)
            } catch (exception: Exception) {
                val attempts = event.attempts + 1
                val failedAt = Instant.now(clock)
                outboxEventRepository.markFailed(
                    OutboxFailedUpdate(
                        id = event.id,
                        attempts = attempts,
                        lastError = exception.toBoundedError(),
                        nextAttemptAt = failedAt.plus(backoffDelay(attempts)),
                        updatedAt = failedAt,
                    ),
                )
                metrics.recordOutboxEventFailed(event.eventType)
                logger.warn(
                    "outbox_event_publish_failed outboxEventId={} eventType={} transactionId={} chainId={} address={} asset={} txHash={} eventIndex={} transactionStatus={} outboxStatus={} attempts={} nextAttemptAt={} error={}",
                    event.id,
                    event.eventType,
                    logFields.transactionId,
                    logFields.chainId,
                    logFields.address,
                    logFields.asset,
                    logFields.txHash,
                    logFields.eventIndex,
                    logFields.transactionStatus,
                    OutboxStatus.FAILED,
                    attempts,
                    failedAt.plus(backoffDelay(attempts)),
                    exception.toBoundedError(),
                )
                failed += 1
                return@forEach
            }

            val publishedAt = Instant.now(clock)
            outboxEventRepository.markPublished(
                OutboxPublishedUpdate(
                    id = event.id,
                    publishedAt = publishedAt,
                    updatedAt = publishedAt,
                ),
            )
            metrics.recordOutboxEventPublished(event.eventType)
            logger.info(
                "outbox_event_publish_succeeded outboxEventId={} eventType={} transactionId={} chainId={} address={} asset={} txHash={} eventIndex={} transactionStatus={} outboxStatus={} attempts={}",
                event.id,
                event.eventType,
                logFields.transactionId,
                logFields.chainId,
                logFields.address,
                logFields.asset,
                logFields.txHash,
                logFields.eventIndex,
                logFields.transactionStatus,
                OutboxStatus.PUBLISHED,
                event.attempts,
            )
            published += 1
        }

        val result = OutboxProcessingResult(
            claimed = events.size,
            published = published,
            failed = failed,
        )
        metrics.recordOutboxBatch(
            claimed = result.claimed,
            published = result.published,
            failed = result.failed,
        )
        if (result.claimed == 0) {
            logger.debug(
                "outbox_batch_processed claimed={} published={} failed={}",
                result.claimed,
                result.published,
                result.failed,
            )
        } else {
            logger.info(
                "outbox_batch_processed claimed={} published={} failed={}",
                result.claimed,
                result.published,
                result.failed,
            )
        }
        return result
    }

    private fun backoffDelay(attempts: Int): Duration =
        outboxProperties.retryBackoffBaseDelay.multipliedBy(attempts.coerceAtLeast(1).toLong())

    private fun Exception.toBoundedError(): String {
        val summary = "${this::class.java.simpleName}: ${message ?: "outbox publish failed"}"
        return summary.take(outboxProperties.maxErrorLength)
    }
}
