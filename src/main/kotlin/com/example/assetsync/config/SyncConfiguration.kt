package com.example.assetsync.config

import java.time.Duration
import java.util.concurrent.ExecutorService
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

    /** Admission gate for concurrent syncs, sized to the provider pool. Acquired at sync entry
     * (before a run is created) so an over-cap sync is rejected cleanly without an orphan run. */
    @Bean
    fun syncCapacitySemaphore(syncProperties: SyncProperties): Semaphore =
        Semaphore(syncProperties.providerMaxThreads)
}

@ConfigurationProperties(prefix = "asset-sync.sync")
data class SyncProperties(
    val providerTimeout: Duration = Duration.ofSeconds(10),
    val providerMaxThreads: Int = 4,
    val accountSyncBatchSize: Int = 100,
    val maxAccountSyncAddresses: Int = 1_000,
    val staleRunTimeout: Duration = Duration.ofMinutes(30),
    val recovery: Recovery = Recovery(),
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
