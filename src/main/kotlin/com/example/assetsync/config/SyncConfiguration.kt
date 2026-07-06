package com.example.assetsync.config

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(SyncProperties::class)
class SyncConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    fun syncProviderExecutor(syncProperties: SyncProperties): ExecutorService =
        ThreadPoolExecutor(
            syncProperties.providerMaxThreads,
            syncProperties.providerMaxThreads,
            0L,
            TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            NamedThreadFactory("asset-sync-provider"),
            ThreadPoolExecutor.AbortPolicy(),
        )

    @Bean(destroyMethod = "shutdownNow")
    fun syncWorkerExecutor(syncProperties: SyncProperties): ExecutorService =
        ThreadPoolExecutor(
            syncProperties.worker.maxConcurrency,
            syncProperties.worker.maxConcurrency,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            NamedThreadFactory("asset-sync-worker"),
            ThreadPoolExecutor.AbortPolicy(),
        )

    @Bean(destroyMethod = "shutdownNow")
    fun syncHeartbeatScheduler(syncProperties: SyncProperties): SyncHeartbeatScheduler =
        SyncHeartbeatScheduler(syncProperties.worker.maxConcurrency)

    @Bean
    fun syncWorkerPermitSemaphore(syncProperties: SyncProperties): Semaphore =
        Semaphore(syncProperties.worker.maxConcurrency)
}

@ConfigurationProperties(prefix = "asset-sync.sync")
data class SyncProperties(
    val providerTimeout: Duration = Duration.ofSeconds(10),
    val providerMaxThreads: Int = 4,
    val accountSyncBatchSize: Int = 100,
    val maxAccountSyncAddresses: Int = 1_000,
    val staleRunTimeout: Duration = Duration.ofMinutes(30),
    val pagination: Pagination = Pagination(),
    val recovery: Recovery = Recovery(),
    val worker: Worker = Worker(),
) {
    init {
        require(!providerTimeout.isNegative && !providerTimeout.isZero) {
            "asset-sync.sync.provider-timeout must be positive."
        }
        require(providerMaxThreads > 0) { "asset-sync.sync.provider-max-threads must be positive." }
        require(accountSyncBatchSize > 0) { "asset-sync.sync.account-sync-batch-size must be positive." }
        require(maxAccountSyncAddresses > 0) { "asset-sync.sync.max-account-sync-addresses must be positive." }
        require(!staleRunTimeout.isNegative && !staleRunTimeout.isZero) {
            "asset-sync.sync.stale-run-timeout must be positive."
        }
        require(worker.maxConcurrency <= providerMaxThreads) {
            "asset-sync.sync.worker.max-concurrency must be <= asset-sync.sync.provider-max-threads."
        }
        require(pagination.cursorLeaseDuration > providerTimeout) {
            "asset-sync.sync.pagination.cursor-lease-duration must be greater than provider-timeout."
        }
        require(pagination.pageSize <= pagination.maxEventsPerAddressRun) {
            "asset-sync.sync.pagination.page-size must be <= max-events-per-address-run."
        }
        require(pagination.pageSize <= pagination.maxEventsPerAccountRun) {
            "asset-sync.sync.pagination.page-size must be <= max-events-per-account-run."
        }
        require(pagination.maxPagesPerAccountRun >= pagination.maxPagesPerAddressRun) {
            "asset-sync.sync.pagination.max-pages-per-account-run must be >= max-pages-per-address-run."
        }
    }

    data class Pagination(
        val pageSize: Int = 100,
        val maxPagesPerAddressRun: Int = 50,
        val maxEventsPerAddressRun: Int = 5_000,
        val maxPagesPerAccountRun: Int = 200,
        val maxEventsPerAccountRun: Int = 20_000,
        val maxRunDuration: Duration = Duration.ofMinutes(2),
        val cursorLeaseDuration: Duration = Duration.ofMinutes(2),
        val cursorHeartbeatInterval: Duration = Duration.ofSeconds(30),
        val cursorLeaseRetryDelay: Duration = Duration.ofSeconds(5),
        val continuationRequeueDelay: Duration = Duration.ofSeconds(1),
        val maxContinuationsPerRun: Int = 1_000,
        val maxCursorLength: Int = 4_096,
        val maxCheckpointJsonLength: Int = 16_384,
        val maxProviderPageBytes: Int = 1_048_576,
    ) {
        init {
            require(pageSize in 1..1_000) {
                "asset-sync.sync.pagination.page-size must be between 1 and 1000."
            }
            require(maxPagesPerAddressRun > 0) {
                "asset-sync.sync.pagination.max-pages-per-address-run must be positive."
            }
            require(maxEventsPerAddressRun > 0) {
                "asset-sync.sync.pagination.max-events-per-address-run must be positive."
            }
            require(maxPagesPerAccountRun > 0) {
                "asset-sync.sync.pagination.max-pages-per-account-run must be positive."
            }
            require(maxEventsPerAccountRun > 0) {
                "asset-sync.sync.pagination.max-events-per-account-run must be positive."
            }
            require(!maxRunDuration.isNegative && !maxRunDuration.isZero) {
                "asset-sync.sync.pagination.max-run-duration must be positive."
            }
            require(!cursorLeaseDuration.isNegative && !cursorLeaseDuration.isZero) {
                "asset-sync.sync.pagination.cursor-lease-duration must be positive."
            }
            require(!cursorHeartbeatInterval.isNegative && !cursorHeartbeatInterval.isZero) {
                "asset-sync.sync.pagination.cursor-heartbeat-interval must be positive."
            }
            require(cursorHeartbeatInterval < cursorLeaseDuration) {
                "asset-sync.sync.pagination.cursor-heartbeat-interval must be less than cursor-lease-duration."
            }
            require(!cursorLeaseRetryDelay.isNegative) {
                "asset-sync.sync.pagination.cursor-lease-retry-delay must not be negative."
            }
            require(!continuationRequeueDelay.isNegative) {
                "asset-sync.sync.pagination.continuation-requeue-delay must not be negative."
            }
            require(maxContinuationsPerRun >= 0) {
                "asset-sync.sync.pagination.max-continuations-per-run must not be negative."
            }
            require(maxCursorLength in 1..4_096) {
                "asset-sync.sync.pagination.max-cursor-length must be between 1 and 4096."
            }
            require(maxCheckpointJsonLength in 1..16_384) {
                "asset-sync.sync.pagination.max-checkpoint-json-length must be between 1 and 16384."
            }
            require(maxProviderPageBytes > 0) {
                "asset-sync.sync.pagination.max-provider-page-bytes must be positive."
            }
        }
    }

    data class Recovery(
        val enabled: Boolean = true,
        val batchSize: Int = 100,
        val fixedDelay: Duration = Duration.ofMinutes(5),
        val initialDelay: Duration = Duration.ofMinutes(5),
    ) {
        init {
            require(batchSize > 0) { "asset-sync.sync.recovery.batch-size must be positive." }
            require(!fixedDelay.isNegative && !fixedDelay.isZero) {
                "asset-sync.sync.recovery.fixed-delay must be positive."
            }
            require(!initialDelay.isNegative) {
                "asset-sync.sync.recovery.initial-delay must not be negative."
            }
        }
    }

    data class Worker(
        val enabled: Boolean = true,
        val fixedDelay: Duration = Duration.ofSeconds(5),
        val initialDelay: Duration = Duration.ofSeconds(10),
        val claimBatchSize: Int = 10,
        val maxConcurrency: Int = 4,
        val leaseDuration: Duration = Duration.ofSeconds(60),
        val heartbeatInterval: Duration = Duration.ofSeconds(20),
        val maxAttempts: Int = 5,
        val retryBackoffBaseDelay: Duration = Duration.ofSeconds(30),
        val retryBackoffMaxDelay: Duration = Duration.ofMinutes(15),
        val maxInFlightRuns: Int = 1_000,
        val maxErrorLength: Int = 1_024,
    ) {
        init {
            require(!fixedDelay.isNegative && !fixedDelay.isZero) {
                "asset-sync.sync.worker.fixed-delay must be positive."
            }
            require(!initialDelay.isNegative) {
                "asset-sync.sync.worker.initial-delay must not be negative."
            }
            require(claimBatchSize > 0) {
                "asset-sync.sync.worker.claim-batch-size must be positive."
            }
            require(maxConcurrency > 0) {
                "asset-sync.sync.worker.max-concurrency must be positive."
            }
            require(!leaseDuration.isNegative && !leaseDuration.isZero) {
                "asset-sync.sync.worker.lease-duration must be positive."
            }
            require(!heartbeatInterval.isNegative && !heartbeatInterval.isZero) {
                "asset-sync.sync.worker.heartbeat-interval must be positive."
            }
            require(heartbeatInterval < leaseDuration) {
                "asset-sync.sync.worker.heartbeat-interval must be less than lease-duration."
            }
            require(maxAttempts > 0) {
                "asset-sync.sync.worker.max-attempts must be positive."
            }
            require(!retryBackoffBaseDelay.isNegative && !retryBackoffBaseDelay.isZero) {
                "asset-sync.sync.worker.retry-backoff-base-delay must be positive."
            }
            require(!retryBackoffMaxDelay.isNegative && !retryBackoffMaxDelay.isZero) {
                "asset-sync.sync.worker.retry-backoff-max-delay must be positive."
            }
            require(maxInFlightRuns > 0) {
                "asset-sync.sync.worker.max-in-flight-runs must be positive."
            }
            require(maxErrorLength in 1..1_024) {
                "asset-sync.sync.worker.max-error-length must be between 1 and 1024."
            }
        }
    }
}

class SyncHeartbeatScheduler(maxConcurrency: Int) {
    private val executor = Executors.newScheduledThreadPool(
        maxConcurrency,
        NamedThreadFactory("asset-sync-heartbeat"),
    )

    fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
        executor.scheduleAtFixedRate(command, initialDelay, period, unit)

    fun shutdownNow() {
        executor.shutdownNow()
    }
}

private class NamedThreadFactory(
    private val prefix: String,
) : ThreadFactory {
    private val counter = AtomicInteger(0)

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply {
            isDaemon = true
        }
}
