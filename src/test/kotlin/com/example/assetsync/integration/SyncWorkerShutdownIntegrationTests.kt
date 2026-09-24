package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.config.SyncProperties
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.example.assetsync.infrastructure.provider.FakeChainProviderStep
import com.example.assetsync.infrastructure.sync.SyncRunWorkerJob
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

/**
 * Graceful shutdown of the sync worker. The job under test is built by hand with its own executors
 * so that stop() never touches the shared context beans, while the provider fetch still runs on the
 * context's provider executor through SyncApplicationService, exactly as in production.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SyncWorkerShutdownIntegrationTests(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val fakeChainProvider: FakeChainProvider,
    @Autowired private val syncRunLifecycleService: SyncRunLifecycleService,
    @Autowired private val syncApplicationService: SyncApplicationService,
    @Autowired private val syncProperties: SyncProperties,
) {
    private val ownedExecutors = mutableListOf<ExecutorService>()

    @BeforeEach
    fun cleanBeforeEach() {
        cleanDatabase()
    }

    @AfterEach
    fun cleanAfterEach() {
        ownedExecutors.forEach { it.shutdownNow() }
        ownedExecutors.clear()
        cleanDatabase()
    }

    @Test
    fun `stop interrupts an in-flight run and requeues it without consuming the retry budget`() {
        val (watchedAddressId, address) = insertWatchedAddress()
        fakeChainProvider.setScript("local-evm", address, "USDC", listOf(FakeChainProviderStep.Delay(Duration.ofSeconds(8))))
        val runId = syncApplicationService.syncAddress(watchedAddressId).id
        val workerJob = workerJob(shutdownTimeout = Duration.ofSeconds(1))
        workerJob.start()

        assertEquals(1, workerJob.claimAndSubmitAvailableRuns())
        awaitUntil("provider fetch to start") { fakeChainProvider.requestedKeys().isNotEmpty() }

        val stopStartedAt = Instant.now()
        workerJob.stop()
        val stopDuration = Duration.between(stopStartedAt, Instant.now())

        assertFalse(workerJob.isRunning)
        assertTrue(stopDuration < Duration.ofSeconds(8), "stop must interrupt instead of waiting for the provider, took $stopDuration")
        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", runId))
        assertEquals(0, singleInt("SELECT failure_attempts FROM sync_runs WHERE id = ?", runId))
        assertEquals(1, singleInt("SELECT attempts FROM sync_runs WHERE id = ?", runId))
        assertEquals("FAILURE", singleString("SELECT last_requeue_reason FROM sync_runs WHERE id = ?", runId))
        assertTrue(singleString("SELECT last_error FROM sync_runs WHERE id = ?", runId).contains("interrupted"))
        // Due again at once: the next instance picks it up right after the deploy.
        assertEquals(
            true,
            jdbcTemplate.queryForObject("SELECT next_attempt_at <= now() + interval '2 seconds' FROM sync_runs WHERE id = ?", Boolean::class.java, runId),
        )
        assertNull(nullableString("SELECT locked_by FROM sync_runs WHERE id = ?", runId))
        assertNull(nullableString("SELECT locked_by FROM sync_cursors WHERE watched_address_id = ?", watchedAddressId))
    }

    @Test
    fun `stop waits for an in-flight run that completes within the shutdown timeout`() {
        val (watchedAddressId, address) = insertWatchedAddress()
        fakeChainProvider.setScript("local-evm", address, "USDC", listOf(FakeChainProviderStep.Delay(Duration.ofMillis(300))))
        val runId = syncApplicationService.syncAddress(watchedAddressId).id
        val workerJob = workerJob(shutdownTimeout = Duration.ofSeconds(10))
        workerJob.start()

        assertEquals(1, workerJob.claimAndSubmitAvailableRuns())
        awaitUntil("provider fetch to start") { fakeChainProvider.requestedKeys().isNotEmpty() }
        workerJob.stop()

        assertFalse(workerJob.isRunning)
        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", runId))
        assertEquals(0, singleInt("SELECT failure_attempts FROM sync_runs WHERE id = ?", runId))
        assertNull(nullableString("SELECT locked_by FROM sync_cursors WHERE watched_address_id = ?", watchedAddressId))
    }

    @Test
    fun `a stopped worker refuses new claims and leaves queued runs untouched`() {
        val (watchedAddressId, _) = insertWatchedAddress()
        val runId = syncApplicationService.syncAddress(watchedAddressId).id
        val workerJob = workerJob(shutdownTimeout = Duration.ofSeconds(1))
        workerJob.start()
        // The asynchronous form is what Spring's lifecycle processor calls; it must signal completion.
        val stopped = CountDownLatch(1)
        workerJob.stop { stopped.countDown() }
        assertTrue(stopped.await(10, TimeUnit.SECONDS), "stop(callback) must invoke the callback once draining is done")

        assertFalse(workerJob.isRunning)
        assertEquals(0, workerJob.claimAndSubmitAvailableRuns())
        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", runId))
        assertEquals(0, singleInt("SELECT attempts FROM sync_runs WHERE id = ?", runId))
    }

    private fun workerJob(shutdownTimeout: Duration): SyncRunWorkerJob {
        val workerExecutor = Executors.newSingleThreadExecutor().also(ownedExecutors::add)
        val providerExecutor = Executors.newSingleThreadExecutor().also(ownedExecutors::add)
        val properties = syncProperties.copy(worker = syncProperties.worker.copy(shutdownTimeout = shutdownTimeout))
        return SyncRunWorkerJob(
            syncRunLifecycleService = syncRunLifecycleService,
            syncApplicationService = syncApplicationService,
            syncWorkerExecutor = workerExecutor,
            syncProviderExecutor = providerExecutor,
            syncWorkerPermitSemaphore = Semaphore(1),
            syncProperties = properties,
            applicationName = "shutdown-test",
        )
    }

    private fun awaitUntil(what: String, timeout: Duration = Duration.ofSeconds(10), condition: () -> Boolean) {
        val deadline = Instant.now().plus(timeout)
        while (!condition()) {
            assertTrue(Instant.now().isBefore(deadline), "timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    private fun insertWatchedAddress(): Pair<UUID, String> {
        val accountId = UUID.randomUUID()
        val watchedAddressId = UUID.randomUUID()
        val address = "0xshutdown-${UUID.randomUUID()}"
        val now = Timestamp.from(Instant.now())
        jdbcTemplate.update(
            """
            INSERT INTO accounts (id, external_ref, status, created_at, updated_at)
            VALUES (?, ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            accountId,
            "shutdown-account-$accountId",
            now,
            now,
        )
        jdbcTemplate.update(
            """
            INSERT INTO watched_addresses (
                id, account_id, chain_id, address, asset, label, status, created_at, updated_at
            )
            VALUES (?, ?, 'local-evm', ?, 'USDC', NULL, 'ACTIVE', ?, ?)
            """.trimIndent(),
            watchedAddressId,
            accountId,
            address,
            now,
            now,
        )
        return watchedAddressId to address
    }

    private fun singleString(sql: String, vararg args: Any): String =
        requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java, *args))

    private fun singleInt(sql: String, vararg args: Any): Int =
        requireNotNull(jdbcTemplate.queryForObject(sql, Int::class.java, *args))

    private fun nullableString(sql: String, vararg args: Any): String? =
        jdbcTemplate.queryForObject(sql, String::class.java, *args)

    private fun cleanDatabase() {
        fakeChainProvider.clear()
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
    }
}
