package com.example.assetsync.infrastructure.outbox

import com.example.assetsync.application.observability.AssetSyncMetrics
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Keeps `asset.sync.outbox.backlog.total` and `asset.sync.outbox.dead.total` current. The gauges read
 * the counts this job refreshes, so a Prometheus scrape never queries the database, and while the
 * database is down the gauges keep their last counts instead of making each scrape wait for the
 * connection pool. Like the other database jobs it can be switched off, as the tests do, which then
 * refresh the gauges themselves.
 */
@Component
@ConditionalOnProperty(
    prefix = "asset-sync.outbox.gauges",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class OutboxGaugeRefreshJob(
    private val metrics: AssetSyncMetrics,
) {

    @Scheduled(initialDelay = 0, fixedDelayString = "\${asset-sync.outbox.gauges.fixed-delay:10s}")
    fun refresh() {
        metrics.refreshOutboxGauges()
    }
}
