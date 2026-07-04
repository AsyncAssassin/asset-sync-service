package com.example.assetsync.infrastructure.persistence

import com.example.assetsync.application.sync.ClaimedSyncRun
import com.example.assetsync.application.sync.NewQueuedSyncRun
import com.example.assetsync.application.sync.SyncRun
import com.example.assetsync.application.sync.SyncRunRepository
import com.example.assetsync.application.sync.SyncRunStatus
import com.example.assetsync.application.sync.SyncTargetType
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.records.SyncRunsRecord
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.SYNC_RUNS
import java.time.Instant
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository

@Repository
class JooqSyncRunRepository(
    private val dsl: DSLContext,
) : SyncRunRepository {

    override fun insertQueued(syncRun: NewQueuedSyncRun): SyncRun? =
        dsl
            .insertInto(SYNC_RUNS)
            .set(SYNC_RUNS.ID, syncRun.id)
            .set(SYNC_RUNS.TARGET_TYPE, syncRun.targetType.name)
            .set(SYNC_RUNS.TARGET_ID, syncRun.targetId)
            .set(SYNC_RUNS.STATUS, SyncRunStatus.QUEUED.name)
            .setNull(SYNC_RUNS.STARTED_AT)
            .setNull(SYNC_RUNS.FINISHED_AT)
            .set(SYNC_RUNS.EVENTS_SEEN, 0)
            .set(SYNC_RUNS.EVENTS_CHANGED, 0)
            .setNull(SYNC_RUNS.LAST_ERROR)
            .set(SYNC_RUNS.CREATED_AT, syncRun.createdAt.toOffsetDateTime())
            .set(SYNC_RUNS.UPDATED_AT, syncRun.updatedAt.toOffsetDateTime())
            .set(SYNC_RUNS.QUEUED_AT, syncRun.queuedAt.toOffsetDateTime())
            .set(SYNC_RUNS.ATTEMPTS, 0)
            .set(SYNC_RUNS.NEXT_ATTEMPT_AT, syncRun.nextAttemptAt.toOffsetDateTime())
            .onConflict(SYNC_RUNS.TARGET_TYPE, SYNC_RUNS.TARGET_ID)
            .where(inFlightStatusCondition())
            .doNothing()
            .returningResult(SYNC_RUN_FIELDS)
            .fetchOne { it.toSyncRun() }

    override fun findInFlightByTarget(targetType: SyncTargetType, targetId: UUID): SyncRun? =
        dsl
            .select(SYNC_RUN_FIELDS)
            .from(SYNC_RUNS)
            .where(SYNC_RUNS.TARGET_TYPE.eq(targetType.name))
            .and(SYNC_RUNS.TARGET_ID.eq(targetId))
            .and(inFlightStatusCondition())
            .orderBy(SYNC_RUNS.QUEUED_AT.asc(), SYNC_RUNS.ID.asc())
            .limit(1)
            .fetchOne { it.toSyncRun() }

    override fun countInFlight(): Int =
        requireNotNull(
            dsl
                .selectCount()
                .from(SYNC_RUNS)
                .where(inFlightStatusCondition())
                .fetchOne(0, Int::class.java),
        )

    override fun countRunning(): Int =
        requireNotNull(
            dsl
                .selectCount()
                .from(SYNC_RUNS)
                .where(SYNC_RUNS.STATUS.eq(SyncRunStatus.RUNNING.name))
                .fetchOne(0, Int::class.java),
        )

    override fun claimDueRuns(limit: Int, now: Instant, leaseUntil: Instant, workerId: String): List<ClaimedSyncRun> {
        require(limit > 0) { "limit must be positive." }

        val dueIds = dsl
            .select(SYNC_RUNS.ID)
            .from(SYNC_RUNS)
            .where(SYNC_RUNS.STATUS.eq(SyncRunStatus.QUEUED.name))
            .and(SYNC_RUNS.NEXT_ATTEMPT_AT.le(now.toOffsetDateTime()))
            .orderBy(SYNC_RUNS.NEXT_ATTEMPT_AT.asc(), SYNC_RUNS.QUEUED_AT.asc(), SYNC_RUNS.ID.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch(SYNC_RUNS.ID)
            .filterNotNull()

        if (dueIds.isEmpty()) {
            return emptyList()
        }

        return dueIds.mapNotNull { id ->
            val lockToken = UUID.randomUUID()
            dsl
                .update(SYNC_RUNS)
                .set(SYNC_RUNS.STATUS, SyncRunStatus.RUNNING.name)
                .set(SYNC_RUNS.STARTED_AT, DSL.coalesce(SYNC_RUNS.STARTED_AT, now.toOffsetDateTime()))
                .set(SYNC_RUNS.ATTEMPTS, SYNC_RUNS.ATTEMPTS.plus(1))
                .set(SYNC_RUNS.LOCKED_BY, workerId)
                .set(SYNC_RUNS.LOCK_TOKEN, lockToken)
                .set(SYNC_RUNS.LOCKED_UNTIL, leaseUntil.toOffsetDateTime())
                .set(SYNC_RUNS.HEARTBEAT_AT, now.toOffsetDateTime())
                .set(SYNC_RUNS.UPDATED_AT, now.toOffsetDateTime())
                .where(SYNC_RUNS.ID.eq(id))
                .and(SYNC_RUNS.STATUS.eq(SyncRunStatus.QUEUED.name))
                .returningResult(SYNC_RUN_FIELDS)
                .fetchOne { record ->
                    val run = record.toSyncRun()
                    ClaimedSyncRun(
                        run = run,
                        lockedBy = workerId,
                        lockToken = lockToken,
                        attempts = run.attempts,
                    )
                }
        }
    }

    override fun markSucceededFenced(
        id: UUID,
        lockedBy: String,
        lockToken: UUID,
        attempts: Int,
        eventsSeen: Int,
        eventsChanged: Int,
        finishedAt: Instant,
        updatedAt: Instant,
    ): Boolean =
        dsl
            .update(SYNC_RUNS)
            .set(SYNC_RUNS.STATUS, SyncRunStatus.SUCCEEDED.name)
            .set(SYNC_RUNS.EVENTS_SEEN, eventsSeen)
            .set(SYNC_RUNS.EVENTS_CHANGED, eventsChanged)
            .setNull(SYNC_RUNS.LAST_ERROR)
            .set(SYNC_RUNS.FINISHED_AT, finishedAt.toOffsetDateTime())
            .clearLockFields()
            .set(SYNC_RUNS.UPDATED_AT, updatedAt.toOffsetDateTime())
            .whereCurrentClaim(id = id, lockedBy = lockedBy, lockToken = lockToken, attempts = attempts)
            .execute() == 1

    override fun markFailedFenced(
        id: UUID,
        lockedBy: String,
        lockToken: UUID,
        attempts: Int,
        eventsSeen: Int,
        eventsChanged: Int,
        lastError: String,
        finishedAt: Instant,
        updatedAt: Instant,
    ): Boolean =
        dsl
            .update(SYNC_RUNS)
            .set(SYNC_RUNS.STATUS, SyncRunStatus.FAILED.name)
            .set(SYNC_RUNS.EVENTS_SEEN, eventsSeen)
            .set(SYNC_RUNS.EVENTS_CHANGED, eventsChanged)
            .set(SYNC_RUNS.LAST_ERROR, lastError)
            .set(SYNC_RUNS.FINISHED_AT, finishedAt.toOffsetDateTime())
            .clearLockFields()
            .set(SYNC_RUNS.UPDATED_AT, updatedAt.toOffsetDateTime())
            .whereCurrentClaim(id = id, lockedBy = lockedBy, lockToken = lockToken, attempts = attempts)
            .execute() == 1

    override fun requeueFenced(
        id: UUID,
        lockedBy: String,
        lockToken: UUID,
        attempts: Int,
        eventsSeen: Int,
        eventsChanged: Int,
        lastError: String,
        nextAttemptAt: Instant,
        updatedAt: Instant,
    ): Boolean =
        dsl
            .update(SYNC_RUNS)
            .set(SYNC_RUNS.STATUS, SyncRunStatus.QUEUED.name)
            .set(SYNC_RUNS.EVENTS_SEEN, eventsSeen)
            .set(SYNC_RUNS.EVENTS_CHANGED, eventsChanged)
            .set(SYNC_RUNS.LAST_ERROR, lastError)
            .set(SYNC_RUNS.NEXT_ATTEMPT_AT, nextAttemptAt.toOffsetDateTime())
            .setNull(SYNC_RUNS.FINISHED_AT)
            .clearLockFields()
            .set(SYNC_RUNS.UPDATED_AT, updatedAt.toOffsetDateTime())
            .whereCurrentClaim(id = id, lockedBy = lockedBy, lockToken = lockToken, attempts = attempts)
            .execute() == 1

    override fun heartbeatFenced(
        id: UUID,
        lockedBy: String,
        lockToken: UUID,
        attempts: Int,
        heartbeatAt: Instant,
        lockedUntil: Instant,
        updatedAt: Instant,
    ): Boolean =
        dsl
            .update(SYNC_RUNS)
            .set(SYNC_RUNS.HEARTBEAT_AT, heartbeatAt.toOffsetDateTime())
            .set(SYNC_RUNS.LOCKED_UNTIL, lockedUntil.toOffsetDateTime())
            .set(SYNC_RUNS.UPDATED_AT, updatedAt.toOffsetDateTime())
            .whereCurrentClaim(id = id, lockedBy = lockedBy, lockToken = lockToken, attempts = attempts)
            .execute() == 1

    override fun recoverExpiredRunning(
        now: Instant,
        limit: Int,
        maxAttempts: Int,
        retryNextAttemptAt: (SyncRun) -> Instant,
        maxErrorLength: Int,
    ): List<SyncRun> {
        require(limit > 0) { "limit must be positive." }
        require(maxAttempts > 0) { "maxAttempts must be positive." }
        require(maxErrorLength > 0) { "maxErrorLength must be positive." }

        return findExpiredRunning(now = now, limit = limit).mapNotNull { expired ->
            val terminal = expired.attempts >= maxAttempts
            val nextAttemptAt = if (terminal) now else retryNextAttemptAt(expired)
            val error = "expired RUNNING lease; previous owner=${expired.lockedBy ?: "unknown"}"
                .take(maxErrorLength)
            val update = dsl
                .update(SYNC_RUNS)
                .set(SYNC_RUNS.STATUS, if (terminal) SyncRunStatus.FAILED.name else SyncRunStatus.QUEUED.name)
                .set(SYNC_RUNS.FINISHED_AT, if (terminal) now.toOffsetDateTime() else null)
                .set(SYNC_RUNS.NEXT_ATTEMPT_AT, nextAttemptAt.toOffsetDateTime())
                .set(SYNC_RUNS.LAST_ERROR, error)
                .clearLockFields()
                .set(SYNC_RUNS.UPDATED_AT, now.toOffsetDateTime())
                .where(SYNC_RUNS.ID.eq(expired.id))
                .and(SYNC_RUNS.STATUS.eq(SyncRunStatus.RUNNING.name))
                .and(SYNC_RUNS.LOCKED_UNTIL.lt(now.toOffsetDateTime()))
                .returningResult(SYNC_RUN_FIELDS)
                .fetchOne { it.toSyncRun() }
            update
        }
    }

    override fun findExpiredRunning(now: Instant, limit: Int): List<SyncRun> {
        require(limit > 0) { "limit must be positive." }

        return dsl
            .select(SYNC_RUN_FIELDS)
            .from(SYNC_RUNS)
            .where(SYNC_RUNS.STATUS.eq(SyncRunStatus.RUNNING.name))
            .and(SYNC_RUNS.LOCKED_UNTIL.lt(now.toOffsetDateTime()))
            .orderBy(SYNC_RUNS.LOCKED_UNTIL.asc(), SYNC_RUNS.ID.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch { it.toSyncRun() }
    }

    override fun findById(syncRunId: UUID): SyncRun? =
        dsl
            .select(SYNC_RUN_FIELDS)
            .from(SYNC_RUNS)
            .where(SYNC_RUNS.ID.eq(syncRunId))
            .fetchOne { it.toSyncRun() }

    override fun findStaleStarted(cutoff: Instant, limit: Int): List<SyncRun> {
        require(limit > 0) { "limit must be positive." }

        return dsl
            .select(SYNC_RUN_FIELDS)
            .from(SYNC_RUNS)
            .where(SYNC_RUNS.STATUS.eq(SyncRunStatus.STARTED.name))
            .and(SYNC_RUNS.STARTED_AT.lt(cutoff.toOffsetDateTime()))
            .orderBy(SYNC_RUNS.STARTED_AT.asc(), SYNC_RUNS.ID.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch { it.toSyncRun() }
    }

    override fun markAbandoned(id: UUID, lastError: String, finishedAt: Instant, updatedAt: Instant): SyncRun? =
        dsl
            .update(SYNC_RUNS)
            .set(SYNC_RUNS.STATUS, SyncRunStatus.FAILED.name)
            .set(SYNC_RUNS.LAST_ERROR, lastError)
            .set(SYNC_RUNS.FINISHED_AT, finishedAt.toOffsetDateTime())
            .set(SYNC_RUNS.UPDATED_AT, updatedAt.toOffsetDateTime())
            .where(SYNC_RUNS.ID.eq(id))
            .and(SYNC_RUNS.STATUS.eq(SyncRunStatus.STARTED.name))
            .returningResult(SYNC_RUN_FIELDS)
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
            queuedAt = requireNotNull(get(SYNC_RUNS.QUEUED_AT)).toInstant(),
            startedAt = get(SYNC_RUNS.STARTED_AT)?.toInstant(),
            finishedAt = get(SYNC_RUNS.FINISHED_AT)?.toInstant(),
            attempts = requireNotNull(get(SYNC_RUNS.ATTEMPTS)),
            nextAttemptAt = requireNotNull(get(SYNC_RUNS.NEXT_ATTEMPT_AT)).toInstant(),
            lockedBy = get(SYNC_RUNS.LOCKED_BY),
            lockToken = get(SYNC_RUNS.LOCK_TOKEN),
            lockedUntil = get(SYNC_RUNS.LOCKED_UNTIL)?.toInstant(),
            heartbeatAt = get(SYNC_RUNS.HEARTBEAT_AT)?.toInstant(),
            createdAt = requireNotNull(get(SYNC_RUNS.CREATED_AT)).toInstant(),
            updatedAt = requireNotNull(get(SYNC_RUNS.UPDATED_AT)).toInstant(),
        )

    private fun inFlightStatusCondition() =
        SYNC_RUNS.STATUS.`in`(SyncRunStatus.QUEUED.name, SyncRunStatus.RUNNING.name)

    private fun org.jooq.UpdateSetMoreStep<SyncRunsRecord>.clearLockFields():
        org.jooq.UpdateSetMoreStep<SyncRunsRecord> =
        setNull(SYNC_RUNS.LOCKED_BY)
            .setNull(SYNC_RUNS.LOCK_TOKEN)
            .setNull(SYNC_RUNS.LOCKED_UNTIL)
            .setNull(SYNC_RUNS.HEARTBEAT_AT)

    private fun org.jooq.UpdateSetMoreStep<SyncRunsRecord>.whereCurrentClaim(
        id: UUID,
        lockedBy: String,
        lockToken: UUID,
        attempts: Int,
    ): org.jooq.UpdateConditionStep<SyncRunsRecord> =
        where(SYNC_RUNS.ID.eq(id))
            .and(SYNC_RUNS.STATUS.eq(SyncRunStatus.RUNNING.name))
            .and(SYNC_RUNS.LOCKED_BY.eq(lockedBy))
            .and(SYNC_RUNS.LOCK_TOKEN.eq(lockToken))
            .and(SYNC_RUNS.ATTEMPTS.eq(attempts))

    private companion object {
        val SYNC_RUN_FIELDS: List<SelectFieldOrAsterisk> = listOf(
            SYNC_RUNS.ID,
            SYNC_RUNS.TARGET_TYPE,
            SYNC_RUNS.TARGET_ID,
            SYNC_RUNS.STATUS,
            SYNC_RUNS.QUEUED_AT,
            SYNC_RUNS.STARTED_AT,
            SYNC_RUNS.FINISHED_AT,
            SYNC_RUNS.EVENTS_SEEN,
            SYNC_RUNS.EVENTS_CHANGED,
            SYNC_RUNS.LAST_ERROR,
            SYNC_RUNS.ATTEMPTS,
            SYNC_RUNS.NEXT_ATTEMPT_AT,
            SYNC_RUNS.LOCKED_BY,
            SYNC_RUNS.LOCK_TOKEN,
            SYNC_RUNS.LOCKED_UNTIL,
            SYNC_RUNS.HEARTBEAT_AT,
            SYNC_RUNS.CREATED_AT,
            SYNC_RUNS.UPDATED_AT,
        )
    }
}
