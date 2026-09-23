package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Guards the provider pool: a provider fetch that floods the bounded hand-off queue while the
 * consumer aborts must not leak the executor thread. With `provider-max-threads=1` a single leaked
 * thread would exhaust the pool, so the second sync would be rejected (503). Before the fix the
 * producer blocked forever on a full queue after cancellation; after the fix it unwinds and the pool
 * recovers.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "asset-sync.sync.provider-max-threads=1",
        "asset-sync.sync.worker.max-concurrency=1",
    ],
)
@AutoConfigureMockMvc
// Own context: this test intentionally stresses the size-1 provider pool; a fresh, disposed-after
// context isolates that state from the other pool-1 test.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SyncProviderPoolLeakIntegrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val fakeChainProvider: FakeChainProvider,
    @Autowired private val syncRunLifecycleService: SyncRunLifecycleService,
    @Autowired private val syncApplicationService: SyncApplicationService,
) {

    @BeforeEach
    fun cleanBeforeEach() {
        fakeChainProvider.clear()
        cleanDatabase()
    }

    @AfterEach
    fun cleanAfterEach() {
        fakeChainProvider.clear()
        cleanDatabase()
    }

    @Test
    fun `a flooding fetch that aborts the consumer does not exhaust the provider pool`() {
        val accountId = createAccount()
        val leakAddress = registerAddress(accountId, "0xleak-a")
        val healthyAddress = registerAddress(accountId, "0xleak-b")

        // 300 events whose address is NOT registered -> the first ingest throws, the consumer aborts
        // and cancels the producer while it is flooding the 100-slot queue with the remaining events.
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xleak-a",
            asset = "USDC",
            events = (1..300).map { providerEvent(txHash = "0xflood-$it", address = "0xmismatch") },
        )
        val failedRunId = submitSync(leakAddress)
        runNextClaimedSync()
        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", failedRunId))

        // The single pool thread must be free again. Poll briefly to absorb the producer's unwind
        // window; a real leak (pre-fix) never releases the thread, so this stays red on the old code.
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xleak-b",
            asset = "USDC",
            events = listOf(providerEvent(txHash = "0xhealthy", address = "0xleak-b")),
        )
        val healthyRunId = submitSync(healthyAddress)
        runNextClaimedSync()
        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs WHERE id = ?", healthyRunId))
    }

    private fun submitSync(addressId: String): UUID {
        val result = mockMvc.perform(post("/api/v1/addresses/$addressId/sync"))
            .andExpect(status().isAccepted)
            .andReturn()
        return UUID.fromString(objectMapper.readTree(result.response.contentAsString)["id"].asText())
    }

    private fun runNextClaimedSync() {
        val claimed = syncRunLifecycleService.claimDueRuns(
            workerId = "leak-test-worker-${UUID.randomUUID()}",
            limit = 1,
        )
        assertEquals(1, claimed.size)
        syncApplicationService.executeClaimedSyncRun(claimed.single())
    }

    private fun createAccount(): String {
        val result = mockMvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"externalRef":"leak-${UUID.randomUUID()}"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()
        return objectMapper.readTree(result.response.contentAsString)["id"].asText()
    }

    private fun registerAddress(accountId: String, address: String): String {
        val result = mockMvc.perform(
            post("/api/v1/accounts/$accountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("chainId" to "local-evm", "address" to address, "asset" to "USDC", "label" to "leak"),
                    ),
                ),
        )
            .andExpect(status().isCreated)
            .andReturn()
        return objectMapper.readTree(result.response.contentAsString)["id"].asText()
    }

    private fun providerEvent(txHash: String, address: String): ChainProviderObservedEvent =
        ChainProviderObservedEvent(
            chainId = "local-evm",
            txHash = txHash,
            eventIndex = 0,
            address = address,
            asset = "USDC",
            amount = BigDecimal("1.000000000000000000"),
            blockHeight = 100,
            confirmations = 1,
            direction = Direction.INBOUND,
            status = TransactionStatus.SEEN,
        )

    private fun cleanDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
        jdbcTemplate.update("DELETE FROM chain_configs WHERE chain_id NOT IN ('local-evm', 'eth-sepolia', 'eth-mainnet')")
        jdbcTemplate.update(
            "UPDATE chain_configs SET enabled = true, required_confirmations = 3, updated_at = ? WHERE chain_id = 'local-evm'",
            Timestamp.from(Instant.now()),
        )
    }

    private fun singleString(sql: String, vararg args: Any): String =
        requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java, *args))
}
