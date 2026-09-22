package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.application.sync.SyncTargetType
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.example.assetsync.infrastructure.provider.FakeChainProviderStep
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
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
        "asset-sync.sync.provider-timeout=1500ms",
        "asset-sync.sync.provider-max-threads=2",
        "asset-sync.sync.worker.max-concurrency=2",
        "asset-sync.sync.worker.heartbeat-interval=250ms",
        "asset-sync.sync.pagination.cursor-lease-duration=2s",
        "asset-sync.sync.pagination.cursor-heartbeat-interval=100ms",
    ],
)
class SyncCursorHeartbeatIntegrationTests(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val fakeChainProvider: FakeChainProvider,
    @Autowired private val syncRunLifecycleService: SyncRunLifecycleService,
    @Autowired private val syncApplicationService: SyncApplicationService,
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
    fun `cursor lease heartbeat extends lease during long provider fetch and checkpoint advances`() {
        val accountId = insertAccount()
        val watchedAddressId = insertWatchedAddress(accountId = accountId, address = "0xsync-heartbeat")
        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-heartbeat",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Delay(Duration.ofMillis(900)),
                FakeChainProviderStep.Event(providerEvent(txHash = "0xsync-heartbeat-1", address = "0xsync-heartbeat")),
            ),
        )

        val syncRun = syncApplicationService.syncAddress(watchedAddressId)
        val claim = syncRunLifecycleService.claimDueRuns(workerId = "heartbeat-worker", limit = 1).single()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val future = CompletableFuture.runAsync(
                { syncApplicationService.executeClaimedSyncRun(claim) },
                executor,
            )
            waitUntil("provider fetch starts") { fakeChainProvider.requestedPageRequests().isNotEmpty() }
            val firstLockedUntil = waitForLockedUntil(watchedAddressId)

            Thread.sleep(350)

            val secondLockedUntil = waitForLockedUntil(watchedAddressId)
            assertTrue(
                secondLockedUntil.toInstant().isAfter(firstLockedUntil.toInstant()),
                "cursor heartbeat should extend locked_until while provider fetch is still running",
            )

            future.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRun.id))
        assertEquals("2", singleString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", watchedAddressId))
        assertNull(nullableTimestamp("SELECT locked_until FROM sync_cursors WHERE watched_address_id = ?", watchedAddressId))
        assertEquals(1, singleInt("SELECT count(*) FROM observed_transactions"))
    }

    @Test
    fun `account sync requeues when direct address sync holds the same cursor lease`() {
        val accountId = insertAccount()
        val watchedAddressId = insertWatchedAddress(accountId = accountId, address = "0xsync-contended")
        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-contended",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Delay(Duration.ofMillis(700)),
                FakeChainProviderStep.Event(providerEvent(txHash = "0xsync-contended-1", address = "0xsync-contended")),
            ),
        )

        val addressRun = syncApplicationService.syncAddress(watchedAddressId)
        val accountRun = syncApplicationService.syncAccount(accountId)
        val claims = syncRunLifecycleService.claimDueRuns(workerId = "contention-worker", limit = 2)
        val addressClaim = claims.single { it.run.targetType == SyncTargetType.ADDRESS }
        val accountClaim = claims.single { it.run.targetType == SyncTargetType.ACCOUNT }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val addressFuture = CompletableFuture.runAsync(
                { syncApplicationService.executeClaimedSyncRun(addressClaim) },
                executor,
            )
            waitUntil("direct address cursor lease") {
                fakeChainProvider.requestedPageRequests().isNotEmpty() &&
                    nullableString("SELECT locked_by FROM sync_cursors WHERE watched_address_id = ?", watchedAddressId) != null
            }

            syncApplicationService.executeClaimedSyncRun(accountClaim)

            assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", accountRun.id))
            assertEquals("LEASE_BUSY", singleString("SELECT last_requeue_reason FROM sync_runs WHERE id = ?", accountRun.id))
            assertEquals(1, singleInt("SELECT continuation_count FROM sync_runs WHERE id = ?", accountRun.id))

            addressFuture.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", addressRun.id))
        assertEquals(1, fakeChainProvider.requestedPageRequests().size)
        assertEquals("2", singleString("SELECT provider_cursor FROM sync_cursors WHERE watched_address_id = ?", watchedAddressId))
        assertEquals(1, singleInt("SELECT count(*) FROM observed_transactions"))
    }

    private fun insertAccount(): UUID {
        val accountId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        jdbcTemplate.update(
            """
            INSERT INTO accounts (id, external_ref, status, created_at, updated_at)
            VALUES (?, ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            accountId,
            "heartbeat-account-$accountId",
            now,
            now,
        )
        return accountId
    }

    private fun insertWatchedAddress(accountId: UUID, address: String): UUID {
        val watchedAddressId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
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
        return watchedAddressId
    }

    private fun providerEvent(
        txHash: String,
        address: String,
        eventIndex: Int = 0,
        blockHeight: Long = 100,
    ): ChainProviderObservedEvent =
        ChainProviderObservedEvent(
            chainId = "local-evm",
            txHash = txHash,
            eventIndex = eventIndex,
            address = address,
            asset = "USDC",
            amount = BigDecimal("12.340000000000000000"),
            blockHeight = blockHeight,
            confirmations = 1,
            direction = Direction.INBOUND,
            status = TransactionStatus.SEEN,
        )

    private fun waitForLockedUntil(watchedAddressId: UUID): Timestamp =
        waitForValue("cursor locked_until") {
            nullableTimestamp("SELECT locked_until FROM sync_cursors WHERE watched_address_id = ?", watchedAddressId)
        }

    private fun waitUntil(description: String, timeout: Duration = Duration.ofSeconds(5), condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(25)
        }
        error("Timed out waiting for $description.")
    }

    private fun <T : Any> waitForValue(description: String, timeout: Duration = Duration.ofSeconds(5), value: () -> T?): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            value()?.let { return it }
            Thread.sleep(25)
        }
        error("Timed out waiting for $description.")
    }

    private fun singleString(sql: String, vararg args: Any): String =
        requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java, *args))

    private fun singleInt(sql: String, vararg args: Any): Int =
        requireNotNull(jdbcTemplate.queryForObject(sql, Int::class.java, *args))

    private fun nullableString(sql: String, vararg args: Any): String? =
        jdbcTemplate.queryForObject(sql, String::class.java, *args)

    private fun nullableTimestamp(sql: String, vararg args: Any): Timestamp? =
        jdbcTemplate.queryForObject(sql, Timestamp::class.java, *args)

    private fun cleanDatabase() {
        fakeChainProvider.clear()
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
        jdbcTemplate.update("DELETE FROM chain_configs WHERE chain_id NOT IN ('local-evm', 'eth-sepolia', 'eth-mainnet')")
        jdbcTemplate.update(
            """
            UPDATE chain_configs
            SET enabled = true,
                required_confirmations = 3,
                updated_at = ?
            WHERE chain_id = 'local-evm'
            """.trimIndent(),
            Timestamp.from(Instant.now()),
        )
    }
}
