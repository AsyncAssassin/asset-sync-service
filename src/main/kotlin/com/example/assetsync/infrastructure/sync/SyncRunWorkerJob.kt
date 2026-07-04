package com.example.assetsync.infrastructure.sync

import com.example.assetsync.application.sync.ClaimedSyncRun
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.config.SyncProperties
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "asset-sync.sync.worker",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SyncRunWorkerJob(
    private val syncRunLifecycleService: SyncRunLifecycleService,
    private val syncApplicationService: SyncApplicationService,
    private val syncWorkerExecutor: ExecutorService,
    private val syncWorkerPermitSemaphore: Semaphore,
    private val syncProperties: SyncProperties,
    @Value("\${spring.application.name:asset-sync-service}") applicationName: String,
) {
    private val logger = LoggerFactory.getLogger(SyncRunWorkerJob::class.java)
    private val workerId = buildWorkerId(applicationName)

    @Scheduled(
        fixedDelayString = "\${asset-sync.sync.worker.fixed-delay:5s}",
        initialDelayString = "\${asset-sync.sync.worker.initial-delay:10s}",
    )
    fun processDueRuns() {
        try {
            claimAndSubmitAvailableRuns()
        } catch (exception: Exception) {
            logger.error(
                "sync_worker_tick_failed workerId={} exceptionClass={} error={}",
                workerId,
                exception.javaClass.simpleName,
                exception.message ?: "sync worker tick failed",
                exception,
            )
        }
    }

    fun claimAndSubmitAvailableRuns(): Int {
        val reservedPermits = reservePermits(syncProperties.worker.claimBatchSize)
        if (reservedPermits == 0) {
            logger.debug("sync_worker_no_local_capacity workerId={}", workerId)
            return 0
        }

        val claimed = try {
            syncRunLifecycleService.claimDueRuns(workerId = workerId, limit = reservedPermits)
        } catch (exception: Exception) {
            syncWorkerPermitSemaphore.release(reservedPermits)
            throw exception
        }

        val unusedPermits = reservedPermits - claimed.size
        if (unusedPermits > 0) {
            syncWorkerPermitSemaphore.release(unusedPermits)
        }

        claimed.forEach { claim ->
            submitClaim(claim)
        }

        if (claimed.isNotEmpty()) {
            logger.info("sync_worker_claimed workerId={} claimed={}", workerId, claimed.size)
        } else {
            logger.debug("sync_worker_claimed workerId={} claimed=0", workerId)
        }
        return claimed.size
    }

    private fun submitClaim(claim: ClaimedSyncRun) {
        try {
            syncWorkerExecutor.execute {
                try {
                    syncApplicationService.executeClaimedSyncRun(claim)
                } finally {
                    syncWorkerPermitSemaphore.release()
                }
            }
        } catch (exception: RejectedExecutionException) {
            try {
                val requeued = syncRunLifecycleService.requeue(
                    claim = claim,
                    eventsSeen = claim.run.eventsSeen,
                    eventsChanged = claim.run.eventsChanged,
                    lastError = "worker executor rejected claimed sync run",
                )
                if (!requeued) {
                    logger.warn(
                        "sync_worker_rejection_requeue_stale syncRunId={} workerId={} lockToken={}",
                        claim.run.id,
                        claim.lockedBy,
                        claim.lockToken,
                    )
                }
            } catch (requeueException: Exception) {
                logger.error(
                    "sync_worker_rejection_requeue_failed syncRunId={} workerId={} error={}",
                    claim.run.id,
                    claim.lockedBy,
                    requeueException.message ?: requeueException.javaClass.simpleName,
                    requeueException,
                )
            } finally {
                syncWorkerPermitSemaphore.release()
            }
        }
    }

    private fun reservePermits(maxPermits: Int): Int {
        var reserved = 0
        while (reserved < maxPermits && syncWorkerPermitSemaphore.tryAcquire()) {
            reserved += 1
        }
        return reserved
    }

    private fun buildWorkerId(applicationName: String): String {
        val hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown-host")
        val pid = runCatching { ProcessHandle.current().pid().toString() }.getOrDefault("unknown-pid")
        val startupId = UUID.randomUUID()
        return "$applicationName:$hostname:$pid:$startupId".take(200)
    }
}
