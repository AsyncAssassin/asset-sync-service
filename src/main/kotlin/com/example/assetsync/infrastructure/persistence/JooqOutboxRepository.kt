package com.example.assetsync.infrastructure.persistence

import com.example.assetsync.application.outbox.OutboxEvent
import com.example.assetsync.application.outbox.OutboxEventRepository
import com.example.assetsync.application.outbox.OutboxFailedUpdate
import com.example.assetsync.application.outbox.OutboxPublishedUpdate
import com.example.assetsync.application.outbox.OutboxStatus
import com.example.assetsync.application.transaction.NewOutboxEvent
import com.example.assetsync.application.transaction.OutboxRepository
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.OUTBOX_EVENTS
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.springframework.stereotype.Repository

@Repository
class JooqOutboxRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : OutboxRepository, OutboxEventRepository {

    override fun insert(event: NewOutboxEvent): Boolean =
        dsl
            .insertInto(OUTBOX_EVENTS)
            .set(OUTBOX_EVENTS.ID, event.id)
            .set(OUTBOX_EVENTS.AGGREGATE_TYPE, event.aggregateType)
            .set(OUTBOX_EVENTS.AGGREGATE_ID, event.aggregateId)
            .set(OUTBOX_EVENTS.EVENT_TYPE, event.eventType.name)
            .set(OUTBOX_EVENTS.IDEMPOTENCY_KEY, event.idempotencyKey)
            .set(OUTBOX_EVENTS.PAYLOAD, JSONB.valueOf(objectMapper.writeValueAsString(event.payload)))
            .set(OUTBOX_EVENTS.STATUS, "NEW")
            .set(OUTBOX_EVENTS.ATTEMPTS, 0)
            .set(OUTBOX_EVENTS.NEXT_ATTEMPT_AT, event.createdAt.toOffsetDateTime())
            .set(OUTBOX_EVENTS.CREATED_AT, event.createdAt.toOffsetDateTime())
            .set(OUTBOX_EVENTS.UPDATED_AT, event.updatedAt.toOffsetDateTime())
            .onConflict(OUTBOX_EVENTS.IDEMPOTENCY_KEY)
            .doNothing()
            .execute() == 1

    override fun claimDueEvents(limit: Int, now: Instant): List<OutboxEvent> {
        require(limit > 0) { "limit must be positive." }

        return dsl
            .select(
                OUTBOX_EVENTS.ID,
                OUTBOX_EVENTS.AGGREGATE_TYPE,
                OUTBOX_EVENTS.AGGREGATE_ID,
                OUTBOX_EVENTS.EVENT_TYPE,
                OUTBOX_EVENTS.IDEMPOTENCY_KEY,
                OUTBOX_EVENTS.PAYLOAD,
                OUTBOX_EVENTS.STATUS,
                OUTBOX_EVENTS.ATTEMPTS,
                OUTBOX_EVENTS.NEXT_ATTEMPT_AT,
                OUTBOX_EVENTS.PUBLISHED_AT,
                OUTBOX_EVENTS.LAST_ERROR,
                OUTBOX_EVENTS.CREATED_AT,
                OUTBOX_EVENTS.UPDATED_AT,
            )
            .from(OUTBOX_EVENTS)
            .where(
                OUTBOX_EVENTS.STATUS.eq(OutboxStatus.NEW.name)
                    .or(OUTBOX_EVENTS.STATUS.eq(OutboxStatus.FAILED.name)),
            )
            .and(OUTBOX_EVENTS.NEXT_ATTEMPT_AT.le(now.toOffsetDateTime()))
            .orderBy(OUTBOX_EVENTS.CREATED_AT.asc(), OUTBOX_EVENTS.ID.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch { it.toOutboxEvent() }
    }

    override fun markPublished(update: OutboxPublishedUpdate) {
        dsl
            .update(OUTBOX_EVENTS)
            .set(OUTBOX_EVENTS.STATUS, OutboxStatus.PUBLISHED.name)
            .set(OUTBOX_EVENTS.PUBLISHED_AT, update.publishedAt.toOffsetDateTime())
            .setNull(OUTBOX_EVENTS.LAST_ERROR)
            .set(OUTBOX_EVENTS.UPDATED_AT, update.updatedAt.toOffsetDateTime())
            .where(OUTBOX_EVENTS.ID.eq(update.id))
            .execute()
    }

    override fun markFailed(update: OutboxFailedUpdate) {
        dsl
            .update(OUTBOX_EVENTS)
            .set(OUTBOX_EVENTS.STATUS, OutboxStatus.FAILED.name)
            .set(OUTBOX_EVENTS.ATTEMPTS, update.attempts)
            .set(OUTBOX_EVENTS.LAST_ERROR, update.lastError)
            .set(OUTBOX_EVENTS.NEXT_ATTEMPT_AT, update.nextAttemptAt.toOffsetDateTime())
            .set(OUTBOX_EVENTS.UPDATED_AT, update.updatedAt.toOffsetDateTime())
            .where(OUTBOX_EVENTS.ID.eq(update.id))
            .execute()
    }

    override fun countBacklog(): Int =
        requireNotNull(
            dsl
                .selectCount()
                .from(OUTBOX_EVENTS)
                .where(
                    OUTBOX_EVENTS.STATUS.eq(OutboxStatus.NEW.name)
                        .or(OUTBOX_EVENTS.STATUS.eq(OutboxStatus.FAILED.name)),
                )
                .fetchOne(0, Int::class.java),
        )

    private fun Record.toOutboxEvent(): OutboxEvent =
        OutboxEvent(
            id = requireNotNull(get(OUTBOX_EVENTS.ID)),
            aggregateType = requireNotNull(get(OUTBOX_EVENTS.AGGREGATE_TYPE)),
            aggregateId = requireNotNull(get(OUTBOX_EVENTS.AGGREGATE_ID)),
            eventType = requireNotNull(get(OUTBOX_EVENTS.EVENT_TYPE)),
            idempotencyKey = requireNotNull(get(OUTBOX_EVENTS.IDEMPOTENCY_KEY)),
            payload = objectMapper.readTree(requireNotNull(get(OUTBOX_EVENTS.PAYLOAD)).data()),
            status = OutboxStatus.valueOf(requireNotNull(get(OUTBOX_EVENTS.STATUS))),
            attempts = requireNotNull(get(OUTBOX_EVENTS.ATTEMPTS)),
            nextAttemptAt = requireNotNull(get(OUTBOX_EVENTS.NEXT_ATTEMPT_AT)).toInstant(),
            publishedAt = get(OUTBOX_EVENTS.PUBLISHED_AT)?.toInstant(),
            lastError = get(OUTBOX_EVENTS.LAST_ERROR),
            createdAt = requireNotNull(get(OUTBOX_EVENTS.CREATED_AT)).toInstant(),
            updatedAt = requireNotNull(get(OUTBOX_EVENTS.UPDATED_AT)).toInstant(),
        )
}
