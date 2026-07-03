package com.example.assetsync.application.sync

import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.config.SyncProperties
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SyncRunLifecycleService(
    private val syncRunRepository: SyncRunRepository,
    private val metrics: AssetSyncMetrics,
    private val clock: Clock,
    private val syncProperties: SyncProperties,
) {
    private val logger = LoggerFactory.getLogger(SyncRunLifecycleService::class.java)

    @Transactional
    fun createStarted(targetType: SyncTargetType, targetId: UUID): SyncRun {
        val now = Instant.now(clock)
        val syncRun = syncRunRepository.insert(
            NewSyncRun(
                id = UUID.randomUUID(),
                targetType = targetType,
                targetId = targetId,
                status = SyncRunStatus.STARTED,
                startedAt = now,
                finishedAt = null,
                eventsSeen = 0,
                eventsChanged = 0,
                lastError = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        metrics.recordSyncRun(targetType = syncRun.targetType, status = syncRun.status)
        logger.info(
            "sync_run_started syncRunId={} targetType={} targetId={}",
            syncRun.id,
            syncRun.targetType,
            syncRun.targetId,
        )
        return syncRun
    }

    @Transactional
    fun markSucceeded(syncRunId: UUID, eventsSeen: Int, eventsChanged: Int): SyncRun {
        val now = Instant.now(clock)
        val syncRun = syncRunRepository.markCompleted(
            SyncRunCompletion(
                id = syncRunId,
                status = SyncRunStatus.SUCCEEDED,
                eventsSeen = eventsSeen,
                eventsChanged = eventsChanged,
                lastError = null,
                finishedAt = now,
                updatedAt = now,
            ),
        )
        recordCompleted(syncRun)
        return syncRun
    }

    @Transactional
    fun markFailed(syncRunId: UUID, eventsSeen: Int, eventsChanged: Int, lastError: String): SyncRun {
        val now = Instant.now(clock)
        val syncRun = syncRunRepository.markCompleted(
            SyncRunCompletion(
                id = syncRunId,
                status = SyncRunStatus.FAILED,
                eventsSeen = eventsSeen,
                eventsChanged = eventsChanged,
                lastError = lastError,
                finishedAt = now,
                updatedAt = now,
            ),
        )
        recordCompleted(syncRun)
        return syncRun
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
                recordCompleted(abandoned)
                recovered += 1
            }
        }
        if (recovered > 0) {
            logger.warn("sync_runs_recovered_stale count={} cutoff={}", recovered, cutoff)
        }
        return recovered
    }

    private fun recordCompleted(syncRun: SyncRun) {
        metrics.recordSyncRun(targetType = syncRun.targetType, status = syncRun.status)
        logger.info(
            "sync_run_completed syncRunId={} targetType={} targetId={} status={} eventsSeen={} eventsChanged={}",
            syncRun.id,
            syncRun.targetType,
            syncRun.targetId,
            syncRun.status,
            syncRun.eventsSeen,
            syncRun.eventsChanged,
        )
    }
}
