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
    val maxErrorLength: Int = 1024,
    val scheduler: Scheduler = Scheduler(),
) {
    init {
        require(batchSize > 0) { "asset-sync.outbox.batch-size must be positive." }
        require(!retryBackoffBaseDelay.isNegative && !retryBackoffBaseDelay.isZero) {
            "asset-sync.outbox.retry-backoff-base-delay must be positive."
        }
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
}
