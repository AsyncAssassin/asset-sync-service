package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.transaction.NewOutboxEvent
import com.example.assetsync.application.transaction.OutboxRepository
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ObservedEventAtomicityIntegrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
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
    fun `outbox insert failure rolls back observed transaction insert`() {
        createWatchedAddress(address = "0xobserved-atomicity")

        mockMvc.perform(
            post("/api/v1/observed-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(observedEventBody(address = "0xobserved-atomicity")),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/database-unavailable"))

        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
    }

    private fun createWatchedAddress(address: String): String {
        val accountId = createAccount()
        val result = mockMvc.perform(
            post("/api/v1/accounts/$accountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "chainId" to "local-evm",
                            "address" to address,
                            "asset" to "USDC",
                            "label" to "primary",
                        ),
                    ),
                ),
        )
            .andExpect(status().isCreated)
            .andReturn()

        return objectMapper.readTree(result.response.contentAsString)["id"].asText()
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

    private fun observedEventBody(address: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "chainId" to "local-evm",
                "txHash" to "0xatomicity",
                "eventIndex" to 0,
                "address" to address,
                "asset" to "USDC",
                "amount" to "12.340000000000000000",
                "blockHeight" to 100,
                "confirmations" to 1,
                "direction" to "INBOUND",
                "status" to "SEEN",
            ),
        )

    private fun tableCount(table: String): Int =
        requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun cleanDatabase() {
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

    @TestConfiguration
    class FailingOutboxConfiguration {

        @Bean
        @Primary
        fun failingOutboxRepository(): OutboxRepository =
            object : OutboxRepository {
                override fun insert(event: NewOutboxEvent): Boolean {
                    throw DataAccessResourceFailureException("outbox insert failed")
                }
            }
    }
}
