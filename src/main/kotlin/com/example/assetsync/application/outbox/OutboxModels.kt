package com.example.assetsync.application.outbox

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class OutboxStatus {
    NEW,
    PUBLISHED,
    FAILED,
    DEAD,
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
    val claimedLeaseUntil: Instant,
    val publishedAt: Instant,
    val updatedAt: Instant,
)

data class OutboxFailedUpdate(
    val id: UUID,
    val claimedLeaseUntil: Instant,
    val status: OutboxStatus,
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

interface OutboxBatchProcessor {
    fun processDueBatch(): OutboxProcessingResult
}

interface OutboxEventRepository {
    fun claimDueEvents(limit: Int, now: Instant, leaseUntil: Instant): List<OutboxEvent>

    fun markPublished(update: OutboxPublishedUpdate): Boolean

    fun markFailed(update: OutboxFailedUpdate): Boolean

    fun countBacklog(): Int

    fun countDead(): Int

    fun deletePublishedBefore(cutoff: Instant, limit: Int): Int
}

interface OutboxEventPublisher {
    fun publish(event: OutboxEvent)
}
