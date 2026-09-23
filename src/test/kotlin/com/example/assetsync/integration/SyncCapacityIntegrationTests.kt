package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "asset-sync.sync.provider-max-threads=1",
        "asset-sync.sync.worker.max-concurrency=1",
        "asset-sync.sync.worker.max-in-flight-runs=1",
    ],
)
@AutoConfigureMockMvc
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
    fun `queue cap rejects new targets but allows duplicate in-flight target`() {
        val accountId = createAccount()
        val firstAddress = registerAddress(accountId, "0xcap-first")
        val secondAddress = registerAddress(accountId, "0xcap-second")

        val first = mockMvc.perform(post("/api/v1/addresses/$firstAddress/sync"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andReturn()
        val firstRunId = objectMapper.readTree(first.response.contentAsString)["id"].asText()

        mockMvc.perform(post("/api/v1/addresses/$firstAddress/sync"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.id").value(firstRunId))

        mockMvc.perform(post("/api/v1/addresses/$secondAddress/sync"))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/sync-queue-full"))
            .andExpect(jsonPath("$.maxInFlightRuns").value(1))
            // The default worker claim interval (asset-sync.sync.worker.fixed-delay, 5s).
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))

        assertEquals(1, tableCount("sync_runs"))
        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(emptyList(), fakeChainProvider.requestedKeys())
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

    private fun tableCount(table: String): Int =
        requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java))

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
}
