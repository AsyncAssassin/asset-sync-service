package com.example.assetsync.application.sync

import java.time.Instant
import java.util.UUID

enum class SyncTargetType {
    ADDRESS,
    ACCOUNT,
}

enum class SyncRunStatus {
    STARTED,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class NewQueuedSyncRun(
    val id: UUID,
    val targetType: SyncTargetType,
    val targetId: UUID,
    val queuedAt: Instant,
    val nextAttemptAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SyncRun(
    val id: UUID,
    val targetType: SyncTargetType,
    val targetId: UUID,
    val status: SyncRunStatus,
    val eventsSeen: Int,
    val eventsChanged: Int,
    val lastError: String?,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val lockedBy: String?,
    val lockToken: UUID?,
    val lockedUntil: Instant?,
    val heartbeatAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ClaimedSyncRun(
    val run: SyncRun,
    val lockedBy: String,
    val lockToken: UUID,
    val attempts: Int,
)

interface SyncRunRepository {
    fun insertQueued(syncRun: NewQueuedSyncRun): SyncRun?

    fun findInFlightByTarget(targetType: SyncTargetType, targetId: UUID): SyncRun?

    fun countInFlight(): Int

    fun countRunning(): Int

    fun claimDueRuns(limit: Int, now: Instant, leaseUntil: Instant, workerId: String): List<ClaimedSyncRun>

    fun markSucceededFenced(
        id: UUID,
        lockedBy: String,
        lockToken: UUID,
        attempts: Int,
        eventsSeen: Int,
        eventsChanged: Int,
        finishedAt: Instant,
        updatedAt: Instant,
    ): Boolean

    fun markFailedFenced(
        id: UUID,
        lockedBy: String,
        lockToken: UUID,
        attempts: Int,
        eventsSeen: Int,
        eventsChanged: Int,
        lastError: String,
        finishedAt: Instant,
        updatedAt: Instant,
    ): Boolean

    fun requeueFenced(
        id: UUID,
        lockedBy: String,
        lockToken: UUID,
        attempts: Int,
        eventsSeen: Int,
        eventsChanged: Int,
        lastError: String,
        nextAttemptAt: Instant,
        updatedAt: Instant,
    ): Boolean

    fun heartbeatFenced(
        id: UUID,
        lockedBy: String,
        lockToken: UUID,
        attempts: Int,
        heartbeatAt: Instant,
        lockedUntil: Instant,
        updatedAt: Instant,
    ): Boolean

    fun recoverExpiredRunning(
        now: Instant,
        limit: Int,
        maxAttempts: Int,
        retryNextAttemptAt: (SyncRun) -> Instant,
        maxErrorLength: Int,
    ): List<SyncRun>

    fun findExpiredRunning(now: Instant, limit: Int): List<SyncRun>

    fun findById(syncRunId: UUID): SyncRun?

    fun findStaleStarted(cutoff: Instant, limit: Int): List<SyncRun>

    /**
     * Marks a run FAILED only if it is still STARTED (status-guarded, tolerant of 0 rows). Returns
     * null when the row is no longer STARTED — so the sweeper can never overwrite a run that a real
     * completion resolved to SUCCEEDED/FAILED in the meantime.
     */
    fun markAbandoned(id: UUID, lastError: String, finishedAt: Instant, updatedAt: Instant): SyncRun?
}

class SyncRunNotFoundException(
    val syncRunId: UUID,
) : RuntimeException("Sync run was not found.")

class WatchedAddressByIdNotFoundException(
    val addressId: UUID,
) : RuntimeException("Active watched address was not found.")

class SyncProviderUnavailableException(
    val syncRun: SyncRun,
    cause: Throwable? = null,
) : RuntimeException("Provider is unavailable.", cause)

class SyncQueueFullException(
    val maxInFlightRuns: Int,
) : RuntimeException("Sync queue is full: at most $maxInFlightRuns queued or running syncs.")
