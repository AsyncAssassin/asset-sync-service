package com.example.assetsync.unit

import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.application.outbox.OutboxEventRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.dao.DataAccessResourceFailureException

/**
 * The outbox gauges read counts a background job refreshes, so a scrape never queries the database:
 * with the database down, each scrape would otherwise wait for the connection pool, and Prometheus
 * would lose every metric, including the ones that show the outage.
 */
class AssetSyncMetricsOutboxGaugeTests {

    private val repository = Mockito.mock(OutboxEventRepository::class.java)
    private val registry = SimpleMeterRegistry()
    private val metrics = AssetSyncMetrics(registry, repository)

    @Test
    fun `a scrape reads the refreshed counts and never the database`() {
        assertTrue(backlog().isNaN(), "nothing is counted before the first refresh")

        Mockito.`when`(repository.countBacklog()).thenReturn(3)
        Mockito.`when`(repository.countDead()).thenReturn(1)
        metrics.refreshOutboxGauges()
        repeat(3) {
            assertEquals(3.0, backlog())
            assertEquals(1.0, registry.get("asset.sync.outbox.dead.total").gauge().value())
        }

        Mockito.verify(repository, Mockito.times(1)).countBacklog()
        Mockito.verify(repository, Mockito.times(1)).countDead()
    }

    @Test
    fun `while the database is down the gauges keep their last counts`() {
        Mockito.`when`(repository.countBacklog()).thenReturn(3)
        Mockito.`when`(repository.countDead()).thenReturn(1)
        metrics.refreshOutboxGauges()

        Mockito.`when`(repository.countBacklog()).thenThrow(DataAccessResourceFailureException("Connection refused"))
        metrics.refreshOutboxGauges()

        assertEquals(3.0, backlog())
    }

    private fun backlog(): Double = registry.get("asset.sync.outbox.backlog.total").gauge().value()
}
