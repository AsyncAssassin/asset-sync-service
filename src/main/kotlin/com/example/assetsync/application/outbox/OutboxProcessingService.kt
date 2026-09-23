package com.example.assetsync.application.outbox

import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.config.OutboxProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class OutboxProcessingService(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventPublisher: OutboxEventPublisher,
    private val outboxProperties: OutboxProperties,
    private val metrics: AssetSyncMetrics,
    private val clock: Clock,
    private val transactionTemplate: TransactionTemplate,
) : OutboxBatchProcessor {
    private val logger = LoggerFactory.getLogger(OutboxProcessingService::class.java)

    override fun processDueBatch(): OutboxProcessingResult =
        processDueBatch(outboxProperties.batchSize)

    fun processDueBatch(batchSize: Int): OutboxProcessingResult {
        require(batchSize > 0) { "batchSize must be positive." }

        val claimStartedAt = Instant.now(clock)
        val events = inTransaction {
            outboxEventRepository.claimDueEvents(
                limit = batchSize,
                now = claimStartedAt,
                leaseUntil = claimStartedAt.plus(outboxProperties.processingLease),
            )
        }
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
                val failedStatus = if (attempts >= outboxProperties.maxAttempts) {
                    OutboxStatus.DEAD
                } else {
                    OutboxStatus.FAILED
                }
                val nextAttemptAt = if (failedStatus == OutboxStatus.DEAD) {
                    failedAt
                } else {
                    failedAt.plus(backoffDelay(attempts, event.id))
                }
                val marked = markFailed(
                    event = event,
                    attempts = attempts,
                    status = failedStatus,
                    failedAt = failedAt,
                    nextAttemptAt = nextAttemptAt,
                    exception = exception,
                )
                if (marked) {
                    if (failedStatus == OutboxStatus.DEAD) {
                        metrics.recordOutboxEventDead(event.eventType)
                    } else {
                        metrics.recordOutboxEventFailed(event.eventType)
                    }
                    failed += 1
                }
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
                    failedStatus,
                    attempts,
                    nextAttemptAt,
                    exception.toBoundedError(),
                )
                return@forEach
            }

            val publishedAt = Instant.now(clock)
            try {
                val marked = inTransaction {
                    outboxEventRepository.markPublished(
                        OutboxPublishedUpdate(
                            id = event.id,
                            claimedLeaseUntil = event.nextAttemptAt,
                            publishedAt = publishedAt,
                            updatedAt = publishedAt,
                        ),
                    )
                }
                if (marked) {
                    metrics.recordOutboxEventPublished(event.eventType)
                    logger.info(
                        "outbox_event_publish_succeeded outboxEventId={} eventType={} transactionId={} chainId={} address={} asset={} txHash={} eventIndex={} transactionStatus={} source={} outboxStatus={} attempts={}",
                        event.id,
                        event.eventType,
                        logFields.transactionId,
                        logFields.chainId,
                        logFields.address,
                        logFields.asset,
                        logFields.txHash,
                        logFields.eventIndex,
                        logFields.transactionStatus,
                        logFields.source,
                        OutboxStatus.PUBLISHED,
                        event.attempts,
                    )
                    published += 1
                } else {
                    logger.debug(
                        "outbox_event_publish_completion_stale outboxEventId={} eventType={} claimedLeaseUntil={}",
                        event.id,
                        event.eventType,
                        event.nextAttemptAt,
                    )
                }
            } catch (exception: Exception) {
                metrics.recordOutboxEventCompletionFailed(event.eventType)
                failed += 1
                logger.error(
                    "outbox_event_publish_completion_failed outboxEventId={} eventType={} transactionId={} chainId={} address={} asset={} txHash={} eventIndex={} transactionStatus={} attempts={} claimedLeaseUntil={} error={}",
                    event.id,
                    event.eventType,
                    logFields.transactionId,
                    logFields.chainId,
                    logFields.address,
                    logFields.asset,
                    logFields.txHash,
                    logFields.eventIndex,
                    logFields.transactionStatus,
                    event.attempts,
                    event.nextAttemptAt,
                    exception.toBoundedError(),
                )
            }
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

    private fun markFailed(
        event: OutboxEvent,
        attempts: Int,
        status: OutboxStatus,
        failedAt: Instant,
        nextAttemptAt: Instant,
        exception: Exception,
    ): Boolean {
        return try {
            inTransaction {
                outboxEventRepository.markFailed(
                    OutboxFailedUpdate(
                        id = event.id,
                        claimedLeaseUntil = event.nextAttemptAt,
                        status = status,
                        attempts = attempts,
                        lastError = exception.toBoundedError(),
                        nextAttemptAt = nextAttemptAt,
                        updatedAt = failedAt,
                    ),
                )
            }.also { marked ->
                if (!marked) {
                    logger.debug(
                        "outbox_event_failure_completion_stale outboxEventId={} intendedStatus={} attempts={} claimedLeaseUntil={}",
                        event.id,
                        status,
                        attempts,
                        event.nextAttemptAt,
                    )
                }
            }
        } catch (markException: Exception) {
            logger.error(
                "outbox_event_mark_failed_failed outboxEventId={} intendedStatus={} attempts={} error={} markError={}",
                event.id,
                status,
                attempts,
                exception.toBoundedError(),
                markException.toBoundedError(),
            )
            false
        }
    }

    private fun backoffDelay(attempts: Int, seed: UUID): Duration {
        val base = minOf(
            runCatching {
                outboxProperties.retryBackoffBaseDelay.multipliedBy(
                    1L shl (attempts - 1).coerceAtLeast(0).coerceAtMost(30),
                )
            }.getOrDefault(outboxProperties.retryBackoffMaxDelay),
            outboxProperties.retryBackoffMaxDelay,
        )
        // Deterministic downward jitter (0..25% of base, from the event id — no Math.random, stays
        // <= maxDelay) so a poison cohort that failed together does not all become due on one tick.
        val jitterCeilingMillis = (base.toMillis() / 4).coerceAtLeast(1)
        val jitterMillis = Math.floorMod(seed.leastSignificantBits, jitterCeilingMillis)
        return base.minusMillis(jitterMillis)
    }

    private fun <T> inTransaction(block: () -> T): T =
        requireNotNull(transactionTemplate.execute { block() })

    private fun Exception.toBoundedError(): String {
        val summary = "${this::class.java.simpleName}: ${message ?: "outbox publish failed"}"
        return summary.take(outboxProperties.maxErrorLength)
    }
}
