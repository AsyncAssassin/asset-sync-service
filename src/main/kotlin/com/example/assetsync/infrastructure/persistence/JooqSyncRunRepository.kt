package com.example.assetsync.infrastructure.persistence

import com.example.assetsync.application.sync.NewSyncRun
import com.example.assetsync.application.sync.SyncRun
import com.example.assetsync.application.sync.SyncRunCompletion
import com.example.assetsync.application.sync.SyncRunRepository
import com.example.assetsync.application.sync.SyncRunStatus
import com.example.assetsync.application.sync.SyncTargetType
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.SYNC_RUNS
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.springframework.stereotype.Repository

@Repository
class JooqSyncRunRepository(
    private val dsl: DSLContext,
) : SyncRunRepository {

    override fun insert(syncRun: NewSyncRun): SyncRun =
        requireNotNull(
            dsl
                .insertInto(SYNC_RUNS)
                .set(SYNC_RUNS.ID, syncRun.id)
                .set(SYNC_RUNS.TARGET_TYPE, syncRun.targetType.name)
                .set(SYNC_RUNS.TARGET_ID, syncRun.targetId)
                .set(SYNC_RUNS.STATUS, syncRun.status.name)
                .set(SYNC_RUNS.STARTED_AT, syncRun.startedAt.toOffsetDateTime())
                .set(SYNC_RUNS.FINISHED_AT, syncRun.finishedAt?.toOffsetDateTime())
                .set(SYNC_RUNS.EVENTS_SEEN, syncRun.eventsSeen)
                .set(SYNC_RUNS.EVENTS_CHANGED, syncRun.eventsChanged)
                .set(SYNC_RUNS.LAST_ERROR, syncRun.lastError)
                .set(SYNC_RUNS.CREATED_AT, syncRun.createdAt.toOffsetDateTime())
                .set(SYNC_RUNS.UPDATED_AT, syncRun.updatedAt.toOffsetDateTime())
                .returningResult(SYNC_RUN_FIELDS)
                .fetchOne { it.toSyncRun() },
        ) { "Sync run ${syncRun.id} could not be inserted." }

    override fun markCompleted(completion: SyncRunCompletion): SyncRun =
        requireNotNull(
            dsl
                .update(SYNC_RUNS)
                .set(SYNC_RUNS.STATUS, completion.status.name)
                .set(SYNC_RUNS.EVENTS_SEEN, completion.eventsSeen)
                .set(SYNC_RUNS.EVENTS_CHANGED, completion.eventsChanged)
                .set(SYNC_RUNS.LAST_ERROR, completion.lastError)
                .set(SYNC_RUNS.FINISHED_AT, completion.finishedAt.toOffsetDateTime())
                .set(SYNC_RUNS.UPDATED_AT, completion.updatedAt.toOffsetDateTime())
                .where(SYNC_RUNS.ID.eq(completion.id))
                .returningResult(SYNC_RUN_FIELDS)
                .fetchOne { it.toSyncRun() },
        ) { "Sync run ${completion.id} was not found for completion update." }

    override fun findById(syncRunId: UUID): SyncRun? =
        dsl
            .select(SYNC_RUN_FIELDS)
            .from(SYNC_RUNS)
            .where(SYNC_RUNS.ID.eq(syncRunId))
            .fetchOne { it.toSyncRun() }

    private fun Record.toSyncRun(): SyncRun =
        SyncRun(
            id = requireNotNull(get(SYNC_RUNS.ID)),
            targetType = SyncTargetType.valueOf(requireNotNull(get(SYNC_RUNS.TARGET_TYPE))),
            targetId = requireNotNull(get(SYNC_RUNS.TARGET_ID)),
            status = SyncRunStatus.valueOf(requireNotNull(get(SYNC_RUNS.STATUS))),
            eventsSeen = requireNotNull(get(SYNC_RUNS.EVENTS_SEEN)),
            eventsChanged = requireNotNull(get(SYNC_RUNS.EVENTS_CHANGED)),
            lastError = get(SYNC_RUNS.LAST_ERROR),
            startedAt = requireNotNull(get(SYNC_RUNS.STARTED_AT)).toInstant(),
            finishedAt = get(SYNC_RUNS.FINISHED_AT)?.toInstant(),
            createdAt = requireNotNull(get(SYNC_RUNS.CREATED_AT)).toInstant(),
            updatedAt = requireNotNull(get(SYNC_RUNS.UPDATED_AT)).toInstant(),
        )

    private companion object {
        val SYNC_RUN_FIELDS: List<SelectFieldOrAsterisk> = listOf(
            SYNC_RUNS.ID,
            SYNC_RUNS.TARGET_TYPE,
            SYNC_RUNS.TARGET_ID,
            SYNC_RUNS.STATUS,
            SYNC_RUNS.STARTED_AT,
            SYNC_RUNS.FINISHED_AT,
            SYNC_RUNS.EVENTS_SEEN,
            SYNC_RUNS.EVENTS_CHANGED,
            SYNC_RUNS.LAST_ERROR,
            SYNC_RUNS.CREATED_AT,
            SYNC_RUNS.UPDATED_AT,
        )
    }
}
