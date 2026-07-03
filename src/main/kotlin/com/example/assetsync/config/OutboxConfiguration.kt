package com.example.assetsync.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxConfiguration

@ConfigurationProperties(prefix = "asset-sync.outbox")
data class OutboxProperties(
    val batchSize: Int = 50,
    val retryBackoffBaseDelay: Duration = Duration.ofSeconds(30),
    val retryBackoffMaxDelay: Duration = Duration.ofMinutes(15),
    val processingLease: Duration = Duration.ofMinutes(5),
    val maxAttempts: Int = 10,
    val maxErrorLength: Int = 1024,
    val retention: Retention = Retention(),
    val scheduler: Scheduler = Scheduler(),
) {
    init {
        require(batchSize > 0) { "asset-sync.outbox.batch-size must be positive." }
        require(!retryBackoffBaseDelay.isNegative && !retryBackoffBaseDelay.isZero) {
            "asset-sync.outbox.retry-backoff-base-delay must be positive."
        }
        require(!retryBackoffMaxDelay.isNegative && !retryBackoffMaxDelay.isZero) {
            "asset-sync.outbox.retry-backoff-max-delay must be positive."
        }
        require(!processingLease.isNegative && !processingLease.isZero) {
            "asset-sync.outbox.processing-lease must be positive."
        }
        require(maxAttempts > 0) { "asset-sync.outbox.max-attempts must be positive." }
        require(maxErrorLength > 0) { "asset-sync.outbox.max-error-length must be positive." }
    }

    data class Scheduler(
        val enabled: Boolean = true,
        val fixedDelay: Duration = Duration.ofSeconds(5),
        val initialDelay: Duration = Duration.ofSeconds(10),
    ) {
        init {
            require(!fixedDelay.isNegative && !fixedDelay.isZero) {
                "asset-sync.outbox.scheduler.fixed-delay must be positive."
            }
            require(!initialDelay.isNegative) {
                "asset-sync.outbox.scheduler.initial-delay must not be negative."
            }
        }
    }

    data class Retention(
        val enabled: Boolean = false,
        val publishedRetention: Duration = Duration.ofDays(7),
        val batchSize: Int = 1_000,
        val fixedDelay: Duration = Duration.ofHours(1),
        val initialDelay: Duration = Duration.ofMinutes(5),
    ) {
        init {
            require(!publishedRetention.isNegative && !publishedRetention.isZero) {
                "asset-sync.outbox.retention.published-retention must be positive."
            }
            require(batchSize > 0) { "asset-sync.outbox.retention.batch-size must be positive." }
            require(!fixedDelay.isNegative && !fixedDelay.isZero) {
                "asset-sync.outbox.retention.fixed-delay must be positive."
            }
            require(!initialDelay.isNegative) {
                "asset-sync.outbox.retention.initial-delay must not be negative."
            }
        }
    }
}
