package com.example.assetsync.e2e

import com.example.assetsync.AlchemyRolloutDatabase
import com.example.assetsync.AssetSyncServiceApplication
import com.example.assetsync.application.account.AccountApplicationService
import com.example.assetsync.application.account.CreateAccountCommand
import com.example.assetsync.application.account.RegisterWatchedAddressCommand
import com.example.assetsync.application.account.WatchedAddressApplicationService
import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRun
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.application.sync.SyncRunStatus
import com.example.assetsync.config.AlchemyProviderProperties
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Manual, env-gated smoke against a real Alchemy endpoint. Normal CI skips it; it runs only with
 * `ALCHEMY_LIVE_SMOKE=true` and a key in `ASSET_SYNC_PROVIDER_ALCHEMY_API_KEY`. See
 * `docs/alchemy-runbook.md` for the full procedure. Required and optional environment:
 *
 * - `ALCHEMY_LIVE_SMOKE_ADDRESS`: the watched address (an address with Sepolia USDC history).
 * - `ALCHEMY_LIVE_SMOKE_CHAIN_ID` (default `eth-sepolia`), `ALCHEMY_LIVE_SMOKE_ASSET` (default `USDC`).
 * - `ALCHEMY_LIVE_SMOKE_FROM_BLOCK`: when set, the scan starts there (`configured-block`), so a
 *   known historical range proves real events are ingested; without it the default
 *   `registration-safe` start makes the first sync idle by design.
 * - `ASSET_SYNC_PROVIDER_ALCHEMY_ENDPOINT_TEMPLATE`: only for dry runs against a local stub.
 *
 * The smoke proves the plan's checklist: the run succeeds and never stores the key, the cursor is
 * the adapter's own JSON with no `pageKey`, the finalized height is populated, every emitted row
 * is the registry asset with a decimal-adjusted amount and a log-index event index, and an idle
 * resync succeeds without duplicating anything. A second test boots with an invalid key and
 * expects the scrubbed fail-fast.
 */
@EnabledIfEnvironmentVariable(named = "ALCHEMY_LIVE_SMOKE", matches = "true")
@ActiveProfiles("e2e")
@SpringBootTest(
    properties = [
        "asset-sync.provider.type=alchemy",
        "asset-sync.sync.provider-timeout=20s",
    ],
)
class AlchemyLiveSmokeTests(
    @Autowired private val accountApplicationService: AccountApplicationService,
    @Autowired private val watchedAddressApplicationService: WatchedAddressApplicationService,
    @Autowired private val syncApplicationService: SyncApplicationService,
    @Autowired private val syncRunLifecycleService: SyncRunLifecycleService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val alchemyProperties: AlchemyProviderProperties,
) {

    @Test
    fun `live sync of the configured address succeeds, keeps the key out of the run, and leaves a durable cursor`() {
        val apiKey = requireNotNull(System.getenv("ASSET_SYNC_PROVIDER_ALCHEMY_API_KEY")) { "ASSET_SYNC_PROVIDER_ALCHEMY_API_KEY is required" }
        assertEquals(apiKey.trim(), alchemyProperties.apiKey, "the context must use the key from the environment")
        val account = accountApplicationService.createAccount(CreateAccountCommand(externalRef = "alchemy-live-smoke-${UUID.randomUUID()}"))
        val watchedAddress = watchedAddressApplicationService.registerWatchedAddress(
            RegisterWatchedAddressCommand(accountId = account.id, chainId = CHAIN_ID, address = ADDRESS, asset = ASSET, label = "alchemy-live-smoke"),
        )

        val first = runToCompletion(syncApplicationService.syncAddress(watchedAddress.id).id)
        assertEquals(SyncRunStatus.SUCCEEDED, first.status, "first live sync: ${first.lastError}")
        assertNull(first.lastError)
        assertEquals(0, first.failureAttempts)

        val cursor = cursorRow(watchedAddress.id)
        val providerCursor = assertNotNull(cursor["provider_cursor"] as String?, "provider_cursor must be durable")
        assertTrue(providerCursor.contains("\"p\":\"alchemy\""), providerCursor)
        assertFalse(providerCursor.contains("pageKey"), providerCursor)
        assertFalse(cursor["checkpoint"].toString().contains("pageKey"), cursor["checkpoint"].toString())
        assertNotNull(cursor["last_finalized_block_height"], "the finalized height must be populated")

        val rows = transactionRows()
        rows.forEach { row ->
            assertEquals(ASSET, row["asset"], row.toString())
            assertEquals(ADDRESS.lowercase(), row["address"], row.toString())
            assertTrue((row["event_index"] as Number).toInt() >= 0, row.toString())
            assertEquals(18, (row["amount"] as BigDecimal).scale(), "amounts are stored decimal-adjusted at scale 18: $row")
        }
        if (rows.isNotEmpty()) {
            assertNotNull(cursor["last_processed_block_height"], "high-water must follow emitted events")
        }

        val idle = runToCompletion(syncApplicationService.syncAddress(watchedAddress.id).id)
        assertEquals(SyncRunStatus.SUCCEEDED, idle.status, "idle resync: ${idle.lastError}")
        assertEquals(rows.size, transactionRows().size, "an idle resync must not duplicate rows")
        assertNotNull(cursorRow(watchedAddress.id)["provider_cursor"])

        val storedErrors = jdbcTemplate.queryForList("SELECT last_error FROM sync_runs", String::class.java).filterNotNull()
        assertTrue(storedErrors.none { it.contains(apiKey) }, "sync_runs.last_error must never carry the key")

        println(
            "ALCHEMY_LIVE_SMOKE summary: chain=$CHAIN_ID asset=$ASSET address=$ADDRESS startMode=${alchemyProperties.startMode} " +
                "firstRunEvents=${first.eventsSeen} rows=${rows.size} cursor=$providerCursor " +
                "finalized=${cursor["last_finalized_block_height"]} highWater=${cursor["last_processed_block_height"]}",
        )
    }

    @Test
    fun `an invalid key fails the boot with a scrubbed configuration error`() {
        val realKey = requireNotNull(System.getenv("ASSET_SYNC_PROVIDER_ALCHEMY_API_KEY"))
        val invalidKey = "invalid-smoke-key-${UUID.randomUUID()}"

        val thrown = assertFailsWith<Throwable> {
            SpringApplicationBuilder(AssetSyncServiceApplication::class.java)
                .profiles("e2e")
                .run(
                    "--server.port=0",
                    "--spring.main.banner-mode=off",
                    "--spring.datasource.url=${postgres.jdbcUrl}",
                    "--spring.datasource.username=${postgres.username}",
                    "--spring.datasource.password=${postgres.password}",
                    "--asset-sync.provider.type=alchemy",
                    "--asset-sync.provider.alchemy.api-key=$invalidKey",
                )
                .close()
        }

        val cause = generateSequence(thrown) { it.cause }.filterIsInstance<ProviderConfigurationException>().firstOrNull()
        assertNotNull(cause, "expected a ProviderConfigurationException in the failure chain of: $thrown")
        assertTrue(cause.message!!.contains("startup probe failed"), cause.message)
        assertFalse(cause.message!!.contains(realKey), "the real key must not appear: ${cause.message}")
        assertFalse(cause.message!!.contains(invalidKey), "even the invalid key must not appear: ${cause.message}")
        println("ALCHEMY_LIVE_SMOKE invalid key check: ${cause.message}")
    }

    private fun runToCompletion(runId: UUID): SyncRun {
        repeat(MAX_CLAIMS) {
            jdbcTemplate.update("UPDATE sync_runs SET next_attempt_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(1)), runId)
            syncRunLifecycleService
                .claimDueRuns(workerId = "alchemy-live-smoke-${UUID.randomUUID()}", limit = 1)
                .forEach { syncApplicationService.executeClaimedSyncRun(it) }
            val run = syncRunLifecycleService.get(runId)
            if (run.status == SyncRunStatus.SUCCEEDED || run.status == SyncRunStatus.FAILED) {
                return run
            }
        }
        error("sync run $runId did not finish within $MAX_CLAIMS claims: ${syncRunLifecycleService.get(runId)}")
    }

    private fun cursorRow(addressId: UUID): Map<String, Any?> =
        jdbcTemplate.queryForMap(
            "SELECT provider_cursor, checkpoint::text AS checkpoint, last_processed_block_height, last_processed_event_index, last_finalized_block_height FROM sync_cursors WHERE watched_address_id = ?",
            addressId,
        )

    private fun transactionRows(): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("SELECT asset, address, event_index, amount, block_height, status FROM observed_transactions ORDER BY block_height, event_index")

    companion object {
        private const val MAX_CLAIMS = 50
        private val CHAIN_ID: String = System.getenv("ALCHEMY_LIVE_SMOKE_CHAIN_ID")?.takeIf { it.isNotBlank() } ?: "eth-sepolia"
        private val ASSET: String = System.getenv("ALCHEMY_LIVE_SMOKE_ASSET")?.takeIf { it.isNotBlank() } ?: "USDC"
        private val ADDRESS: String = System.getenv("ALCHEMY_LIVE_SMOKE_ADDRESS")?.takeIf { it.isNotBlank() } ?: "unset"
        private val FROM_BLOCK: Long? = System.getenv("ALCHEMY_LIVE_SMOKE_FROM_BLOCK")?.takeIf { it.isNotBlank() }?.toLong()

        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("asset_sync_alchemy_smoke")
                .withUsername("asset_sync")
                .withPassword("asset_sync")
                .also { it.start() }

        init {
            require(ADDRESS != "unset") { "ALCHEMY_LIVE_SMOKE_ADDRESS is required" }
            AlchemyRolloutDatabase.prepare(postgres.jdbcUrl, postgres.username, postgres.password)
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            FROM_BLOCK?.let { fromBlock ->
                registry.add("asset-sync.provider.alchemy.start-mode") { "configured-block" }
                registry.add("asset-sync.provider.alchemy.networks.$CHAIN_ID.start-block") { fromBlock.toString() }
            }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
