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
