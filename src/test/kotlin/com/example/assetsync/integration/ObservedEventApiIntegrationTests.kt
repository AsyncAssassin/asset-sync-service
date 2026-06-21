package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ObservedEventApiIntegrationTests(
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
    fun `create new observed event stores transaction and outbox event`() {
        createWatchedAddress(address = "0xobserved-create")

        val response = postObservedEvent(address = "0xobserved-create")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.result").value("CREATED"))
            .andExpect(jsonPath("$.status").value("SEEN"))
            .andExpect(jsonPath("$.outboxEvents[0]").value("TRANSACTION_SEEN"))
            .andReturn()

        val transactionId = objectMapper.readTree(response.response.contentAsString)["transactionId"].asText()
        assertEquals(1, tableCount("observed_transactions"))
        assertEquals(1, tableCount("outbox_events"))
        assertEquals("TRANSACTION_SEEN", singleString("SELECT event_type FROM outbox_events"))
        assertEquals(transactionId, singleString("SELECT payload ->> 'transactionId' FROM outbox_events"))
    }

    @Test
    fun `duplicate event is no change and does not duplicate outbox`() {
        createWatchedAddress(address = "0xobserved-duplicate")
        postObservedEvent(address = "0xobserved-duplicate")
            .andExpect(status().isCreated)

        postObservedEvent(address = "0xobserved-duplicate")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("NO_CHANGE"))
            .andExpect(jsonPath("$.status").value("SEEN"))
            .andExpect(jsonPath("$.outboxEvents.length()").value(0))

        assertEquals(1, tableCount("observed_transactions"))
        assertEquals(1, tableCount("outbox_events"))
    }

    @Test
    fun `higher confirmations below threshold updates transaction without outbox`() {
        createWatchedAddress(address = "0xobserved-confirmations")
        postObservedEvent(address = "0xobserved-confirmations", confirmations = 1)
            .andExpect(status().isCreated)

        postObservedEvent(address = "0xobserved-confirmations", confirmations = 2, blockHeight = 101)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("UPDATED"))
            .andExpect(jsonPath("$.status").value("SEEN"))
            .andExpect(jsonPath("$.outboxEvents.length()").value(0))

        assertEquals(2, singleInt("SELECT confirmations FROM observed_transactions"))
        assertEquals(101L, singleLong("SELECT block_height FROM observed_transactions"))
        assertEquals(1, tableCount("outbox_events"))
    }

    @Test
    fun `confirmations reaching threshold confirms and creates confirmed outbox event`() {
        createWatchedAddress(address = "0xobserved-threshold")
        postObservedEvent(address = "0xobserved-threshold", confirmations = 1)
            .andExpect(status().isCreated)

        postObservedEvent(address = "0xobserved-threshold", confirmations = 3, blockHeight = 102)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("UPDATED"))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.outboxEvents[0]").value("TRANSACTION_CONFIRMED"))

        assertEquals("CONFIRMED", singleString("SELECT status FROM observed_transactions"))
        assertEquals(1, eventCount("TRANSACTION_CONFIRMED"))
    }

    @Test
    fun `provider confirmed status confirms even below threshold`() {
        createWatchedAddress(address = "0xobserved-provider-confirmed")
        postObservedEvent(address = "0xobserved-provider-confirmed", confirmations = 1)
            .andExpect(status().isCreated)

        postObservedEvent(
            address = "0xobserved-provider-confirmed",
            confirmations = 1,
            statusValue = "CONFIRMED",
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("UPDATED"))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.outboxEvents[0]").value("TRANSACTION_CONFIRMED"))

        assertEquals("CONFIRMED", singleString("SELECT status FROM observed_transactions"))
        assertEquals(1, eventCount("TRANSACTION_CONFIRMED"))
    }

    @Test
    fun `reorg after confirmed reverts and duplicate reorg is no change`() {
        createWatchedAddress(address = "0xobserved-reorg")
        postObservedEvent(address = "0xobserved-reorg", confirmations = 3)
            .andExpect(status().isCreated)

        postObservedEvent(address = "0xobserved-reorg", confirmations = 1, statusValue = "REVERTED")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("UPDATED"))
            .andExpect(jsonPath("$.status").value("REVERTED"))
            .andExpect(jsonPath("$.outboxEvents[0]").value("TRANSACTION_REVERTED"))

        postObservedEvent(address = "0xobserved-reorg", confirmations = 1, statusValue = "REVERTED")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("NO_CHANGE"))
            .andExpect(jsonPath("$.outboxEvents.length()").value(0))

        assertEquals("REVERTED", singleString("SELECT status FROM observed_transactions"))
        assertEquals(1, eventCount("TRANSACTION_REVERTED"))
    }

    @Test
    fun `immutable amount and direction conflicts return conflict without outbox`() {
        createWatchedAddress(address = "0xobserved-conflict")
        postObservedEvent(address = "0xobserved-conflict", txHash = "0xamount-conflict")
            .andExpect(status().isCreated)

        postObservedEvent(
            address = "0xobserved-conflict",
            txHash = "0xamount-conflict",
            amount = "99.000000000000000000",
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/immutable-field-conflict"))
            .andExpect(jsonPath("$.conflictingFields[0]").value("AMOUNT"))

        postObservedEvent(address = "0xobserved-conflict", txHash = "0xdirection-conflict")
            .andExpect(status().isCreated)

        postObservedEvent(
            address = "0xobserved-conflict",
            txHash = "0xdirection-conflict",
            direction = "OUTBOUND",
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.conflictingFields[0]").value("DIRECTION"))

        assertEquals(2, tableCount("observed_transactions"))
        assertEquals(2, tableCount("outbox_events"))
    }

    @Test
    fun `stale lower confirmations are no change and do not decrease stored value`() {
        createWatchedAddress(address = "0xobserved-stale")
        postObservedEvent(address = "0xobserved-stale", confirmations = 2)
            .andExpect(status().isCreated)

        postObservedEvent(address = "0xobserved-stale", confirmations = 1)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("NO_CHANGE"))
            .andExpect(jsonPath("$.status").value("SEEN"))

        assertEquals(2, singleInt("SELECT confirmations FROM observed_transactions"))
        assertEquals(1, tableCount("outbox_events"))
    }

    @Test
    fun `validation failures return bad request`() {
        createWatchedAddress(address = "0xobserved-validation")

        val invalidBodies = listOf(
            observedEventBody(address = "   "),
            observedEventBody(eventIndex = -1),
            observedEventBody(amount = "-1.00"),
            observedEventBody(amount = "1.0000000000000000001"),
            observedEventBody(blockHeight = -1),
            observedEventBody(confirmations = -1),
            observedEventBody(direction = "SIDEWAYS"),
            observedEventBody(statusValue = "PENDING"),
        )

        invalidBodies.forEach { body ->
            mockMvc.perform(
                post("/api/v1/observed-events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.status").value(400))
        }

        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
    }

    @Test
    fun `missing and disabled watched addresses return not found`() {
        createWatchedAddress(address = "0xobserved-disabled")

        postObservedEvent(address = "0xobserved-missing")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Watched address not found"))

        jdbcTemplate.update(
            "UPDATE watched_addresses SET status = 'DISABLED' WHERE address = ?",
            "0xobserved-disabled",
        )

        postObservedEvent(address = "0xobserved-disabled")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Watched address not found"))

        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
    }

    @Test
    fun `concurrent duplicate ingestion creates one transaction and one outbox event`() {
        createWatchedAddress(address = "0xobserved-concurrent")
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)

        try {
            val tasks = (1..2).map {
                Callable<Pair<Int, String>> {
                    start.await()
                    val result = mockMvc.perform(
                        post("/api/v1/observed-events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(observedEventBody(address = "0xobserved-concurrent")),
                    ).andReturn()

                    result.response.status to objectMapper
                        .readTree(result.response.contentAsString)["result"]
                        .asText()
                }
            }

            val futures = tasks.map(executor::submit)
            start.countDown()
            val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(setOf(200, 201), outcomes.map { it.first }.toSet())
            assertEquals(setOf("CREATED", "NO_CHANGE"), outcomes.map { it.second }.toSet())
            assertEquals(1, tableCount("observed_transactions"))
            assertEquals(1, tableCount("outbox_events"))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun createWatchedAddress(address: String, asset: String = "USDC"): JsonNode {
        val accountId = createAccount()
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

    private fun postObservedEvent(
        txHash: String = "0xdeadbeef",
        eventIndex: Int = 0,
        address: String,
        asset: String = "USDC",
        amount: String = "12.340000000000000000",
        blockHeight: Long = 100,
        confirmations: Int = 1,
        direction: String = "INBOUND",
        statusValue: String = "SEEN",
    ) =
        mockMvc.perform(
            post("/api/v1/observed-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    observedEventBody(
                        txHash = txHash,
                        eventIndex = eventIndex,
                        address = address,
                        asset = asset,
                        amount = amount,
                        blockHeight = blockHeight,
                        confirmations = confirmations,
                        direction = direction,
                        statusValue = statusValue,
                    ),
                ),
        )

    private fun observedEventBody(
        chainId: String = "local-evm",
        txHash: String = "0xdeadbeef",
        eventIndex: Int = 0,
        address: String = "0xabc123",
        asset: String = "USDC",
        amount: String = "12.340000000000000000",
        blockHeight: Long = 100,
        confirmations: Int = 1,
        direction: String = "INBOUND",
        statusValue: String = "SEEN",
    ): String =
        objectMapper.writeValueAsString(
            mapOf(
                "chainId" to chainId,
                "txHash" to txHash,
                "eventIndex" to eventIndex,
                "address" to address,
                "asset" to asset,
                "amount" to amount,
                "blockHeight" to blockHeight,
                "confirmations" to confirmations,
                "direction" to direction,
                "status" to statusValue,
            ),
        )

    private fun tableCount(table: String): Int =
        requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun eventCount(eventType: String): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = ?",
                Int::class.java,
                eventType,
            ),
        )

    private fun singleString(sql: String): String =
        requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java))

    private fun singleInt(sql: String): Int =
        requireNotNull(jdbcTemplate.queryForObject(sql, Int::class.java))

    private fun singleLong(sql: String): Long =
        requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java))

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
}
