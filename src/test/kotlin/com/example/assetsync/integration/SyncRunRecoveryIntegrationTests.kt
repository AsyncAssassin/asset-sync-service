package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.application.sync.SyncRunRepository
import com.example.assetsync.config.SyncProperties
import com.example.assetsync.infrastructure.sync.SyncRunWorkerJob
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        "asset-sync.sync.worker.max-attempts=3",
        "asset-sync.sync.worker.retry-backoff-base-delay=10s",
        "asset-sync.sync.worker.retry-backoff-max-delay=40s",
    ],
)
class SyncRunRecoveryIntegrationTests(
    @Autowired private val syncRunLifecycleService: SyncRunLifecycleService,
    @Autowired private val syncRunRepository: SyncRunRepository,
    @Autowired private val syncApplicationService: SyncApplicationService,
    @Autowired private val syncProperties: SyncProperties,
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

    @Test
    fun `expired running sync runs are requeued and clear lock fields`() {
        val running = insertRunningSyncRun(
            lockedUntil = Instant.now().minusSeconds(60),
            attempts = 1,
        )

        val recovered = syncRunLifecycleService.recoverExpiredRunning()

        assertEquals(1, recovered)
        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", running))
        assertNull(nullableTimestamp("SELECT finished_at FROM sync_runs WHERE id = ?", running))
        assertNull(nullableString("SELECT locked_by FROM sync_runs WHERE id = ?", running))
        assertNull(nullableString("SELECT lock_token::text FROM sync_runs WHERE id = ?", running))
        assertNotNull(nullableTimestamp("SELECT next_attempt_at FROM sync_runs WHERE id = ?", running))
    }

    @Test
    fun `expired running at max attempts becomes failed and terminal rows are untouched`() {
        val expiredAtMaxAttempts = insertRunningSyncRun(
            lockedUntil = Instant.now().minusSeconds(60),
            attempts = 3,
        )
        val succeeded = insertSucceededSyncRun(startedAt = Instant.now().minusSeconds(60))

        val recovered = syncRunLifecycleService.recoverExpiredRunning()

        assertEquals(1, recovered)
        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", expiredAtMaxAttempts))
        assertNotNull(nullableTimestamp("SELECT finished_at FROM sync_runs WHERE id = ?", expiredAtMaxAttempts))
        assertNull(nullableString("SELECT lock_token::text FROM sync_runs WHERE id = ?", expiredAtMaxAttempts))
        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", succeeded))
    }

    @Test
    fun `expired running recovery applies progressive jittered backoff per row`() {
        val firstAttempt = insertRunningSyncRun(
            lockedUntil = Instant.now().minusSeconds(60),
            attempts = 1,
        )
        val secondAttempt = insertRunningSyncRun(
            lockedUntil = Instant.now().minusSeconds(60),
            attempts = 2,
        )
        val maxAttempts = insertRunningSyncRun(
            lockedUntil = Instant.now().minusSeconds(60),
            attempts = 3,
        )
        val beforeRecovery = Instant.now()

        val recovered = syncRunLifecycleService.recoverExpiredRunning()

        assertEquals(3, recovered)
        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", firstAttempt))
        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", secondAttempt))
        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", maxAttempts))
        val firstNextAttempt = singleTimestamp("SELECT next_attempt_at FROM sync_runs WHERE id = ?", firstAttempt).toInstant()
        val secondNextAttempt = singleTimestamp("SELECT next_attempt_at FROM sync_runs WHERE id = ?", secondAttempt).toInstant()
        assertTrue(firstNextAttempt.isAfter(beforeRecovery))
        assertTrue(
            secondNextAttempt.isAfter(firstNextAttempt),
            "attempt=2 should receive a later progressive retry than attempt=1",
        )
        assertNotNull(nullableTimestamp("SELECT finished_at FROM sync_runs WHERE id = ?", maxAttempts))
    }

    @Test
    fun `two workers claim disjoint queued sync runs`() {
        val queuedIds = (1..4).map { insertQueuedSyncRun() }.toSet()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val workerA = CompletableFuture.supplyAsync(
                { claimAfterBarrier(workerId = "worker-a", ready = ready, start = start) },
                executor,
            )
            val workerB = CompletableFuture.supplyAsync(
                { claimAfterBarrier(workerId = "worker-b", ready = ready, start = start) },
                executor,
            )

            assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not reach claim barrier")
            start.countDown()

            val claimedByA = workerA.get(5, TimeUnit.SECONDS)
            val claimedByB = workerB.get(5, TimeUnit.SECONDS)
            val claimedIds = claimedByA + claimedByB

            assertEquals(2, claimedByA.size)
            assertEquals(2, claimedByB.size)
            assertEquals(4, claimedIds.toSet().size)
            assertEquals(queuedIds, claimedIds.toSet())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `stale completion cannot overwrite a re-claimed job`() {
        val runId = insertQueuedSyncRun()
        val claimA = syncRunLifecycleService.claimDueRuns(workerId = "worker-a", limit = 1).single()

        assertTrue(
            syncRunLifecycleService.requeue(
                claim = claimA,
                eventsSeen = 1,
                eventsChanged = 1,
                lastError = "transient provider failure",
            ),
        )
        jdbcTemplate.update(
            "UPDATE sync_runs SET next_attempt_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(1)),
            runId,
        )
        val claimB = syncRunLifecycleService.claimDueRuns(workerId = "worker-b", limit = 1).single()

        val staleCompletion = syncRunLifecycleService.markSucceeded(
            claim = claimA,
            eventsSeen = 99,
            eventsChanged = 99,
        )

        assertFalse(staleCompletion)
        assertEquals("RUNNING", singleString("SELECT status FROM sync_runs WHERE id = ?", runId))
        assertEquals("worker-b", singleString("SELECT locked_by FROM sync_runs WHERE id = ?", runId))
        assertEquals(claimB.lockToken.toString(), singleString("SELECT lock_token::text FROM sync_runs WHERE id = ?", runId))
        assertEquals(1, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", runId))
        assertEquals(1, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", runId))
    }

    @Test
    fun `completion after lease expiry but before recovery still succeeds`() {
        val runId = insertQueuedSyncRun()
        val claim = syncRunLifecycleService.claimDueRuns(workerId = "worker-a", limit = 1).single()
        jdbcTemplate.update(
            "UPDATE sync_runs SET locked_until = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(60)),
            runId,
        )

        val marked = syncRunLifecycleService.markSucceeded(
            claim = claim,
            eventsSeen = 1,
            eventsChanged = 1,
        )

        assertTrue(marked)
        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", runId))
        assertNull(nullableString("SELECT lock_token::text FROM sync_runs WHERE id = ?", runId))
        assertEquals(1, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", runId))
    }

    @Test
    fun `executor rejection after claim requeues the run`() {
        val runId = insertQueuedSyncRun()
        val workerJob = SyncRunWorkerJob(
            syncRunLifecycleService = syncRunLifecycleService,
            syncApplicationService = syncApplicationService,
            syncWorkerExecutor = rejectingExecutor(),
            syncWorkerPermitSemaphore = Semaphore(1),
            syncProperties = syncProperties,
            applicationName = "test-asset-sync",
        )

        val claimed = workerJob.claimAndSubmitAvailableRuns()

        assertEquals(1, claimed)
        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", runId))
        assertEquals(1, singleInt("SELECT attempts FROM sync_runs WHERE id = ?", runId))
        assertEquals("worker executor rejected claimed sync run", singleString("SELECT last_error FROM sync_runs WHERE id = ?", runId))
        assertNull(nullableString("SELECT lock_token::text FROM sync_runs WHERE id = ?", runId))
    }

    @Test
    fun `heartbeat extends locked_until for a claimed run`() {
        val runId = insertQueuedSyncRun()
        val claim = syncRunLifecycleService.claimDueRuns(workerId = "worker-a", limit = 1).single()
        val forcedExpiredLease = Instant.now().minus(Duration.ofMinutes(5))
        jdbcTemplate.update(
            "UPDATE sync_runs SET locked_until = ? WHERE id = ?",
            Timestamp.from(forcedExpiredLease),
            runId,
        )

        val heartbeat = syncRunLifecycleService.heartbeat(claim)

        assertTrue(heartbeat)
        assertTrue(
            singleTimestamp("SELECT locked_until FROM sync_runs WHERE id = ?", runId).toInstant()
                .isAfter(forcedExpiredLease),
        )
        assertNotNull(nullableTimestamp("SELECT heartbeat_at FROM sync_runs WHERE id = ?", runId))
    }

    private fun claimAfterBarrier(workerId: String, ready: CountDownLatch, start: CountDownLatch): List<UUID> {
        ready.countDown()
        assertTrue(start.await(5, TimeUnit.SECONDS), "claim barrier did not open")
        return syncRunLifecycleService.claimDueRuns(workerId = workerId, limit = 2).map { it.run.id }
    }

    private fun rejectingExecutor(): AbstractExecutorService =
        object : AbstractExecutorService() {
            override fun shutdown() = Unit

            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

            override fun isShutdown(): Boolean = false

            override fun isTerminated(): Boolean = false

            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true

            override fun execute(command: Runnable) {
                throw RejectedExecutionException("test rejection")
            }
        }

    private fun insertQueuedSyncRun(): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        jdbcTemplate.update(
            """
            INSERT INTO sync_runs (
                id,
                target_type,
                target_id,
                status,
                started_at,
                finished_at,
                events_seen,
                events_changed,
                queued_at,
                attempts,
                next_attempt_at,
                created_at,
                updated_at
            )
            VALUES (?, 'ADDRESS', ?, 'QUEUED', NULL, NULL, 0, 0, ?, 0, ?, ?, ?)
            """.trimIndent(),
            id,
            UUID.randomUUID(),
            Timestamp.from(now),
            Timestamp.from(now.minusSeconds(1)),
            Timestamp.from(now),
            Timestamp.from(now),
        )
        return id
    }

    private fun insertSucceededSyncRun(startedAt: Instant): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO sync_runs (
                id, target_type, target_id, status, started_at, finished_at,
                events_seen, events_changed, queued_at, attempts, next_attempt_at, created_at, updated_at
            )
            VALUES (?, 'ACCOUNT', ?, 'SUCCEEDED', ?, ?, 0, 0, ?, 0, ?, ?, ?)
            """.trimIndent(),
            id,
            UUID.randomUUID(),
            Timestamp.from(startedAt),
            Timestamp.from(startedAt),
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
                queued_at,
                attempts,
                next_attempt_at,
                created_at,
                updated_at
            )
            VALUES (?, 'ACCOUNT', ?, 'STARTED', ?, 0, 0, ?, 0, ?, ?, ?)
            """.trimIndent(),
            id,
            UUID.randomUUID(),
            Timestamp.from(startedAt),
            Timestamp.from(startedAt),
            Timestamp.from(startedAt),
            Timestamp.from(startedAt),
            Timestamp.from(startedAt),
        )
        return id
    }

    private fun insertRunningSyncRun(lockedUntil: Instant, attempts: Int): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
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
                queued_at,
                locked_by,
                lock_token,
                locked_until,
                heartbeat_at,
                attempts,
                next_attempt_at,
                created_at,
                updated_at
            )
            VALUES (?, 'ACCOUNT', ?, 'RUNNING', ?, 0, 0, ?, 'test-worker', ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            UUID.randomUUID(),
            Timestamp.from(now.minusSeconds(120)),
            Timestamp.from(now.minusSeconds(120)),
            UUID.randomUUID(),
            Timestamp.from(lockedUntil),
            Timestamp.from(lockedUntil.minusSeconds(30)),
            attempts,
            Timestamp.from(now.minusSeconds(120)),
            Timestamp.from(now.minusSeconds(120)),
            Timestamp.from(now.minusSeconds(120)),
        )
        return id
    }

    private fun singleString(sql: String, vararg args: Any): String =
        requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java, *args))

    private fun nullableTimestamp(sql: String, vararg args: Any): Timestamp? =
        jdbcTemplate.queryForObject(sql, Timestamp::class.java, *args)

    private fun singleTimestamp(sql: String, vararg args: Any): Timestamp =
        requireNotNull(jdbcTemplate.queryForObject(sql, Timestamp::class.java, *args))

    private fun singleInt(sql: String, vararg args: Any): Int =
        requireNotNull(jdbcTemplate.queryForObject(sql, Int::class.java, *args))

    private fun nullableString(sql: String, vararg args: Any): String? =
        jdbcTemplate.queryForObject(sql, String::class.java, *args)

    private fun cleanDatabase() {
        jdbcTemplate.update("DELETE FROM sync_runs")
    }
}
