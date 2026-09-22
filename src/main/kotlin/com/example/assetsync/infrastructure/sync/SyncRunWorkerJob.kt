package com.example.assetsync.infrastructure.sync

import com.example.assetsync.application.sync.ClaimedSyncRun
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.config.SyncProperties
import java.net.InetAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Claims due sync runs on a schedule and hands them to the worker executor.
 *
 * It is also a [SmartLifecycle] so that a graceful shutdown drains in-flight runs instead of cutting
 * them off when the executors are destroyed: [stop] refuses new claims, lets running claims finish
 * for up to `asset-sync.sync.worker.shutdown-timeout`, then interrupts the rest. An interrupted run
 * is requeued without consuming its retry budget (see `SyncApplicationService.executeClaimedSyncRun`).
 * The lifecycle shares the web server's graceful-shutdown phase, so HTTP requests and sync runs
 * drain concurrently within `spring.lifecycle.timeout-per-shutdown-phase`.
 */
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
    private val syncProviderExecutor: ExecutorService,
    private val syncWorkerPermitSemaphore: Semaphore,
    private val syncProperties: SyncProperties,
    @Value("\${spring.application.name:asset-sync-service}") applicationName: String,
) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(SyncRunWorkerJob::class.java)
    private val workerId = buildWorkerId(applicationName)
    private val running = AtomicBoolean(false)

    @Volatile
    private var draining = false

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
        if (draining) {
            logger.debug("sync_worker_draining_skip_claim workerId={}", workerId)
            return 0
        }

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

    override fun start() {
        running.set(true)
    }

    override fun stop() {
        if (!running.getAndSet(false)) {
            return
        }
        draining = true
        val timeout = syncProperties.worker.shutdownTimeout
        logger.info("sync_worker_draining workerId={} timeout={}", workerId, timeout)

        syncWorkerExecutor.shutdown()
        val drained = awaitTerminationQuietly(syncWorkerExecutor, timeout)
        if (!drained) {
            logger.warn(
                "sync_worker_drain_timeout workerId={} timeout={} interruptingInFlightRuns=true",
                workerId,
                timeout,
            )
            // Interrupted runs requeue themselves; give them a moment to persist that before the
            // context moves on to destroying the data source.
            syncWorkerExecutor.shutdownNow()
            val settled = awaitTerminationQuietly(syncWorkerExecutor, FORCE_STOP_GRACE)
            if (!settled) {
                logger.error("sync_worker_stop_incomplete workerId={} grace={}", workerId, FORCE_STOP_GRACE)
            }
        }
        syncProviderExecutor.shutdown()
        logger.info("sync_worker_stopped workerId={} drained={}", workerId, drained)
    }

    /**
     * Asynchronous form used by Spring's lifecycle processor. Draining runs on its own thread so the
     * web server's graceful shutdown, which shares this phase, proceeds concurrently and the phase
     * timeout bounds both together.
     */
    override fun stop(callback: Runnable) {
        if (!running.get()) {
            callback.run()
            return
        }
        Thread(
            {
                try {
                    stop()
                } finally {
                    callback.run()
                }
            },
            "asset-sync-worker-shutdown",
        ).start()
    }

    override fun isRunning(): Boolean = running.get()

    override fun getPhase(): Int = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE

    private fun awaitTerminationQuietly(executor: ExecutorService, timeout: Duration): Boolean =
        try {
            executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            false
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

    private companion object {
        /** Extra time after interrupting in-flight runs so they can persist their requeue. */
        val FORCE_STOP_GRACE: Duration = Duration.ofSeconds(5)
    }
}
