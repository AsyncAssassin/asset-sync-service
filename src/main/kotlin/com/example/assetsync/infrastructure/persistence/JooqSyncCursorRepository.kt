package com.example.assetsync.infrastructure.persistence

import com.example.assetsync.application.sync.AcquiredSyncCursorLease
import com.example.assetsync.application.sync.AdvanceSyncCheckpointCommand
import com.example.assetsync.application.sync.SyncCursor
import com.example.assetsync.application.sync.SyncCursorRepository
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.records.SyncCursorsRecord
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.SYNC_CURSORS
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository

@Repository
class JooqSyncCursorRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : SyncCursorRepository {

    override fun ensureCursor(watchedAddressId: UUID, now: Instant): SyncCursor {
        dsl
            .insertInto(SYNC_CURSORS)
            .set(SYNC_CURSORS.WATCHED_ADDRESS_ID, watchedAddressId)
            .setNull(SYNC_CURSORS.PROVIDER_CURSOR)
            .set(SYNC_CURSORS.CHECKPOINT, JSONB.valueOf("{}"))
            .set(SYNC_CURSORS.VERSION, 0L)
            .set(SYNC_CURSORS.CREATED_AT, now.toOffsetDateTime())
            .set(SYNC_CURSORS.UPDATED_AT, now.toOffsetDateTime())
            .onConflict(SYNC_CURSORS.WATCHED_ADDRESS_ID)
            .doNothing()
            .execute()

        return requireNotNull(findCursor(watchedAddressId)) {
            "Sync cursor was not found after ensure for watchedAddressId=$watchedAddressId."
        }
    }

    override fun findCursor(watchedAddressId: UUID): SyncCursor? =
        dsl
            .select(SYNC_CURSOR_FIELDS)
            .from(SYNC_CURSORS)
            .where(SYNC_CURSORS.WATCHED_ADDRESS_ID.eq(watchedAddressId))
            .fetchOne { it.toSyncCursor() }

    override fun tryAcquireCursorLease(
        watchedAddressId: UUID,
        lockedBy: String,
        lockToken: UUID,
        now: Instant,
        leaseUntil: Instant,
    ): AcquiredSyncCursorLease? =
        dsl
            .update(SYNC_CURSORS)
            .set(SYNC_CURSORS.LOCKED_BY, lockedBy)
            .set(SYNC_CURSORS.LOCK_TOKEN, lockToken)
            .set(SYNC_CURSORS.LOCKED_UNTIL, leaseUntil.toOffsetDateTime())
            .set(SYNC_CURSORS.UPDATED_AT, now.toOffsetDateTime())
            .where(SYNC_CURSORS.WATCHED_ADDRESS_ID.eq(watchedAddressId))
            .and(
                SYNC_CURSORS.LOCKED_UNTIL.isNull
                    .or(SYNC_CURSORS.LOCKED_UNTIL.lt(now.toOffsetDateTime())),
            )
            .returningResult(SYNC_CURSOR_FIELDS)
            .fetchOne { record ->
                AcquiredSyncCursorLease(
                    cursor = record.toSyncCursor(),
                    lockedBy = lockedBy,
                    lockToken = lockToken,
                )
            }

    override fun extendCursorLease(
        watchedAddressId: UUID,
        lockedBy: String,
        lockToken: UUID,
        leaseUntil: Instant,
        updatedAt: Instant,
    ): Boolean =
        dsl
            .update(SYNC_CURSORS)
            .set(SYNC_CURSORS.LOCKED_UNTIL, leaseUntil.toOffsetDateTime())
            .set(SYNC_CURSORS.UPDATED_AT, updatedAt.toOffsetDateTime())
            .where(SYNC_CURSORS.WATCHED_ADDRESS_ID.eq(watchedAddressId))
            .and(SYNC_CURSORS.LOCKED_BY.eq(lockedBy))
            .and(SYNC_CURSORS.LOCK_TOKEN.eq(lockToken))
            .and(SYNC_CURSORS.LOCKED_UNTIL.ge(updatedAt.toOffsetDateTime()))
            .execute() == 1

    override fun advanceCheckpointFenced(command: AdvanceSyncCheckpointCommand): SyncCursor? {
        val nextBlock = coalescedBlock(command.lastProcessedBlockHeight)
        val nextEventIndex = coalescedEventIndex(command.lastProcessedEventIndex)
        val nextFinalizedBlock = coalescedFinalizedBlock(command.lastFinalizedBlockHeight)

        return dsl
            .update(SYNC_CURSORS)
            .set(SYNC_CURSORS.PROVIDER_CURSOR, command.providerCursor)
            .set(SYNC_CURSORS.CHECKPOINT, JSONB.valueOf(command.checkpoint.toString()))
            .set(SYNC_CURSORS.LAST_PROCESSED_BLOCK_HEIGHT, nextBlock)
            .set(SYNC_CURSORS.LAST_PROCESSED_EVENT_INDEX, nextEventIndex)
            .set(SYNC_CURSORS.LAST_FINALIZED_BLOCK_HEIGHT, nextFinalizedBlock)
            .set(SYNC_CURSORS.VERSION, SYNC_CURSORS.VERSION.plus(1))
            .set(SYNC_CURSORS.CURSOR_UPDATED_AT, command.cursorUpdatedAt.toOffsetDateTime())
            .set(SYNC_CURSORS.UPDATED_AT, command.updatedAt.toOffsetDateTime())
            .where(SYNC_CURSORS.WATCHED_ADDRESS_ID.eq(command.watchedAddressId))
            .and(SYNC_CURSORS.LOCKED_BY.eq(command.lockedBy))
            .and(SYNC_CURSORS.LOCK_TOKEN.eq(command.lockToken))
            .and(SYNC_CURSORS.VERSION.eq(command.expectedVersion))
            .and(SYNC_CURSORS.LOCKED_UNTIL.ge(command.leaseCheckedAt.toOffsetDateTime()))
            .and(
                nextBlock.isNull
                    .or(SYNC_CURSORS.LAST_PROCESSED_BLOCK_HEIGHT.isNull)
                    .or(nextBlock.gt(SYNC_CURSORS.LAST_PROCESSED_BLOCK_HEIGHT))
                    .or(
                        nextBlock.eq(SYNC_CURSORS.LAST_PROCESSED_BLOCK_HEIGHT)
                            .and(
                                nextEventIndex.isNull
                                    .or(SYNC_CURSORS.LAST_PROCESSED_EVENT_INDEX.isNull)
                                    .or(nextEventIndex.ge(SYNC_CURSORS.LAST_PROCESSED_EVENT_INDEX)),
                            ),
                    ),
            )
            .and(
                nextFinalizedBlock.isNull
                    .or(SYNC_CURSORS.LAST_FINALIZED_BLOCK_HEIGHT.isNull)
                    .or(nextFinalizedBlock.ge(SYNC_CURSORS.LAST_FINALIZED_BLOCK_HEIGHT)),
            )
            .returningResult(SYNC_CURSOR_FIELDS)
            .fetchOne { it.toSyncCursor() }
    }

    override fun releaseCursorLease(watchedAddressId: UUID, updatedAt: Instant): Boolean =
        dsl
            .update(SYNC_CURSORS)
            .setNull(SYNC_CURSORS.LOCKED_BY)
            .setNull(SYNC_CURSORS.LOCK_TOKEN)
            .setNull(SYNC_CURSORS.LOCKED_UNTIL)
            .set(SYNC_CURSORS.UPDATED_AT, updatedAt.toOffsetDateTime())
            .where(SYNC_CURSORS.WATCHED_ADDRESS_ID.eq(watchedAddressId))
            .execute() == 1

    override fun releaseCursorLeaseFenced(
        watchedAddressId: UUID,
        lockedBy: String,
        lockToken: UUID,
        updatedAt: Instant,
    ): Boolean =
        dsl
            .update(SYNC_CURSORS)
            .setNull(SYNC_CURSORS.LOCKED_BY)
            .setNull(SYNC_CURSORS.LOCK_TOKEN)
            .setNull(SYNC_CURSORS.LOCKED_UNTIL)
            .set(SYNC_CURSORS.UPDATED_AT, updatedAt.toOffsetDateTime())
            .where(SYNC_CURSORS.WATCHED_ADDRESS_ID.eq(watchedAddressId))
            .and(SYNC_CURSORS.LOCKED_BY.eq(lockedBy))
            .and(SYNC_CURSORS.LOCK_TOKEN.eq(lockToken))
            .execute() == 1

    override fun findExpiredCursorLeases(now: Instant, limit: Int): List<SyncCursor> {
        require(limit > 0) { "limit must be positive." }

        return dsl
            .select(SYNC_CURSOR_FIELDS)
            .from(SYNC_CURSORS)
            .where(SYNC_CURSORS.LOCKED_UNTIL.lt(now.toOffsetDateTime()))
            .orderBy(SYNC_CURSORS.LOCKED_UNTIL.asc(), SYNC_CURSORS.WATCHED_ADDRESS_ID.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch { it.toSyncCursor() }
    }

    override fun clearExpiredCursorLeases(now: Instant, limit: Int): Int {
        require(limit > 0) { "limit must be positive." }

        val expired = DSL
            .select(SYNC_CURSORS.WATCHED_ADDRESS_ID)
            .from(SYNC_CURSORS)
            .where(SYNC_CURSORS.LOCKED_UNTIL.lt(now.toOffsetDateTime()))
            .orderBy(SYNC_CURSORS.LOCKED_UNTIL.asc(), SYNC_CURSORS.WATCHED_ADDRESS_ID.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked()

        return dsl
            .update(SYNC_CURSORS)
            .setNull(SYNC_CURSORS.LOCKED_BY)
            .setNull(SYNC_CURSORS.LOCK_TOKEN)
            .setNull(SYNC_CURSORS.LOCKED_UNTIL)
            .set(SYNC_CURSORS.UPDATED_AT, now.toOffsetDateTime())
            .where(SYNC_CURSORS.WATCHED_ADDRESS_ID.`in`(expired))
            .execute()
    }

    private fun coalescedBlock(value: Long?): Field<Long?> =
        DSL.coalesce(
            DSL.value(value, SYNC_CURSORS.LAST_PROCESSED_BLOCK_HEIGHT.dataType),
            SYNC_CURSORS.LAST_PROCESSED_BLOCK_HEIGHT,
        )

    private fun coalescedEventIndex(value: Int?): Field<Int?> =
        DSL.coalesce(
            DSL.value(value, SYNC_CURSORS.LAST_PROCESSED_EVENT_INDEX.dataType),
            SYNC_CURSORS.LAST_PROCESSED_EVENT_INDEX,
        )

    private fun coalescedFinalizedBlock(value: Long?): Field<Long?> =
        DSL.coalesce(
            DSL.value(value, SYNC_CURSORS.LAST_FINALIZED_BLOCK_HEIGHT.dataType),
            SYNC_CURSORS.LAST_FINALIZED_BLOCK_HEIGHT,
        )

    private fun Record.toSyncCursor(): SyncCursor =
        SyncCursor(
            watchedAddressId = requireNotNull(get(SYNC_CURSORS.WATCHED_ADDRESS_ID)),
            providerCursor = get(SYNC_CURSORS.PROVIDER_CURSOR),
            checkpoint = get(SYNC_CURSORS.CHECKPOINT).toObjectNode(),
            lastProcessedBlockHeight = get(SYNC_CURSORS.LAST_PROCESSED_BLOCK_HEIGHT),
            lastProcessedEventIndex = get(SYNC_CURSORS.LAST_PROCESSED_EVENT_INDEX),
            lastFinalizedBlockHeight = get(SYNC_CURSORS.LAST_FINALIZED_BLOCK_HEIGHT),
            version = requireNotNull(get(SYNC_CURSORS.VERSION)),
            lockedBy = get(SYNC_CURSORS.LOCKED_BY),
            lockToken = get(SYNC_CURSORS.LOCK_TOKEN),
            lockedUntil = get(SYNC_CURSORS.LOCKED_UNTIL)?.toInstant(),
            cursorUpdatedAt = get(SYNC_CURSORS.CURSOR_UPDATED_AT)?.toInstant(),
            createdAt = requireNotNull(get(SYNC_CURSORS.CREATED_AT)).toInstant(),
            updatedAt = requireNotNull(get(SYNC_CURSORS.UPDATED_AT)).toInstant(),
        )

    private fun JSONB?.toObjectNode(): ObjectNode {
        val node = objectMapper.readTree(this?.data() ?: "{}")
        require(node is ObjectNode) { "Expected JSON object for sync cursor checkpoint." }
        return node.deepCopy()
    }

    private companion object {
        val SYNC_CURSOR_FIELDS: List<SelectFieldOrAsterisk> = listOf(
            SYNC_CURSORS.WATCHED_ADDRESS_ID,
            SYNC_CURSORS.PROVIDER_CURSOR,
            SYNC_CURSORS.CHECKPOINT,
            SYNC_CURSORS.LAST_PROCESSED_BLOCK_HEIGHT,
            SYNC_CURSORS.LAST_PROCESSED_EVENT_INDEX,
            SYNC_CURSORS.LAST_FINALIZED_BLOCK_HEIGHT,
            SYNC_CURSORS.VERSION,
            SYNC_CURSORS.LOCKED_BY,
            SYNC_CURSORS.LOCK_TOKEN,
            SYNC_CURSORS.LOCKED_UNTIL,
            SYNC_CURSORS.CURSOR_UPDATED_AT,
            SYNC_CURSORS.CREATED_AT,
            SYNC_CURSORS.UPDATED_AT,
        )
    }
}
