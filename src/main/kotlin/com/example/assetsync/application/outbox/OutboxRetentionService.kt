package com.example.assetsync.application.outbox

import com.example.assetsync.config.OutboxProperties
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class OutboxRetentionService(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxProperties: OutboxProperties,
    private val clock: Clock,
    private val transactionTemplate: TransactionTemplate,
) {
    private val logger = LoggerFactory.getLogger(OutboxRetentionService::class.java)

    fun deleteExpiredPublished(): Int {
        val cutoff = Instant.now(clock).minus(outboxProperties.retention.publishedRetention)
        val deleted = requireNotNull(
            transactionTemplate.execute {
                outboxEventRepository.deletePublishedBefore(
                    cutoff = cutoff,
                    limit = outboxProperties.retention.batchSize,
                )
            },
        )
        if (deleted > 0) {
            logger.info("outbox_retention_deleted publishedDeleted={} cutoff={}", deleted, cutoff)
        } else {
            logger.debug("outbox_retention_deleted publishedDeleted={} cutoff={}", deleted, cutoff)
        }
        return deleted
    }
}
