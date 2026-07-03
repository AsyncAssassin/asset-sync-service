package com.example.assetsync.infrastructure.outbox

import com.example.assetsync.application.outbox.OutboxRetentionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "asset-sync.outbox.retention",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class OutboxRetentionJob(
    private val outboxRetentionService: OutboxRetentionService,
) {
    private val logger = LoggerFactory.getLogger(OutboxRetentionJob::class.java)

    @Scheduled(
        fixedDelayString = "\${asset-sync.outbox.retention.fixed-delay:1h}",
        initialDelayString = "\${asset-sync.outbox.retention.initial-delay:5m}",
    )
    fun deleteExpiredPublished() {
        try {
            outboxRetentionService.deleteExpiredPublished()
        } catch (exception: Exception) {
            logger.error(
                "outbox_retention_failed exceptionClass={} error={}",
                exception.javaClass.simpleName,
                exception.message ?: "outbox retention failed",
                exception,
            )
        }
    }
}
