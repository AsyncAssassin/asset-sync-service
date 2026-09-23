package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.config.DemoDataSeeder
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.model.outboxIdempotencyKey
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.test.context.ActiveProfiles

/**
 * Verifies the demo profile boots with the real provider + auth wiring and that DemoDataSeeder
 * populates every lifecycle stage idempotently (re-running it does not multiply rows). Schedulers
 * are disabled here so the seeded NEW/STARTED rows stay put for deterministic assertions.
 */
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "asset-sync.provider.base-url=http://localhost:1",
        "asset-sync.outbox.scheduler.enabled=false",
        "asset-sync.sync.recovery.enabled=false",
        "asset-sync.sync.worker.enabled=false",
    ],
)
class DemoDataSeederIntegrationTests(
    @Autowired private val demoDataSeeder: DemoDataSeeder,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val userDetailsManager: UserDetailsManager,
    @Autowired private val objectMapper: ObjectMapper,
) {

    @BeforeEach
    fun cleanBeforeEach() {
        cleanDomain()
    }

    @AfterEach
    fun cleanAfterEach() {
        cleanDomain()
    }

    @Test
    fun `seeds every lifecycle stage and is idempotent`() {
        demoDataSeeder.run(null)
        assertSeededCounts()
        assertTrue(userDetailsManager.userExists("demo-reader"))
        assertTrue(userDetailsManager.userExists("demo-operator"))
        assertEquals(
            setOf("SEEN", "CONFIRMED", "REVERTED"),
            jdbcTemplate.queryForList("SELECT status FROM observed_transactions", String::class.java).toSet(),
        )
        assertEquals(
            setOf("NEW", "PUBLISHED", "FAILED", "DEAD"),
            jdbcTemplate.queryForList("SELECT status FROM outbox_events", String::class.java).toSet(),
        )
        assertEquals("STARTED", singleString("SELECT status FROM sync_runs"))

        // Re-running the seeder must not multiply rows.
        demoDataSeeder.run(null)
        assertSeededCounts()
    }

    @Test
    fun `seeded outbox rows are complete lifecycle events of their transactions`() {
        demoDataSeeder.run(null)

        // Outbox id -> the event ingestion would have written: status, confirmations, and version of the
        // transaction at that event. The reverted transaction carries two: its first sighting went DEAD,
        // its reversal retries.
        val expected = mapOf(
            "d0000000-0000-0000-0000-0000000000e1" to SeededEvent("NEW", "d0000000-0000-0000-0000-0000000000c1", "SEEN", 1, 0),
            "d0000000-0000-0000-0000-0000000000e2" to SeededEvent("PUBLISHED", "d0000000-0000-0000-0000-0000000000c2", "CONFIRMED", 6, 0),
            "d0000000-0000-0000-0000-0000000000e4" to SeededEvent("DEAD", "d0000000-0000-0000-0000-0000000000c3", "SEEN", 1, 0),
            "d0000000-0000-0000-0000-0000000000e3" to SeededEvent("FAILED", "d0000000-0000-0000-0000-0000000000c3", "REVERTED", 2, 1),
        )
        val rows = jdbcTemplate.queryForList(
            """
            SELECT o.id, o.status AS outbox_status, o.aggregate_id, o.event_type, o.idempotency_key,
                   o.payload::text AS payload, t.chain_id, t.tx_hash, t.address, t.asset
            FROM outbox_events o
            JOIN observed_transactions t ON t.id = o.aggregate_id
            """.trimIndent(),
        )

        assertEquals(expected.keys, rows.map { it["id"].toString() }.toSet())
        rows.forEach { row ->
            val outboxId = row["id"].toString()
            val event = expected.getValue(outboxId)
            val payload = objectMapper.readTree(row["payload"] as String)
            assertEquals(event.outboxStatus, row["outbox_status"], outboxId)
            assertEquals(event.transactionId, row["aggregate_id"].toString(), outboxId)
            assertEquals("TRANSACTION_${event.status}", row["event_type"], outboxId)
            assertEquals(
                outboxIdempotencyKey(UUID.fromString(event.transactionId), TransactionStatus.valueOf(event.status), event.version),
                row["idempotency_key"],
                outboxId,
            )
            assertEquals(outboxId, payload["eventId"].asText())
            assertEquals("TRANSACTION_${event.status}", payload["eventType"].asText())
            assertEquals(event.transactionId, payload["transactionId"].asText())
            assertEquals(row["chain_id"], payload["chainId"].asText())
            assertEquals(row["tx_hash"], payload["txHash"].asText())
            assertEquals(0, payload["eventIndex"].asInt())
            assertEquals(row["address"], payload["address"].asText())
            assertEquals(row["asset"], payload["asset"].asText())
            assertEquals("1.000000000000000000", payload["amount"].asText())
            assertEquals("INBOUND", payload["direction"].asText())
            assertEquals(event.status, payload["status"].asText())
            assertEquals(event.confirmations, payload["confirmations"].asInt())
            assertEquals(1_000L, payload["blockHeight"].asLong())
            assertEquals("demo:seed", payload["source"].asText())
        }
        assertEquals(
            mapOf(
                "d0000000-0000-0000-0000-0000000000c1" to 0L,
                "d0000000-0000-0000-0000-0000000000c2" to 0L,
                "d0000000-0000-0000-0000-0000000000c3" to 1L,
            ),
            jdbcTemplate.queryForList("SELECT id, version FROM observed_transactions")
                .associate { it["id"].toString() to (it["version"] as Number).toLong() },
        )
        assertEquals(
            listOf("demo:seed"),
            jdbcTemplate.queryForList("SELECT DISTINCT source FROM observed_transactions", String::class.java),
        )
    }

    @Test
    fun `a restart does not bring back seeded events that are gone`() {
        demoDataSeeder.run(null)
        // Published events removed later, for example by outbox retention.
        jdbcTemplate.update("DELETE FROM outbox_events")

        demoDataSeeder.run(null)

        assertEquals(0, count("outbox_events"))
        assertEquals(3, count("observed_transactions"))
    }

    private data class SeededEvent(
        val outboxStatus: String,
        val transactionId: String,
        val status: String,
        val confirmations: Int,
        val version: Long,
    )

    private fun assertSeededCounts() {
        assertEquals(1, count("accounts"))
        assertEquals(1, count("watched_addresses"))
        assertEquals(3, count("observed_transactions"))
        assertEquals(4, count("outbox_events"))
        assertEquals(1, count("sync_runs"))
    }

    private fun count(table: String): Int =
        requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun singleString(sql: String): String =
        requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java))

    private fun cleanDomain() {
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
    }
}
