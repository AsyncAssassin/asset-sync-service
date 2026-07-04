package com.example.assetsync.infrastructure.sync

import com.example.assetsync.application.sync.SyncRunLifecycleService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "asset-sync.sync.recovery",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SyncRunRecoveryJob(
    private val syncRunLifecycleService: SyncRunLifecycleService,
) {
    private val logger = LoggerFactory.getLogger(SyncRunRecoveryJob::class.java)

    @Scheduled(
        fixedDelayString = "\${asset-sync.sync.recovery.fixed-delay:5m}",
        initialDelayString = "\${asset-sync.sync.recovery.initial-delay:5m}",
    )
    fun recoverStaleRuns() {
        try {
            syncRunLifecycleService.markStaleStartedFailed()
            syncRunLifecycleService.recoverExpiredRunning()
        } catch (exception: Exception) {
            logger.error(
                "sync_run_recovery_failed exceptionClass={} error={}",
                exception.javaClass.simpleName,
                exception.message ?: "sync run recovery failed",
                exception,
            )
        }
    }
}
