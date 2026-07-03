package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.application.sync.SyncRunRepository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "asset-sync.sync.stale-run-timeout=30m",
        "asset-sync.sync.recovery.batch-size=10",
    ],
)
class SyncRunRecoveryIntegrationTests(
    @Autowired private val syncRunLifecycleService: SyncRunLifecycleService,
    @Autowired private val syncRunRepository: SyncRunRepository,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @BeforeEach
    fun cleanBeforeEach() {
        cleanDatabase()
    }

    @AfterEach
    fun cleanAfterEach() {
        cleanDatabase()
    }

    @Test
    fun `stale started sync runs are marked failed while fresh runs are untouched`() {
        val staleRun = insertStartedSyncRun(startedAt = Instant.now().minusSeconds(60 * 60))
        val freshRun = insertStartedSyncRun(startedAt = Instant.now().minusSeconds(60))

        val recovered = syncRunLifecycleService.markStaleStartedFailed()

        assertEquals(1, recovered)
        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", staleRun))
        assertNotNull(nullableTimestamp("SELECT finished_at FROM sync_runs WHERE id = ?", staleRun))
        assertEquals(
            "abandoned: no completion within PT30M",
            singleString("SELECT last_error FROM sync_runs WHERE id = ?", staleRun),
        )
        assertEquals("STARTED", singleString("SELECT status FROM sync_runs WHERE id = ?", freshRun))
        assertNull(nullableTimestamp("SELECT finished_at FROM sync_runs WHERE id = ?", freshRun))
    }

    @Test
    fun `markAbandoned fails a still-started run but never overwrites a completed one`() {
        val started = insertStartedSyncRun(startedAt = Instant.now().minusSeconds(60 * 60))
        val succeeded = insertSucceededSyncRun(startedAt = Instant.now().minusSeconds(60 * 60))
        val now = Instant.now()

        val abandonedStarted = syncRunRepository.markAbandoned(started, "abandoned", now, now)
        val abandonedSucceeded = syncRunRepository.markAbandoned(succeeded, "abandoned", now, now)

        assertNotNull(abandonedStarted)
        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", started))
        // Status guard: a SUCCEEDED run is not matched, so it is never clobbered.
        assertNull(abandonedSucceeded)
        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", succeeded))
    }

    private fun insertSucceededSyncRun(startedAt: Instant): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO sync_runs (
                id, target_type, target_id, status, started_at, finished_at,
                events_seen, events_changed, created_at, updated_at
            )
            VALUES (?, 'ACCOUNT', ?, 'SUCCEEDED', ?, ?, 0, 0, ?, ?)
            """.trimIndent(),
            id,
            UUID.randomUUID(),
            Timestamp.from(startedAt),
            Timestamp.from(startedAt),
            Timestamp.from(startedAt),
            Timestamp.from(startedAt),
        )
        return id
    }

    private fun insertStartedSyncRun(startedAt: Instant): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO sync_runs (
                id,
                target_type,
                target_id,
                status,
                started_at,
                events_seen,
                events_changed,
                created_at,
                updated_at
            )
            VALUES (?, 'ACCOUNT', ?, 'STARTED', ?, 0, 0, ?, ?)
            """.trimIndent(),
            id,
            UUID.randomUUID(),
            Timestamp.from(startedAt),
            Timestamp.from(startedAt),
            Timestamp.from(startedAt),
        )
        return id
    }

    private fun singleString(sql: String, vararg args: Any): String =
        requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java, *args))

    private fun nullableTimestamp(sql: String, vararg args: Any): Timestamp? =
        jdbcTemplate.queryForObject(sql, Timestamp::class.java, *args)

    private fun cleanDatabase() {
        jdbcTemplate.update("DELETE FROM sync_runs")
    }
}
