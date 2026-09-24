package com.example.assetsync.infrastructure.outbox

import com.example.assetsync.application.observability.AssetSyncMetrics
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Keeps `asset.sync.outbox.backlog.total` and `asset.sync.outbox.dead.total` current. The gauges read
 * the counts this job refreshes, so a Prometheus scrape never queries the database, and while the
 * database is down the gauges keep their last counts instead of making each scrape wait for the
 * connection pool.
 */
@Component
class OutboxGaugeRefreshJob(
    private val metrics: AssetSyncMetrics,
) {

    @Scheduled(initialDelay = 0, fixedDelay = REFRESH_INTERVAL_MILLIS)
    fun refresh() {
        metrics.refreshOutboxGauges()
    }

    companion object {
        const val REFRESH_INTERVAL_MILLIS = 10_000L
    }
}
