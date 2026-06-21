package com.example.assetsync.application.outbox

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class OutboxStatus {
    NEW,
    PUBLISHED,
    FAILED,
}

data class OutboxEvent(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val idempotencyKey: String,
    val payload: JsonNode,
    val status: OutboxStatus,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val publishedAt: Instant?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class OutboxPublishedUpdate(
    val id: UUID,
    val publishedAt: Instant,
    val updatedAt: Instant,
)

data class OutboxFailedUpdate(
    val id: UUID,
    val attempts: Int,
    val lastError: String,
    val nextAttemptAt: Instant,
    val updatedAt: Instant,
)

data class OutboxProcessingResult(
    val claimed: Int,
    val published: Int,
    val failed: Int,
)

interface OutboxEventRepository {
    fun claimDueEvents(limit: Int, now: Instant): List<OutboxEvent>

    fun markPublished(update: OutboxPublishedUpdate)

    fun markFailed(update: OutboxFailedUpdate)

    fun countBacklog(): Int
}

interface OutboxEventPublisher {
    fun publish(event: OutboxEvent)
}
