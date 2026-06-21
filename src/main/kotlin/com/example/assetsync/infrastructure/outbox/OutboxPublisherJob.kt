package com.example.assetsync.infrastructure.outbox

import com.example.assetsync.application.outbox.OutboxProcessingService
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
    private val outboxProcessingService: OutboxProcessingService,
) {

    @Scheduled(
        fixedDelayString = "\${asset-sync.outbox.scheduler.fixed-delay:5s}",
        initialDelayString = "\${asset-sync.outbox.scheduler.initial-delay:10s}",
    )
    fun publishDueEvents() {
        outboxProcessingService.processDueBatch()
    }
}
