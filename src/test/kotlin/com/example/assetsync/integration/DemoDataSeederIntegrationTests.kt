package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.config.DemoDataSeeder
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
