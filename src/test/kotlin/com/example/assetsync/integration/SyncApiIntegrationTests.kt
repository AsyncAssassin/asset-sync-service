package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.api.dto.MAX_TX_HASH_LENGTH
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.example.assetsync.infrastructure.provider.FakeChainProviderKey
import com.example.assetsync.infrastructure.provider.FakeChainProviderStep
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "asset-sync.sync.provider-timeout=100ms",
        "asset-sync.sync.provider-max-threads=2",
        "asset-sync.sync.account-sync-batch-size=1",
        "asset-sync.sync.max-account-sync-addresses=2",
        "asset-sync.sync.worker.max-concurrency=2",
        "asset-sync.sync.worker.retry-backoff-base-delay=1s",
        "asset-sync.sync.worker.retry-backoff-max-delay=1s",
    ],
)
@AutoConfigureMockMvc
class SyncApiIntegrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
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
    fun `address sync POST returns accepted quickly and worker records changed provider events`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-address-success")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-address-success",
            asset = "USDC",
            events = listOf(
                providerEvent(txHash = "0xsync-address-success-1", address = "0xsync-address-success"),
                providerEvent(txHash = "0xsync-address-success-2", address = "0xsync-address-success"),
            ),
        )

        val startedAt = System.nanoTime()
        val syncRunId = submitAddressSync(addressId)
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertTrue(elapsed < Duration.ofMillis(500), "POST should only enqueue; elapsed=$elapsed")
        assertEquals(emptyList(), fakeChainProvider.requestedKeys())
        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertNull(nullableTimestamp("SELECT started_at FROM sync_runs WHERE id = ?", syncRunId))

        runNextClaimedSyncs()

        mockMvc.perform(get("/api/v1/sync-runs/$syncRunId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(syncRunId.toString()))
            .andExpect(jsonPath("$.targetType").value("ADDRESS"))
            .andExpect(jsonPath("$.targetId").value(addressId))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.eventsSeen").value(2))
            .andExpect(jsonPath("$.eventsChanged").value(2))
            .andExpect(jsonPath("$.queuedAt").exists())
            .andExpect(jsonPath("$.startedAt").exists())
            .andExpect(jsonPath("$.finishedAt").exists())

        assertEquals(1, tableCount("sync_runs"))
        assertEquals(2, tableCount("observed_transactions"))
        assertEquals(2, tableCount("outbox_events"))
        assertEquals(
            listOf(FakeChainProviderKey("local-evm", "0xsync-address-success", "USDC")),
            fakeChainProvider.requestedKeys(),
        )
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `account sync worker succeeds over multiple active watched addresses`() {
        val accountId = createAccount()
        registerAddress(accountId = accountId, address = "0xsync-account-one", asset = "USDC")
        registerAddress(accountId = accountId, address = "0xsync-account-two", asset = "ETH")

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-account-one",
            asset = "USDC",
            events = listOf(providerEvent(txHash = "0xsync-account-one", address = "0xsync-account-one")),
        )
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-account-two",
            asset = "ETH",
            events = listOf(providerEvent(txHash = "0xsync-account-two", address = "0xsync-account-two", asset = "ETH")),
        )

        val syncRunId = submitAccountSync(accountId)
        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(2, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(2, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(2, tableCount("observed_transactions"))
        assertEquals(2, tableCount("outbox_events"))
        assertEquals(
            setOf(
                FakeChainProviderKey("local-evm", "0xsync-account-one", "USDC"),
                FakeChainProviderKey("local-evm", "0xsync-account-two", "ETH"),
            ),
            fakeChainProvider.requestedKeys().toSet(),
        )
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `duplicate in-flight target returns the same sync run before queue cap checks`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-duplicate-target")
        val addressId = watchedAddress["id"].asText()

        val firstRunId = submitAddressSync(addressId)
        val secondRunId = submitAddressSync(addressId)

        assertEquals(firstRunId, secondRunId)
        assertEquals(1, tableCount("sync_runs"))
        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", firstRunId))
        assertEquals(emptyList(), fakeChainProvider.requestedKeys())
    }

    @Test
    fun `sync uses existing observed event ingestion idempotency`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-duplicate")
        val addressId = watchedAddress["id"].asText()
        val duplicateEvent = providerEvent(txHash = "0xsync-duplicate-tx", address = "0xsync-duplicate")

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-duplicate",
            asset = "USDC",
            events = listOf(duplicateEvent, duplicateEvent),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(2, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, tableCount("observed_transactions"))
        assertEquals(1, tableCount("outbox_events"))
    }

    @Test
    fun `retryable provider failure requeues the run without bubbling to POST`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-provider-failure")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-provider-failure",
            asset = "USDC",
            steps = listOf(FakeChainProviderStep.Failure("Provider timeout")),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT attempts FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals("Provider timeout", singleString("SELECT last_error FROM sync_runs WHERE id = ?", syncRunId))
        assertNull(nullableTimestamp("SELECT locked_until FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `retry progress is cumulative across worker attempts`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-retry-progress")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-retry-progress",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Event(
                    providerEvent(txHash = "0xsync-retry-progress-1", address = "0xsync-retry-progress"),
                ),
                FakeChainProviderStep.Failure("Provider failed after partial ingest"),
            ),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, tableCount("observed_transactions"))

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-retry-progress",
            asset = "USDC",
            events = emptyList(),
        )
        jdbcTemplate.update(
            "UPDATE sync_runs SET next_attempt_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(1)),
            syncRunId,
        )

        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, tableCount("observed_transactions"))
    }

    @Test
    fun `provider data mismatch is terminal failed`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-provider-mismatch")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-provider-mismatch",
            asset = "USDC",
            events = listOf(providerEvent(txHash = "0xsync-provider-mismatch", address = "0xnot-watched")),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
    }

    @Test
    fun `provider timeout is an absolute deadline and requeues partial attempt`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-slow-trickle")
        val addressId = watchedAddress["id"].asText()
        val steps = (1..20).flatMap { index ->
            listOf(
                FakeChainProviderStep.Delay(Duration.ofMillis(90)),
                FakeChainProviderStep.Event(
                    providerEvent(
                        txHash = "0xsync-slow-trickle-$index",
                        address = "0xsync-slow-trickle",
                        eventIndex = index,
                    ),
                ),
            )
        }

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-slow-trickle",
            asset = "USDC",
            steps = steps,
        )

        val syncRunId = submitAddressSync(addressId)
        val startedAt = System.nanoTime()
        runNextClaimedSyncs()
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertTrue(elapsed < Duration.ofSeconds(1), "Expected absolute deadline near 100ms, elapsed=$elapsed")
        assertEquals("QUEUED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertTrue(
            singleString("SELECT last_error FROM sync_runs WHERE id = ?", syncRunId).startsWith("Provider timeout"),
        )
        assertTrue(singleInt("SELECT count(*) FROM observed_transactions") < 20)
    }

    @Test
    fun `provider event database constraint violation is terminal failed`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-db-constraint")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-db-constraint",
            asset = "USDC",
            events = listOf(
                providerEvent(
                    txHash = "x".repeat(MAX_TX_HASH_LENGTH + 1),
                    address = "0xsync-db-constraint",
                ),
            ),
        )

        val syncRunId = submitAddressSync(addressId)
        runNextClaimedSyncs()

        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
    }

    @Test
    fun `get sync run succeeds and missing sync run returns not found`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-get")
        val addressId = watchedAddress["id"].asText()

        val syncRunId = submitAddressSync(addressId)

        mockMvc.perform(get("/api/v1/sync-runs/$syncRunId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(syncRunId.toString()))
            .andExpect(jsonPath("$.targetType").value("ADDRESS"))
            .andExpect(jsonPath("$.status").value("QUEUED"))

        mockMvc.perform(get("/api/v1/sync-runs/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/not-found"))
            .andExpect(jsonPath("$.title").value("Sync run not found"))
    }

    @Test
    fun `address and account sync return not found before creating sync run`() {
        mockMvc.perform(post("/api/v1/addresses/${UUID.randomUUID()}/sync"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Watched address not found"))

        mockMvc.perform(post("/api/v1/accounts/${UUID.randomUUID()}/sync"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Account not found"))

        assertEquals(0, tableCount("sync_runs"))
    }

    @Test
    fun `account sync skips disabled watched addresses`() {
        val accountId = createAccount()
        registerAddress(accountId = accountId, address = "0xsync-active", asset = "USDC")
        val disabledAddress = registerAddress(accountId = accountId, address = "0xsync-disabled", asset = "ETH")
        jdbcTemplate.update(
            "UPDATE watched_addresses SET status = 'DISABLED' WHERE id = ?",
            UUID.fromString(disabledAddress["id"].asText()),
        )

        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-active",
            asset = "USDC",
            events = listOf(providerEvent(txHash = "0xsync-active", address = "0xsync-active")),
        )
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xsync-disabled",
            asset = "ETH",
            events = listOf(providerEvent(txHash = "0xsync-disabled", address = "0xsync-disabled", asset = "ETH")),
        )

        val syncRunId = submitAccountSync(accountId)
        runNextClaimedSyncs()

        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(1, tableCount("observed_transactions"))
        assertEquals("0xsync-active", singleString("SELECT address FROM observed_transactions"))
        assertEquals(
            listOf(FakeChainProviderKey("local-evm", "0xsync-active", "USDC")),
            fakeChainProvider.requestedKeys(),
        )
    }

    @Test
    fun `account sync over address cap is terminal failed during worker execution`() {
        val accountId = createAccount()
        registerAddress(accountId = accountId, address = "0xsync-cap-one", asset = "USDC")
        registerAddress(accountId = accountId, address = "0xsync-cap-two", asset = "ETH")
        registerAddress(accountId = accountId, address = "0xsync-cap-three", asset = "DAI")

        val syncRunId = submitAccountSync(accountId)
        runNextClaimedSyncs()

        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals("Account has more than 2 active watched addresses.", singleString("SELECT last_error FROM sync_runs WHERE id = ?", syncRunId))
        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
        assertEquals(emptyList(), fakeChainProvider.requestedKeys())
    }

    private fun submitAddressSync(addressId: String): UUID =
        submitSync("/api/v1/addresses/$addressId/sync", expectedTargetId = addressId, expectedTargetType = "ADDRESS")

    private fun submitAccountSync(accountId: String): UUID =
        submitSync("/api/v1/accounts/$accountId/sync", expectedTargetId = accountId, expectedTargetType = "ACCOUNT")

    private fun submitSync(path: String, expectedTargetId: String, expectedTargetType: String): UUID {
        val result = mockMvc.perform(post(path))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.targetType").value(expectedTargetType))
            .andExpect(jsonPath("$.targetId").value(expectedTargetId))
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.eventsSeen").value(0))
            .andExpect(jsonPath("$.eventsChanged").value(0))
            .andExpect(jsonPath("$.queuedAt").exists())
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.updatedAt").exists())
            .andReturn()
        val syncRunId = UUID.fromString(objectMapper.readTree(result.response.contentAsString)["id"].asText())
        assertEquals("/api/v1/sync-runs/$syncRunId", result.response.getHeader("Location"))
        return syncRunId
    }

    private fun runNextClaimedSyncs(limit: Int = 10): List<UUID> {
        val claimed = syncRunLifecycleService.claimDueRuns(
            workerId = "test-worker-${UUID.randomUUID()}",
            limit = limit,
        )
        assertTrue(claimed.isNotEmpty(), "Expected at least one due sync run to be claimed.")
        claimed.forEach { syncApplicationService.executeClaimedSyncRun(it) }
        return claimed.map { it.run.id }
    }

    private fun createAccount(): String {
        val result = mockMvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"externalRef":"account-${UUID.randomUUID()}"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()

        return objectMapper.readTree(result.response.contentAsString)["id"].asText()
    }

    private fun registerAddress(
        accountId: String,
        address: String,
        asset: String = "USDC",
    ): JsonNode {
        val result = mockMvc.perform(
            post("/api/v1/accounts/$accountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "chainId" to "local-evm",
                            "address" to address,
                            "asset" to asset,
                            "label" to "primary",
                        ),
                    ),
                ),
        )
            .andExpect(status().isCreated)
            .andReturn()

        return objectMapper.readTree(result.response.contentAsString)
    }

    private fun providerEvent(
        txHash: String,
        address: String,
        eventIndex: Int = 0,
        asset: String = "USDC",
        amount: String = "12.340000000000000000",
        blockHeight: Long = 100,
        confirmations: Int = 1,
        direction: Direction = Direction.INBOUND,
        status: TransactionStatus = TransactionStatus.SEEN,
    ): ChainProviderObservedEvent =
        ChainProviderObservedEvent(
            chainId = "local-evm",
            txHash = txHash,
            eventIndex = eventIndex,
            address = address,
            asset = asset,
            amount = BigDecimal(amount),
            blockHeight = blockHeight,
            confirmations = confirmations,
            direction = direction,
            status = status,
        )

    private fun assertProviderCallsOutsideTransactions() {
        assertTrue(fakeChainProvider.transactionActiveSnapshots().isNotEmpty())
        assertEquals(setOf(false), fakeChainProvider.transactionActiveSnapshots().toSet())
    }

    private fun tableCount(table: String): Int =
        requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun singleString(sql: String, vararg args: Any): String =
        requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java, *args))

    private fun singleInt(sql: String, vararg args: Any): Int =
        requireNotNull(jdbcTemplate.queryForObject(sql, Int::class.java, *args))

    private fun nullableTimestamp(sql: String, vararg args: Any): Timestamp? =
        jdbcTemplate.queryForObject(sql, Timestamp::class.java, *args)

    private fun cleanDatabase() {
        fakeChainProvider.clear()
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
        jdbcTemplate.update("DELETE FROM chain_configs WHERE chain_id <> 'local-evm'")
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
