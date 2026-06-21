package com.example.assetsync.infrastructure.outbox

import com.example.assetsync.application.outbox.OutboxEvent
import com.example.assetsync.application.outbox.OutboxEventPublisher
import com.example.assetsync.application.outbox.toLogFields
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LocalStructuredLogOutboxEventPublisher : OutboxEventPublisher {
    private val logger = LoggerFactory.getLogger(LocalStructuredLogOutboxEventPublisher::class.java)

    override fun publish(event: OutboxEvent) {
        val logFields = event.toLogFields()
        logger.info(
            "outbox_event_publish_attempted outboxEventId={} eventType={} transactionId={} chainId={} address={} asset={} txHash={} eventIndex={} transactionStatus={} outboxStatus={} aggregateType={} idempotencyKey={} attempts={}",
            event.id,
            event.eventType,
            logFields.transactionId,
            logFields.chainId,
            logFields.address,
            logFields.asset,
            logFields.txHash,
            logFields.eventIndex,
            logFields.transactionStatus,
            event.status,
            event.aggregateType,
            event.idempotencyKey,
            event.attempts,
        )
    }
}
