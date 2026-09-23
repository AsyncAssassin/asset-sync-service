package com.example.assetsync.application.sync

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Duration
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
    val failureAttempts: Int,
    val continuationCount: Int,
    val runCheckpoint: ObjectNode,
    val lastRequeueReason: SyncRunRequeueReason?,
    val nextAttemptAt: Instant,
    val lockedBy: String?,
    val lockToken: UUID?,
    val lockedUntil: Instant?,
    val heartbeatAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class SyncRunRequeueReason {
    FAILURE,
    CONTINUATION,
    LEASE_BUSY,
}

enum class SyncRunContinuationRequeueResult {
    REQUEUED,
    FAILED_LIMIT_EXCEEDED,
    STALE_CLAIM,
}

data class SyncCursor(
    val watchedAddressId: UUID,
    val providerCursor: String?,
    val checkpoint: ObjectNode,
    val lastProcessedBlockHeight: Long?,
    val lastProcessedEventIndex: Int?,
    val lastFinalizedBlockHeight: Long?,
    val version: Long,
    val lockedBy: String?,
    val lockToken: UUID?,
    val lockedUntil: Instant?,
    val cursorUpdatedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AcquiredSyncCursorLease(
    val cursor: SyncCursor,
    val lockedBy: String,
    val lockToken: UUID,
)

data class AdvanceSyncCheckpointCommand(
    val watchedAddressId: UUID,
    val lockedBy: String,
    val lockToken: UUID,
    val expectedVersion: Long,
    val leaseCheckedAt: Instant,
    val providerCursor: String?,
    val checkpoint: ObjectNode,
    val lastProcessedBlockHeight: Long?,
    val lastProcessedEventIndex: Int?,
    val lastFinalizedBlockHeight: Long?,
    val cursorUpdatedAt: Instant,
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
        failureAttempts: Int? = null,
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

    fun requeueContinuationFenced(
        id: UUID,
        lockedBy: String,
        lockToken: UUID,
        attempts: Int,
        eventsSeen: Int,
        eventsChanged: Int,
        reason: SyncRunRequeueReason,
        runCheckpoint: ObjectNode,
        nextAttemptAt: Instant,
        maxContinuationsPerRun: Int,
        maxErrorLength: Int,
        updatedAt: Instant,
    ): SyncRunContinuationRequeueResult

    fun requeueFailureFenced(
        id: UUID,
        lockedBy: String,
        lockToken: UUID,
        attempts: Int,
        expectedFailureAttempts: Int,
        newFailureAttempts: Int,
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
        maxFailureAttempts: Int,
        retryNextAttemptAt: (SyncRun, Int) -> Instant,
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

interface SyncCursorRepository {
    fun ensureCursor(watchedAddressId: UUID, now: Instant): SyncCursor

    fun findCursor(watchedAddressId: UUID): SyncCursor?

    fun tryAcquireCursorLease(
        watchedAddressId: UUID,
        lockedBy: String,
        lockToken: UUID,
        now: Instant,
        leaseUntil: Instant,
    ): AcquiredSyncCursorLease?

    fun extendCursorLease(
        watchedAddressId: UUID,
        lockedBy: String,
        lockToken: UUID,
        leaseUntil: Instant,
        updatedAt: Instant,
    ): Boolean

    fun advanceCheckpointFenced(command: AdvanceSyncCheckpointCommand): SyncCursor?

    fun releaseCursorLease(watchedAddressId: UUID, updatedAt: Instant): Boolean

    fun releaseCursorLeaseFenced(
        watchedAddressId: UUID,
        lockedBy: String,
        lockToken: UUID,
        updatedAt: Instant,
    ): Boolean

    fun findExpiredCursorLeases(now: Instant, limit: Int): List<SyncCursor>

    fun clearExpiredCursorLeases(now: Instant, limit: Int): Int
}

class SyncRunNotFoundException(
    val syncRunId: UUID,
) : RuntimeException("Sync run was not found.")

class WatchedAddressByIdNotFoundException(
    val addressId: UUID,
) : RuntimeException("Active watched address was not found.")

class SyncQueueFullException(
    val maxInFlightRuns: Int,
    val retryAfter: Duration,
) : RuntimeException("Sync queue is full: at most $maxInFlightRuns queued or running syncs.")
