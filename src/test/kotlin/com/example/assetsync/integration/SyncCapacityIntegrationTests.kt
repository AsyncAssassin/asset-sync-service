package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.example.assetsync.infrastructure.provider.FakeChainProviderStep
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Guards N8: when the sync pool is saturated the extra request must be a distinct capacity signal
 * (429 sync-capacity-exceeded), not a mislabeled `provider-unavailable` (503). With one pool thread
 * held by a slow sync, a concurrent sync is rejected by the executor's AbortPolicy.
 */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["asset-sync.sync.provider-max-threads=1"])
@AutoConfigureMockMvc
// Own context: this test deliberately saturates the size-1 provider pool; a fresh, disposed-after
// context keeps that state from bleeding into (or from) the other pool-1 test.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SyncCapacityIntegrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val fakeChainProvider: FakeChainProvider,
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
    fun `a sync beyond the pool cap is rejected as capacity, not provider-unavailable`() {
        val accountId = createAccount()
        val slowAddress = registerAddress(accountId, "0xcap-slow")
        val secondAddress = registerAddress(accountId, "0xcap-second")

        // The slow sync occupies the single pool thread for ~3s (the delay runs on that thread).
        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xcap-slow",
            asset = "USDC",
            steps = listOf(FakeChainProviderStep.Delay(Duration.ofSeconds(3))),
        )
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xcap-second",
            asset = "USDC",
            events = listOf(providerEvent("0xcap-second-tx", "0xcap-second")),
        )

        val executor = Executors.newSingleThreadExecutor()
        try {
            val slowSync = executor.submit {
                mockMvc.perform(post("/api/v1/addresses/$slowAddress/sync")).andReturn()
            }
            // Deterministic readiness: FakeChainProvider records the key on fetch entry, which runs
            // on the single pool thread — so once it appears the pool is provably occupied.
            awaitProviderFetchStarted("0xcap-slow")

            mockMvc.perform(post("/api/v1/addresses/$secondAddress/sync"))
                .andExpect(status().isTooManyRequests)
                .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/sync-capacity-exceeded"))

            // Cap-gated before createStarted: the rejected sync leaves no orphan run behind.
            val rejectedRuns = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sync_runs WHERE target_id = ?::uuid",
                Int::class.java,
                secondAddress,
            )
            assertEquals(0, rejectedRuns)

            slowSync.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitProviderFetchStarted(address: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (fakeChainProvider.requestedKeys().any { it.address == address }) {
                return
            }
            Thread.sleep(10)
        }
        throw AssertionError("Provider fetch for $address did not start within the timeout.")
    }

    private fun createAccount(): String {
        val result = mockMvc.perform(
            post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"externalRef":"cap-${UUID.randomUUID()}"}"""),
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
                        mapOf("chainId" to "local-evm", "address" to address, "asset" to "USDC", "label" to "cap"),
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
        jdbcTemplate.update("DELETE FROM chain_configs WHERE chain_id <> 'local-evm'")
        jdbcTemplate.update(
            "UPDATE chain_configs SET enabled = true, required_confirmations = 3, updated_at = ? WHERE chain_id = 'local-evm'",
            Timestamp.from(Instant.now()),
        )
    }
}
