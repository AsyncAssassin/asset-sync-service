package com.example.assetsync.integration

import com.example.assetsync.AlchemyJsonRpcStubServer
import com.example.assetsync.AlchemyRolloutDatabase
import com.example.assetsync.ScriptedAlchemyChain
import com.example.assetsync.ScriptedAlchemyChain.Transfer
import com.example.assetsync.application.account.AccountApplicationService
import com.example.assetsync.application.account.CreateAccountCommand
import com.example.assetsync.application.account.RegisterWatchedAddressCommand
import com.example.assetsync.application.account.UnsupportedChainException
import com.example.assetsync.application.account.WatchedAddressApplicationService
import com.example.assetsync.application.account.WatchedAddressStatus
import com.example.assetsync.application.sync.ClaimedSyncRun
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRun
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.application.sync.SyncRunRequeueReason
import com.example.assetsync.application.sync.SyncRunStatus
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

private const val INTEGRATION_API_KEY = "integration-secret-key-456"

/**
 * Drives the real sync worker against the Alchemy adapter and a scripted JSON-RPC stub, with
 * Testcontainers PostgreSQL behind the `e2e` profile switched to `asset-sync.provider.type=alchemy`
 * in path auth mode. It proves the end-to-end contract the unit tests cannot: whole pages are
 * ingested and confirmed through the real state machine and outbox, the durable cursor and
 * high-water advance only past drained blocks and never above the finality frontier, a provider
 * outage retries from the same cursor and spends `failure_attempts` while a long scan continues
 * through `continuation_count`, `Retry-After` is honored, and `sync_runs.last_error` never carries
 * the API key even when a transport failure embeds the path-mode URL.
 */
@ActiveProfiles("e2e")
@SpringBootTest(
    properties = [
        "asset-sync.provider.type=alchemy",
        "asset-sync.provider.alchemy.api-key=$INTEGRATION_API_KEY",
        "asset-sync.provider.alchemy.auth-mode=path",
        "asset-sync.provider.alchemy.max-window-blocks=50",
        "asset-sync.provider.read-timeout=2s",
        "asset-sync.sync.pagination.max-pages-per-address-run=2",
        "asset-sync.sync.worker.retry-backoff-base-delay=1s",
        "asset-sync.sync.worker.retry-backoff-max-delay=10m",
    ],
)
class AlchemyProviderSyncIntegrationTests(
    @Autowired private val accountApplicationService: AccountApplicationService,
    @Autowired private val watchedAddressApplicationService: WatchedAddressApplicationService,
    @Autowired private val syncApplicationService: SyncApplicationService,
    @Autowired private val syncRunLifecycleService: SyncRunLifecycleService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @BeforeEach
    fun resetChainAndDatabase() {
        stub.reset()
        stub.responder = chain.responder()
        chain.transfers.clear()
        chain.transfersCalls.clear()
        chain.latest = 1000
        chain.finality["safe"] = 900
        chain.pageSize = 1000
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
    }

    @Test
    fun `registration-safe starts above the frontier, then ingests and confirms events without emitting blocks above safe`() {
        chain.transfers += Transfer(block = 850, logIndex = 1, from = OTHER, to = WATCHED)
        val addressId = registerWatchedAddress()

        // First sync: idle at safe + 1, no backfill of the transfer at block 850.
        val idle = runToCompletion(enqueue(addressId))
        assertEquals(SyncRunStatus.SUCCEEDED, idle.status)
        assertEquals(0, idle.eventsSeen)
        assertEquals(0, transactions().size)
        assertEquals(cursor(901), cursorRow(addressId).providerCursor)
        assertEquals(900L, cursorRow(addressId).lastFinalizedBlockHeight)
        assertNull(cursorRow(addressId).lastProcessedBlockHeight)
        assertTrue(cursorRow(addressId).checkpoint.contains("\"initialStartBlock\": 901"), cursorRow(addressId).checkpoint)
        assertEquals(0, chain.transfersCalls.size, "an idle page needs no transfers call")

        // The chain moves on: two transfers below the new frontier, one above it.
        chain.latest = 1100
        chain.finality["safe"] = 1000
        chain.transfers += Transfer(block = 950, logIndex = 3, from = OTHER, to = WATCHED, rawValue = "0x12d687")
        chain.transfers += Transfer(block = 960, logIndex = 1, from = WATCHED, to = OTHER, rawValue = "0x3d0900")
        chain.transfers += Transfer(block = 1050, logIndex = 2, from = OTHER, to = WATCHED)

        val ingested = runToCompletion(enqueue(addressId))
        assertEquals(SyncRunStatus.SUCCEEDED, ingested.status)
        assertEquals(2, ingested.eventsSeen)
        assertEquals(2, ingested.eventsChanged)
        assertEquals(0, ingested.failureAttempts)
        assertEquals(listOf(901L to 950L, 951L to 1000L), chain.transfersCalls.filter { it.direction == "in" }.map { it.fromBlock to it.toBlock })
        assertTrue(chain.transfersCalls.all { it.toBlock <= 1000L }, "no request may look above the safe frontier")
        val rows = transactions()
        assertEquals(listOf(950L to 3, 960L to 1), rows.map { it.blockHeight to it.eventIndex })
        assertEquals(listOf("INBOUND", "OUTBOUND"), rows.map { it.direction })
        assertEquals(listOf("CONFIRMED", "CONFIRMED"), rows.map { it.status }, "eth-sepolia requires one confirmation")
        assertEquals(0, BigDecimal("1.234567").compareTo(rows[0].amount))
        assertEquals(0, BigDecimal("4").compareTo(rows[1].amount))
        assertEquals(listOf(151, 141), rows.map { it.confirmations })
        assertEquals(WATCHED.lowercase(), rows[0].address)
        assertEquals(listOf("TRANSACTION_CONFIRMED", "TRANSACTION_CONFIRMED"), outboxEventTypes())
        val advanced = cursorRow(addressId)
        assertEquals(cursor(1001), advanced.providerCursor)
        assertEquals(960L, advanced.lastProcessedBlockHeight)
        assertEquals(1, advanced.lastProcessedEventIndex)
        assertEquals(1000L, advanced.lastFinalizedBlockHeight)
        assertTrue(advanced.checkpoint.contains("\"provider\": \"alchemy\""), advanced.checkpoint)
        assertFalse(advanced.checkpoint.contains(INTEGRATION_API_KEY), advanced.checkpoint)

        // Once the frontier passes block 1050 the remaining transfer is picked up from the cursor.
        chain.latest = 1200
        chain.finality["safe"] = 1100
        val caughtUp = runToCompletion(enqueue(addressId))
        assertEquals(SyncRunStatus.SUCCEEDED, caughtUp.status)
        assertEquals(1, caughtUp.eventsSeen)
        assertEquals(listOf(950L, 960L, 1050L), transactions().map { it.blockHeight })
        assertEquals(cursor(1101), cursorRow(addressId).providerCursor)
        assertEquals(1050L, cursorRow(addressId).lastProcessedBlockHeight)
    }

    @Test
    fun `a provider outage retries from the durable cursor, spends failure attempts, and never stores the key`() {
        val addressId = registerWatchedAddress()
        runToCompletion(enqueue(addressId))
        chain.latest = 1100
        chain.finality["safe"] = 1000
        chain.transfers += Transfer(block = 950, logIndex = 3, from = OTHER, to = WATCHED)
        val runId = enqueue(addressId)

        // 1) HTTP 500 from the provider: requeued, one failure attempt, cursor untouched.
        stub.responder = null
        stub.responseStatus = 500
        assertEquals(1, runWorkerOnce().size)
        val afterOutage = syncRunLifecycleService.get(runId)
        assertEquals(SyncRunStatus.QUEUED, afterOutage.status)
        assertEquals(1, afterOutage.failureAttempts)
        assertEquals(0, afterOutage.continuationCount)
        assertEquals(SyncRunRequeueReason.FAILURE, afterOutage.lastRequeueReason)
        assertTrue(afterOutage.lastError!!.contains("HTTP 500"), afterOutage.lastError)
        assertEquals(cursor(901), cursorRow(addressId).providerCursor)
        assertEquals(0, transactions().size)

        // 2) A transport failure (the transfers response arrives after the 2s read timeout): path
        //    auth mode puts the key into the request URL that RestClient embeds in its message, and
        //    last_error must carry only the scrubbed form.
        stub.responder = { request ->
            val response = chain.responder()(request)
            if (request.method == "alchemy_getAssetTransfers") response.copy(delay = Duration.ofSeconds(3)) else response
        }
        makeDue(runId)
        assertEquals(1, runWorkerOnce().size)
        val afterTransportFailure = syncRunLifecycleService.get(runId)
        assertEquals(SyncRunStatus.QUEUED, afterTransportFailure.status)
        assertEquals(2, afterTransportFailure.failureAttempts)
        val lastError = requireNotNull(afterTransportFailure.lastError)
        assertTrue(lastError.startsWith("Alchemy request failed for network eth-sepolia"), lastError)
        assertTrue(lastError.contains("***"), "the request url must be scrubbed: $lastError")
        assertFalse(lastError.contains(INTEGRATION_API_KEY), lastError)
        assertEquals(cursor(901), cursorRow(addressId).providerCursor)

        // 3) The provider recovers: the same run resumes from the same cursor and succeeds.
        stub.responder = chain.responder()
        makeDue(runId)
        assertEquals(1, runWorkerOnce().size)
        val recovered = syncRunLifecycleService.get(runId)
        assertEquals(SyncRunStatus.SUCCEEDED, recovered.status)
        assertEquals(2, recovered.failureAttempts, "the budget spent on the outage stays on the run")
        assertEquals(1, recovered.eventsSeen)
        assertEquals(listOf(950L), transactions().map { it.blockHeight })
        assertEquals(cursor(1001), cursorRow(addressId).providerCursor)
        assertFalse(jdbcTemplate.queryForList("SELECT last_error FROM sync_runs").toString().contains(INTEGRATION_API_KEY))
    }

    @Test
    fun `a provider 429 with Retry-After schedules the retry accordingly and consumes one failure attempt`() {
        val addressId = registerWatchedAddress()
        runToCompletion(enqueue(addressId))
        chain.latest = 1100
        chain.finality["safe"] = 1000
        chain.transfers += Transfer(block = 950, logIndex = 3, from = OTHER, to = WATCHED)
        stub.responder = { request ->
            if (request.method == "alchemy_getAssetTransfers") {
                AlchemyJsonRpcStubServer.StubResponse(body = """{"error":"throttled"}""", status = 429, retryAfter = "120")
            } else {
                chain.responder()(request)
            }
        }
        val runId = enqueue(addressId)
        val before = Instant.now()

        assertEquals(1, runWorkerOnce().size)

        val throttled = syncRunLifecycleService.get(runId)
        assertEquals(SyncRunStatus.QUEUED, throttled.status)
        assertEquals(1, throttled.failureAttempts)
        assertEquals(SyncRunRequeueReason.FAILURE, throttled.lastRequeueReason)
        assertTrue(throttled.lastError!!.contains("HTTP 429"), throttled.lastError)
        val delay = Duration.between(before, throttled.nextAttemptAt)
        assertTrue(delay >= Duration.ofSeconds(110) && delay <= Duration.ofSeconds(125), "Retry-After 120s must be honored, got $delay")
        assertEquals(cursor(901), cursorRow(addressId).providerCursor)
        assertEquals(0, transactions().size)
    }

    @Test
    fun `a long scan continues across claims through continuation_count without spending failure attempts`() {
        val addressId = registerWatchedAddress()
        runToCompletion(enqueue(addressId))
        chain.latest = 1400
        chain.finality["safe"] = 1300
        chain.transfers += Transfer(block = 920, logIndex = 1, from = OTHER, to = WATCHED)
        chain.transfers += Transfer(block = 1120, logIndex = 2, from = OTHER, to = WATCHED)
        chain.transfers += Transfer(block = 1290, logIndex = 3, from = WATCHED, to = OTHER)
        val runId = enqueue(addressId)

        // Two pages of 50 blocks per claim (max-pages-per-address-run=2): 901..1000 first.
        assertEquals(1, runWorkerOnce().size)
        val continued = syncRunLifecycleService.get(runId)
        assertEquals(SyncRunStatus.QUEUED, continued.status)
        assertEquals(1, continued.continuationCount)
        assertEquals(0, continued.failureAttempts)
        assertEquals(SyncRunRequeueReason.CONTINUATION, continued.lastRequeueReason)
        assertEquals(cursor(1001), cursorRow(addressId).providerCursor)
        assertEquals(listOf(920L), transactions().map { it.blockHeight })

        var claims = 1
        while (syncRunLifecycleService.get(runId).status == SyncRunStatus.QUEUED) {
            makeDue(runId)
            assertEquals(1, runWorkerOnce().size, "the continuation must be claimable once due")
            claims += 1
            assertTrue(claims <= 10, "the scan must finish within a bounded number of claims")
        }

        val finished = syncRunLifecycleService.get(runId)
        assertEquals(SyncRunStatus.SUCCEEDED, finished.status)
        assertEquals(4, claims, "400 blocks at 100 per claim")
        assertEquals(3, finished.continuationCount)
        assertEquals(0, finished.failureAttempts)
        assertEquals(3, finished.eventsSeen)
        assertEquals(listOf(920L, 1120L, 1290L), transactions().map { it.blockHeight })
        assertEquals(cursor(1301), cursorRow(addressId).providerCursor)
        assertEquals(1290L, cursorRow(addressId).lastProcessedBlockHeight)
        assertEquals(1300L, cursorRow(addressId).lastFinalizedBlockHeight)
        assertTrue(chain.transfersCalls.all { it.toBlock - it.fromBlock + 1 <= 50 }, "every range respects max-window-blocks")
    }

    private data class TransactionRow(
        val blockHeight: Long,
        val eventIndex: Int,
        val direction: String,
        val amount: BigDecimal,
        val status: String,
        val confirmations: Int,
        val address: String,
    )

    private data class CursorRow(
        val providerCursor: String?,
        val checkpoint: String,
        val lastProcessedBlockHeight: Long?,
        val lastProcessedEventIndex: Int?,
        val lastFinalizedBlockHeight: Long?,
    )

    @Test
    fun `an address on a chain without an alchemy network is refused at registration and at re-enabling`() {
        val account = accountApplicationService.createAccount(CreateAccountCommand(externalRef = "alchemy-it-${UUID.randomUUID()}"))

        // The seeded local-evm chain and its USDC row are enabled, but no Alchemy network serves them.
        assertThrows<UnsupportedChainException> {
            watchedAddressApplicationService.registerWatchedAddress(
                RegisterWatchedAddressCommand(accountId = account.id, chainId = "local-evm", address = "0xlocal", asset = "USDC", label = null),
            )
        }
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM watched_addresses", Int::class.java))

        // One registered under the HTTP bridge before the switch and disabled since cannot come back:
        // it would fail every sync and stop the next start.
        val legacyId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO watched_addresses (id, account_id, chain_id, address, asset, label, status, created_at, updated_at)
            VALUES (?, ?, 'local-evm', '0xlegacy-local', 'USDC', NULL, 'DISABLED', now(), now())
            """.trimIndent(),
            legacyId,
            account.id,
        )
        assertThrows<UnsupportedChainException> { watchedAddressApplicationService.updateStatus(legacyId, WatchedAddressStatus.ACTIVE) }
        assertEquals("DISABLED", jdbcTemplate.queryForObject("SELECT status FROM watched_addresses WHERE id = ?", String::class.java, legacyId))
    }

    private fun registerWatchedAddress(): UUID {
        val account = accountApplicationService.createAccount(CreateAccountCommand(externalRef = "alchemy-it-${UUID.randomUUID()}"))
        return watchedAddressApplicationService.registerWatchedAddress(
            RegisterWatchedAddressCommand(
                accountId = account.id,
                chainId = "eth-sepolia",
                address = WATCHED,
                asset = "usdc",
                label = "alchemy-it",
            ),
        ).id
    }

    private fun enqueue(addressId: UUID): UUID = syncApplicationService.syncAddress(addressId).id

    private fun runWorkerOnce(): List<ClaimedSyncRun> {
        val claims = syncRunLifecycleService.claimDueRuns(workerId = "alchemy-it-worker-${UUID.randomUUID()}", limit = 10)
        claims.forEach { syncApplicationService.executeClaimedSyncRun(it) }
        return claims
    }

    private fun runToCompletion(runId: UUID): SyncRun {
        repeat(10) {
            makeDue(runId)
            runWorkerOnce()
            val run = syncRunLifecycleService.get(runId)
            if (run.status == SyncRunStatus.SUCCEEDED || run.status == SyncRunStatus.FAILED) {
                return run
            }
        }
        error("sync run $runId did not finish: ${syncRunLifecycleService.get(runId)}")
    }

    private fun makeDue(runId: UUID) {
        jdbcTemplate.update("UPDATE sync_runs SET next_attempt_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(1)), runId)
    }

    private fun cursor(nextBlock: Long): String = """{"v":1,"p":"alchemy","nextBlock":$nextBlock}"""

    private fun cursorRow(addressId: UUID): CursorRow =
        jdbcTemplate.queryForObject(
            """
            SELECT provider_cursor, checkpoint::text AS checkpoint, last_processed_block_height, last_processed_event_index, last_finalized_block_height
            FROM sync_cursors WHERE watched_address_id = ?
            """.trimIndent(),
            { rs, _ ->
                CursorRow(
                    providerCursor = rs.getString("provider_cursor"),
                    checkpoint = rs.getString("checkpoint"),
                    lastProcessedBlockHeight = rs.getObject("last_processed_block_height")?.let { (it as Number).toLong() },
                    lastProcessedEventIndex = rs.getObject("last_processed_event_index")?.let { (it as Number).toInt() },
                    lastFinalizedBlockHeight = rs.getObject("last_finalized_block_height")?.let { (it as Number).toLong() },
                )
            },
            addressId,
        )!!

    private fun transactions(): List<TransactionRow> =
        jdbcTemplate.query(
            "SELECT block_height, event_index, direction, amount, status, confirmations, address FROM observed_transactions ORDER BY block_height, event_index",
        ) { rs, _ ->
            TransactionRow(
                blockHeight = rs.getLong("block_height"),
                eventIndex = rs.getInt("event_index"),
                direction = rs.getString("direction"),
                amount = rs.getBigDecimal("amount"),
                status = rs.getString("status"),
                confirmations = rs.getInt("confirmations"),
                address = rs.getString("address"),
            )
        }

    private fun outboxEventTypes(): List<String> =
        jdbcTemplate.queryForList("SELECT event_type FROM outbox_events ORDER BY created_at", String::class.java)

    companion object {
        private const val WATCHED = "0xAbC0000000000000000000000000000000000003"
        private const val OTHER = "0x2222222222222222222222222222222222222222"
        private const val USDC_SEPOLIA = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("asset_sync_alchemy_sync")
                .withUsername("asset_sync")
                .withPassword("asset_sync")
                .also { it.start() }

        private val stub = AlchemyJsonRpcStubServer()
        private val chain = ScriptedAlchemyChain(watchedAddress = WATCHED, contractAddress = USDC_SEPOLIA)

        init {
            AlchemyRolloutDatabase.prepare(postgres.jdbcUrl, postgres.username, postgres.password)
            stub.responder = chain.responder()
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("asset-sync.provider.alchemy.path-endpoint-template") { stub.pathEndpointTemplate() }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            stub.close()
            postgres.stop()
        }
    }
}
