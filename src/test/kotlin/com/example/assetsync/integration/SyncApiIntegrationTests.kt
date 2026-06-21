package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.example.assetsync.infrastructure.provider.FakeChainProviderKey
import com.example.assetsync.infrastructure.provider.FakeChainProviderStep
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
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
@SpringBootTest
@AutoConfigureMockMvc
class SyncApiIntegrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val fakeChainProvider: FakeChainProvider,
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
    fun `address sync succeeds and records changed provider events`() {
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

        mockMvc.perform(post("/api/v1/addresses/$addressId/sync"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.targetType").value("ADDRESS"))
            .andExpect(jsonPath("$.targetId").value(addressId))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.eventsSeen").value(2))
            .andExpect(jsonPath("$.eventsChanged").value(2))
            .andExpect(jsonPath("$.lastError").doesNotExist())
            .andExpect(jsonPath("$.startedAt").exists())
            .andExpect(jsonPath("$.finishedAt").exists())
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.updatedAt").exists())

        assertEquals(1, tableCount("sync_runs"))
        assertEquals(2, tableCount("observed_transactions"))
        assertEquals(2, tableCount("outbox_events"))
        assertEquals("SUCCEEDED", singleString("SELECT status FROM sync_runs"))
        assertEquals(2, singleInt("SELECT events_seen FROM sync_runs"))
        assertEquals(2, singleInt("SELECT events_changed FROM sync_runs"))
        assertEquals(
            listOf(FakeChainProviderKey("local-evm", "0xsync-address-success", "USDC")),
            fakeChainProvider.requestedKeys(),
        )
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `account sync succeeds over multiple active watched addresses`() {
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

        mockMvc.perform(post("/api/v1/accounts/$accountId/sync"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.targetType").value("ACCOUNT"))
            .andExpect(jsonPath("$.targetId").value(accountId))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.eventsSeen").value(2))
            .andExpect(jsonPath("$.eventsChanged").value(2))

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

        mockMvc.perform(post("/api/v1/addresses/$addressId/sync"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.eventsSeen").value(2))
            .andExpect(jsonPath("$.eventsChanged").value(1))

        assertEquals(1, tableCount("observed_transactions"))
        assertEquals(1, tableCount("outbox_events"))
        assertEquals(1, singleInt("SELECT count(*) FROM outbox_events WHERE event_type = 'TRANSACTION_SEEN'"))
    }

    @Test
    fun `provider failure marks sync run failed and returns service unavailable`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-provider-failure")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-provider-failure",
            asset = "USDC",
            steps = listOf(FakeChainProviderStep.Failure("Provider timeout")),
        )

        val result = mockMvc.perform(post("/api/v1/addresses/$addressId/sync"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/provider-unavailable"))
            .andExpect(jsonPath("$.syncRunId").exists())
            .andReturn()

        val syncRunId = objectMapper.readTree(result.response.contentAsString)["syncRunId"].asText()
        assertEquals("FAILED", singleString("SELECT status FROM sync_runs WHERE id = ?", UUID.fromString(syncRunId)))
        assertEquals(0, singleInt("SELECT events_seen FROM sync_runs WHERE id = ?", UUID.fromString(syncRunId)))
        assertEquals(0, singleInt("SELECT events_changed FROM sync_runs WHERE id = ?", UUID.fromString(syncRunId)))
        assertEquals("Provider timeout", singleString("SELECT last_error FROM sync_runs WHERE id = ?", UUID.fromString(syncRunId)))
        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `provider failure after an event keeps earlier committed ingestion`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-partial")
        val addressId = watchedAddress["id"].asText()

        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xsync-partial",
            asset = "USDC",
            steps = listOf(
                FakeChainProviderStep.Event(providerEvent(txHash = "0xsync-partial-first", address = "0xsync-partial")),
                FakeChainProviderStep.Failure("Provider cursor failed"),
            ),
        )

        mockMvc.perform(post("/api/v1/addresses/$addressId/sync"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/provider-unavailable"))

        assertEquals("FAILED", singleString("SELECT status FROM sync_runs"))
        assertEquals(1, singleInt("SELECT events_seen FROM sync_runs"))
        assertEquals(1, singleInt("SELECT events_changed FROM sync_runs"))
        assertEquals("Provider cursor failed", singleString("SELECT last_error FROM sync_runs"))
        assertEquals(1, tableCount("observed_transactions"))
        assertEquals(1, tableCount("outbox_events"))
        assertProviderCallsOutsideTransactions()
    }

    @Test
    fun `get sync run succeeds and missing sync run returns not found`() {
        val accountId = createAccount()
        val watchedAddress = registerAddress(accountId = accountId, address = "0xsync-get")
        val addressId = watchedAddress["id"].asText()

        val syncResult = mockMvc.perform(post("/api/v1/addresses/$addressId/sync"))
            .andExpect(status().isAccepted)
            .andReturn()
        val syncRunId = objectMapper.readTree(syncResult.response.contentAsString)["id"].asText()

        mockMvc.perform(get("/api/v1/sync-runs/$syncRunId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(syncRunId))
            .andExpect(jsonPath("$.targetType").value("ADDRESS"))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))

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

        mockMvc.perform(post("/api/v1/accounts/$accountId/sync"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.eventsSeen").value(1))
            .andExpect(jsonPath("$.eventsChanged").value(1))

        assertEquals(1, tableCount("observed_transactions"))
        assertEquals("0xsync-active", singleString("SELECT address FROM observed_transactions"))
        assertEquals(
            listOf(FakeChainProviderKey("local-evm", "0xsync-active", "USDC")),
            fakeChainProvider.requestedKeys(),
        )
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
