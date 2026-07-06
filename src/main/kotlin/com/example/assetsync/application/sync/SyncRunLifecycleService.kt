package com.example.assetsync.application.sync

import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.config.SyncProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SyncRunLifecycleService(
    private val syncRunRepository: SyncRunRepository,
    private val syncCursorRepository: SyncCursorRepository,
    private val metrics: AssetSyncMetrics,
    private val clock: Clock,
    private val syncProperties: SyncProperties,
) {
    private val logger = LoggerFactory.getLogger(SyncRunLifecycleService::class.java)

    @Transactional
    fun createQueued(targetType: SyncTargetType, targetId: UUID): SyncRun {
        syncRunRepository.findInFlightByTarget(targetType = targetType, targetId = targetId)?.let { existing ->
            logger.info(
                "sync_run_in_flight_reused syncRunId={} targetType={} targetId={} status={}",
                existing.id,
                existing.targetType,
                existing.targetId,
                existing.status,
            )
            return existing
        }

        val countInFlight = syncRunRepository.countInFlight()
        if (countInFlight >= syncProperties.worker.maxInFlightRuns) {
            throw SyncQueueFullException(syncProperties.worker.maxInFlightRuns)
        }

        val now = Instant.now(clock)
        val inserted = syncRunRepository.insertQueued(
            NewQueuedSyncRun(
                id = UUID.randomUUID(),
                targetType = targetType,
                targetId = targetId,
                queuedAt = now,
                nextAttemptAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val syncRun = inserted
            ?: syncRunRepository.findInFlightByTarget(targetType = targetType, targetId = targetId)
            ?: error("Sync run for $targetType/$targetId conflicted but no in-flight row was found.")

        if (inserted != null) {
            metrics.recordSyncRun(targetType = syncRun.targetType, status = syncRun.status)
            logger.info(
                "sync_run_queued syncRunId={} targetType={} targetId={}",
                syncRun.id,
                syncRun.targetType,
                syncRun.targetId,
            )
        } else {
            logger.info(
                "sync_run_in_flight_reused_after_conflict syncRunId={} targetType={} targetId={} status={}",
                syncRun.id,
                syncRun.targetType,
                syncRun.targetId,
                syncRun.status,
            )
        }
        return syncRun
    }

    @Transactional
    fun claimDueRuns(workerId: String, limit: Int): List<ClaimedSyncRun> {
        val now = Instant.now(clock)
        val claimed = syncRunRepository.claimDueRuns(
            limit = limit,
            now = now,
            leaseUntil = now.plus(syncProperties.worker.leaseDuration),
            workerId = workerId,
        )
        claimed.forEach { claim ->
            metrics.recordSyncRun(targetType = claim.run.targetType, status = SyncRunStatus.RUNNING)
            logger.info(
                "sync_run_claimed syncRunId={} targetType={} targetId={} workerId={} attempts={} lockToken={}",
                claim.run.id,
                claim.run.targetType,
                claim.run.targetId,
                claim.lockedBy,
                claim.attempts,
                claim.lockToken,
            )
        }
        return claimed
    }

    @Transactional
    fun markSucceeded(claim: ClaimedSyncRun, eventsSeen: Int, eventsChanged: Int): Boolean {
        val now = Instant.now(clock)
        val marked = syncRunRepository.markSucceededFenced(
            id = claim.run.id,
            lockedBy = claim.lockedBy,
            lockToken = claim.lockToken,
            attempts = claim.attempts,
            eventsSeen = eventsSeen,
            eventsChanged = eventsChanged,
            finishedAt = now,
            updatedAt = now,
        )
        if (marked) {
            recordCompleted(
                syncRun = claim.run,
                status = SyncRunStatus.SUCCEEDED,
                eventsSeen = eventsSeen,
                eventsChanged = eventsChanged,
            )
        } else {
            logStaleClaimCompletion(claim = claim, intendedStatus = SyncRunStatus.SUCCEEDED)
        }
        return marked
    }

    @Transactional
    fun markFailed(
        claim: ClaimedSyncRun,
        eventsSeen: Int,
        eventsChanged: Int,
        lastError: String,
        failureAttempts: Int? = null,
    ): Boolean {
        val now = Instant.now(clock)
        val marked = syncRunRepository.markFailedFenced(
            id = claim.run.id,
            lockedBy = claim.lockedBy,
            lockToken = claim.lockToken,
            attempts = claim.attempts,
            eventsSeen = eventsSeen,
            eventsChanged = eventsChanged,
            lastError = lastError.take(syncProperties.worker.maxErrorLength),
            finishedAt = now,
            updatedAt = now,
            failureAttempts = failureAttempts,
        )
        if (marked) {
            recordCompleted(
                syncRun = claim.run,
                status = SyncRunStatus.FAILED,
                eventsSeen = eventsSeen,
                eventsChanged = eventsChanged,
            )
        } else {
            logStaleClaimCompletion(claim = claim, intendedStatus = SyncRunStatus.FAILED)
        }
        return marked
    }

    @Transactional
    fun requeue(claim: ClaimedSyncRun, eventsSeen: Int, eventsChanged: Int, lastError: String): Boolean {
        val now = Instant.now(clock)
        val nextAttemptAt = retryNextAttemptAtForClaimAttempts(now = now, attempts = claim.attempts, seed = claim.run.id)
        val requeued = syncRunRepository.requeueFenced(
            id = claim.run.id,
            lockedBy = claim.lockedBy,
            lockToken = claim.lockToken,
            attempts = claim.attempts,
            eventsSeen = eventsSeen,
            eventsChanged = eventsChanged,
            lastError = lastError.take(syncProperties.worker.maxErrorLength),
            nextAttemptAt = nextAttemptAt,
            updatedAt = now,
        )
        if (requeued) {
            logger.warn(
                "sync_run_requeued syncRunId={} targetType={} targetId={} attempts={} nextAttemptAt={} error={}",
                claim.run.id,
                claim.run.targetType,
                claim.run.targetId,
                claim.attempts,
                nextAttemptAt,
                lastError.take(syncProperties.worker.maxErrorLength),
            )
        } else {
            logStaleClaimCompletion(claim = claim, intendedStatus = SyncRunStatus.QUEUED)
        }
        return requeued
    }

    @Transactional
    fun requeueFailure(
        claim: ClaimedSyncRun,
        eventsSeen: Int,
        eventsChanged: Int,
        lastError: String,
        retryAfter: Instant? = null,
    ): Boolean {
        val now = Instant.now(clock)
        val expectedFailureAttempts = claim.run.failureAttempts
        val newFailureAttempts = expectedFailureAttempts + 1
        val clippedError = lastError.take(syncProperties.worker.maxErrorLength)
        return if (newFailureAttempts >= syncProperties.worker.maxAttempts) {
            markFailed(
                claim = claim,
                eventsSeen = eventsSeen,
                eventsChanged = eventsChanged,
                lastError = clippedError,
                failureAttempts = newFailureAttempts,
            )
        } else {
            val nextAttemptAt = retryAfter?.let { minOf(it, now.plus(syncProperties.worker.retryBackoffMaxDelay)) }
                ?: retryNextAttemptAt(now = now, failureAttempts = newFailureAttempts, seed = claim.run.id)
            val requeued = syncRunRepository.requeueFailureFenced(
                id = claim.run.id,
                lockedBy = claim.lockedBy,
                lockToken = claim.lockToken,
                attempts = claim.attempts,
                expectedFailureAttempts = expectedFailureAttempts,
                newFailureAttempts = newFailureAttempts,
                eventsSeen = eventsSeen,
                eventsChanged = eventsChanged,
                lastError = clippedError,
                nextAttemptAt = nextAttemptAt,
                updatedAt = now,
            )
            if (requeued) {
                logger.warn(
                    "sync_run_requeued_failure syncRunId={} targetType={} targetId={} attempts={} failureAttempts={} nextAttemptAt={} error={}",
                    claim.run.id,
                    claim.run.targetType,
                    claim.run.targetId,
                    claim.attempts,
                    newFailureAttempts,
                    nextAttemptAt,
                    clippedError,
                )
            } else {
                logStaleClaimCompletion(claim = claim, intendedStatus = SyncRunStatus.QUEUED)
            }
            requeued
        }
    }

    @Transactional
    fun requeueContinuation(
        claim: ClaimedSyncRun,
        eventsSeen: Int,
        eventsChanged: Int,
        reason: SyncRunRequeueReason,
        runCheckpoint: com.fasterxml.jackson.databind.node.ObjectNode,
        delay: Duration,
    ): SyncRunContinuationRequeueResult {
        val now = Instant.now(clock)
        val result = syncRunRepository.requeueContinuationFenced(
            id = claim.run.id,
            lockedBy = claim.lockedBy,
            lockToken = claim.lockToken,
            attempts = claim.attempts,
            eventsSeen = eventsSeen,
            eventsChanged = eventsChanged,
            reason = reason,
            runCheckpoint = runCheckpoint,
            nextAttemptAt = now.plus(delay),
            maxContinuationsPerRun = syncProperties.pagination.maxContinuationsPerRun,
            maxErrorLength = syncProperties.worker.maxErrorLength,
            updatedAt = now,
        )
        when (result) {
            SyncRunContinuationRequeueResult.REQUEUED -> {
                metrics.recordSyncContinuation(reason = reason, targetType = claim.run.targetType)
                logger.info(
                    "sync_run_requeued_continuation syncRunId={} targetType={} targetId={} reason={} continuationCount={} nextAttemptDelay={}",
                    claim.run.id,
                    claim.run.targetType,
                    claim.run.targetId,
                    reason,
                    claim.run.continuationCount + 1,
                    delay,
                )
            }
            SyncRunContinuationRequeueResult.FAILED_LIMIT_EXCEEDED ->
                logger.warn(
                    "sync_run_continuation_limit_exceeded syncRunId={} targetType={} targetId={} maxContinuations={}",
                    claim.run.id,
                    claim.run.targetType,
                    claim.run.targetId,
                    syncProperties.pagination.maxContinuationsPerRun,
                )
            SyncRunContinuationRequeueResult.STALE_CLAIM ->
                logStaleClaimCompletion(claim = claim, intendedStatus = SyncRunStatus.QUEUED)
        }
        return result
    }

    @Transactional
    fun heartbeat(claim: ClaimedSyncRun): Boolean {
        val now = Instant.now(clock)
        return syncRunRepository.heartbeatFenced(
            id = claim.run.id,
            lockedBy = claim.lockedBy,
            lockToken = claim.lockToken,
            attempts = claim.attempts,
            heartbeatAt = now,
            lockedUntil = now.plus(syncProperties.worker.leaseDuration),
            updatedAt = now,
        )
    }

    @Transactional(readOnly = true)
    fun get(syncRunId: UUID): SyncRun =
        syncRunRepository.findById(syncRunId) ?: throw SyncRunNotFoundException(syncRunId)

    @Transactional
    fun markStaleStartedFailed(): Int {
        val now = Instant.now(clock)
        val cutoff = now.minus(syncProperties.staleRunTimeout)
        val staleRuns = syncRunRepository.findStaleStarted(
            cutoff = cutoff,
            limit = syncProperties.recovery.batchSize,
        )
        var recovered = 0
        staleRuns.forEach { syncRun ->
            // Status-guarded: skips a run a real completion resolved between select and update.
            val abandoned = syncRunRepository.markAbandoned(
                id = syncRun.id,
                lastError = "abandoned: no completion within ${syncProperties.staleRunTimeout}",
                finishedAt = now,
                updatedAt = now,
            )
            if (abandoned != null) {
                recordCompleted(
                    syncRun = abandoned,
                    status = abandoned.status,
                    eventsSeen = abandoned.eventsSeen,
                    eventsChanged = abandoned.eventsChanged,
                )
                recovered += 1
            }
        }
        if (recovered > 0) {
            logger.warn("sync_runs_recovered_stale count={} cutoff={}", recovered, cutoff)
        }
        return recovered
    }

    @Transactional
    fun recoverExpiredRunning(): Int {
        val now = Instant.now(clock)
        val recoveredRuns = syncRunRepository.recoverExpiredRunning(
            now = now,
            limit = syncProperties.recovery.batchSize,
            maxFailureAttempts = syncProperties.worker.maxAttempts,
            retryNextAttemptAt = { syncRun, failureAttempts ->
                retryNextAttemptAt(now = now, failureAttempts = failureAttempts, seed = syncRun.id)
            },
            maxErrorLength = syncProperties.worker.maxErrorLength,
        )
        recoveredRuns.forEach { syncRun ->
            when (syncRun.status) {
                SyncRunStatus.FAILED -> {
                    metrics.recordSyncRun(targetType = syncRun.targetType, status = syncRun.status)
                    logger.warn(
                        "sync_run_expired_failed syncRunId={} targetType={} targetId={} attempts={}",
                        syncRun.id,
                        syncRun.targetType,
                        syncRun.targetId,
                        syncRun.attempts,
                    )
                }
                SyncRunStatus.QUEUED ->
                    logger.warn(
                        "sync_run_expired_requeued syncRunId={} targetType={} targetId={} attempts={} nextAttemptAt={}",
                        syncRun.id,
                        syncRun.targetType,
                        syncRun.targetId,
                        syncRun.attempts,
                        syncRun.nextAttemptAt,
                    )
                else -> Unit
            }
        }
        if (recoveredRuns.isNotEmpty()) {
            logger.warn("sync_runs_recovered_expired_running count={}", recoveredRuns.size)
        }
        return recoveredRuns.size
    }

    @Transactional
    fun clearExpiredCursorLeases(): Int {
        val now = Instant.now(clock)
        val cleared = syncCursorRepository.clearExpiredCursorLeases(
            now = now,
            limit = syncProperties.recovery.batchSize,
        )
        if (cleared > 0) {
            logger.warn("sync_cursor_leases_recovered_expired count={}", cleared)
        }
        return cleared
    }

    fun retryNextAttemptAtForClaimAttempts(now: Instant, attempts: Int, seed: UUID): Instant =
        now.plus(backoffDelay(attempts = attempts, seed = seed))

    fun retryNextAttemptAt(now: Instant, failureAttempts: Int, seed: UUID): Instant =
        now.plus(backoffDelay(attempts = failureAttempts, seed = seed))

    private fun recordCompleted(syncRun: SyncRun, status: SyncRunStatus, eventsSeen: Int, eventsChanged: Int) {
        metrics.recordSyncRun(targetType = syncRun.targetType, status = status)
        logger.info(
            "sync_run_completed syncRunId={} targetType={} targetId={} status={} eventsSeen={} eventsChanged={}",
            syncRun.id,
            syncRun.targetType,
            syncRun.targetId,
            status,
            eventsSeen,
            eventsChanged,
        )
    }

    private fun logStaleClaimCompletion(claim: ClaimedSyncRun, intendedStatus: SyncRunStatus) {
        logger.debug(
            "sync_run_claim_completion_stale syncRunId={} targetType={} targetId={} intendedStatus={} workerId={} attempts={} lockToken={}",
            claim.run.id,
            claim.run.targetType,
            claim.run.targetId,
            intendedStatus,
            claim.lockedBy,
            claim.attempts,
            claim.lockToken,
        )
    }

    private fun backoffDelay(attempts: Int, seed: UUID): Duration {
        val base = minOf(
            runCatching {
                syncProperties.worker.retryBackoffBaseDelay.multipliedBy(
                    1L shl (attempts - 1).coerceAtLeast(0).coerceAtMost(30),
                )
            }.getOrDefault(syncProperties.worker.retryBackoffMaxDelay),
            syncProperties.worker.retryBackoffMaxDelay,
        )
        val jitterCeilingMillis = (base.toMillis() / 4).coerceAtLeast(1)
        val jitterMillis = Math.floorMod(seed.leastSignificantBits, jitterCeilingMillis)
        return base.minusMillis(jitterMillis)
    }
}
