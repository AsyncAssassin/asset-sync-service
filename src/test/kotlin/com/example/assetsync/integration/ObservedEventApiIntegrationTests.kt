package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.api.dto.MAX_AMOUNT_LENGTH
import com.example.assetsync.api.dto.MAX_TX_HASH_LENGTH
import com.example.assetsync.config.JacksonConfiguration.Companion.MAX_JSON_STRING_LENGTH
import com.example.assetsync.infrastructure.provider.ProviderEventsPageResponse
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
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
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
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
            .andExpect(header().doesNotExist("Location"))
            .andExpect(jsonPath("$.result").value("CREATED"))
            .andExpect(jsonPath("$.status").value("SEEN"))
            .andExpect(jsonPath("$.outboxEvents[0]").value("TRANSACTION_SEEN"))
            .andReturn()

        val transactionId = objectMapper.readTree(response.response.contentAsString)["transactionId"].asText()
        assertEquals(1, tableCount("observed_transactions"))
        assertEquals(1, tableCount("outbox_events"))
        assertEquals("TRANSACTION_SEEN", singleString("SELECT event_type FROM outbox_events"))
        assertEquals(transactionId, singleString("SELECT payload ->> 'transactionId' FROM outbox_events"))
        // The test profile authenticates nobody, so the API caller is recorded as anonymous.
        assertEquals("rest:anonymous", singleString("SELECT source FROM observed_transactions"))
        assertEquals("rest:anonymous", singleString("SELECT payload ->> 'source' FROM outbox_events"))
    }

    @Test
    fun `observed event identity is normalized before watched address lookup`() {
        createWatchedAddress(address = "0xobserved-normalized", asset = "USDC")

        postObservedEvent(address = "0xOBSERVED-NORMALIZED", asset = "usdc")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.result").value("CREATED"))

        assertEquals("0xobserved-normalized", singleString("SELECT address FROM observed_transactions"))
        assertEquals("USDC", singleString("SELECT asset FROM observed_transactions"))
    }

    @Test
    fun `tx hash case is normalized so mixed-case duplicates dedup to one lineage`() {
        createWatchedAddress(address = "0xtxnorm")

        postObservedEvent(address = "0xtxnorm", txHash = "0xABCDEF")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.result").value("CREATED"))

        postObservedEvent(address = "0xtxnorm", txHash = "0xabcdef")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("NO_CHANGE"))

        assertEquals(1, tableCount("observed_transactions"))
        assertEquals("0xabcdef", singleString("SELECT tx_hash FROM observed_transactions"))
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
    fun `equal confirmation block height correction persists without outbox and lower confirmation does not overwrite`() {
        createWatchedAddress(address = "0xobserved-block-correction")
        postObservedEvent(address = "0xobserved-block-correction", confirmations = 2, blockHeight = 100)
            .andExpect(status().isCreated)

        postObservedEvent(address = "0xobserved-block-correction", confirmations = 2, blockHeight = 101)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("UPDATED"))
            .andExpect(jsonPath("$.status").value("SEEN"))
            .andExpect(jsonPath("$.outboxEvents.length()").value(0))

        assertEquals(2, singleInt("SELECT confirmations FROM observed_transactions"))
        assertEquals(101L, singleLong("SELECT block_height FROM observed_transactions"))
        assertEquals(1, tableCount("outbox_events"))

        postObservedEvent(address = "0xobserved-block-correction", confirmations = 1, blockHeight = 90)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("NO_CHANGE"))

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
    fun `a value that fails its check is reported under its own field`() {
        createWatchedAddress(address = "0xobserved-field-names")

        listOf(
            observedEventBody(address = "0xobserved-field-names", amount = "-1.00") to
                "amount: amount must be a non-negative decimal string that fits numeric(38,18)",
            observedEventBody(address = "0xobserved-field-names", direction = "SIDEWAYS") to "direction: direction must be INBOUND or OUTBOUND",
            observedEventBody(address = "0xobserved-field-names", statusValue = "PENDING") to "status: status must be SEEN, CONFIRMED, or REVERTED",
        ).forEach { (body, error) ->
            mockMvc.perform(post("/api/v1/observed-events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0]").value(error))
                .andExpect(jsonPath("$.detail").value(error))
        }
    }

    @Test
    fun `a number with a fraction is refused where an integer is expected, in requests and in bridge pages`() {
        createWatchedAddress(address = "0xobserved-fraction")

        listOf("eventIndex" to 1.9, "blockHeight" to 10.7, "confirmations" to 2.5).forEach { (field, value) ->
            val body = observedEventPayload(address = "0xobserved-fraction").apply { this[field] = value }
            mockMvc.perform(post("/api/v1/observed-events").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/invalid-request"))
        }
        assertEquals(0, tableCount("observed_transactions"))

        // The HTTP bridge adapter reads its pages with this mapper too.
        val page = """{"hasMore":false,"events":[{"txHash":"0x1","eventIndex":1.5,"address":"0xa","asset":"USDC","amount":"1",""" +
            """"blockHeight":1,"confirmations":1,"direction":"INBOUND","status":"SEEN"}]}"""
        assertThrows<MismatchedInputException> { objectMapper.readValue(page, ProviderEventsPageResponse::class.java) }
    }

    @Test
    fun `omitted required numeric fields return bad request`() {
        createWatchedAddress(address = "0xobserved-omitted-numeric")

        listOf("eventIndex", "blockHeight", "confirmations").forEach { field ->
            val body = observedEventPayload(address = "0xobserved-omitted-numeric")
                .apply { remove(field) }

            mockMvc.perform(
                post("/api/v1/observed-events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/validation-failed"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("$field:")))
        }

        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
    }

    @Test
    fun `explicit null numeric fields return bad request`() {
        createWatchedAddress(address = "0xobserved-null-numeric")

        listOf("eventIndex", "blockHeight", "confirmations").forEach { field ->
            val body = observedEventPayload(address = "0xobserved-null-numeric")
                .apply { this[field] = null }

            mockMvc.perform(
                post("/api/v1/observed-events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/validation-failed"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("$field:")))
        }

        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
    }

    @Test
    fun `zero numeric fields remain valid`() {
        createWatchedAddress(address = "0xobserved-zero-numeric")

        postObservedEvent(
            address = "0xobserved-zero-numeric",
            eventIndex = 0,
            blockHeight = 0,
            confirmations = 0,
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.result").value("CREATED"))

        assertEquals(0, singleInt("SELECT event_index FROM observed_transactions"))
        assertEquals(0L, singleLong("SELECT block_height FROM observed_transactions"))
        assertEquals(0, singleInt("SELECT confirmations FROM observed_transactions"))
    }

    @Test
    fun `oversized observed event string returns bad request before persistence`() {
        createWatchedAddress(address = "0xobserved-long-string")

        mockMvc.perform(
            post("/api/v1/observed-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    observedEventBody(
                        txHash = "x".repeat(MAX_TX_HASH_LENGTH + 1),
                        address = "0xobserved-long-string",
                    ),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/validation-failed"))

        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))
    }

    @Test
    fun `an oversized amount gets only its length error`() {
        postObservedEvent(address = "0xabc123", amount = "1".repeat(MAX_JSON_STRING_LENGTH))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/validation-failed"))
            .andExpect(jsonPath("$.errors.length()").value(1))
            .andExpect(jsonPath("$.errors[0]").value("amount: amount must be at most $MAX_AMOUNT_LENGTH characters"))
    }

    @Test
    fun `a string past the json limit is refused while the body is read`() {
        postObservedEvent(address = "0xabc123", txHash = "x".repeat(MAX_JSON_STRING_LENGTH + 1))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/invalid-request"))
            .andExpect(
                jsonPath("$.detail").value(
                    "Request body exceeds a JSON size limit, such as a string over $MAX_JSON_STRING_LENGTH characters or a number over 1000 digits.",
                ),
            )

        assertEquals(0, tableCount("observed_transactions"))
    }

    @Test
    fun `an unknown field is ignored at any length and position`() {
        createWatchedAddress(address = "0xobserved-unknown-field")
        val body = linkedMapOf<String, Any?>("note" to "x".repeat(MAX_JSON_STRING_LENGTH * 2)) +
            observedEventPayload(address = "0xobserved-unknown-field")

        mockMvc.perform(
            post("/api/v1/observed-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)

        assertEquals(1, tableCount("observed_transactions"))
    }

    @Test
    fun `observed event tx hash length boundary is accepted`() {
        createWatchedAddress(address = "0xobserved-txhash-boundary")

        postObservedEvent(
            txHash = "x".repeat(MAX_TX_HASH_LENGTH),
            address = "0xobserved-txhash-boundary",
        )
            .andExpect(status().isCreated)

        assertEquals("x".repeat(MAX_TX_HASH_LENGTH), singleString("SELECT tx_hash FROM observed_transactions"))
    }

    @Test
    fun `amount integer digit boundary matches numeric precision`() {
        createWatchedAddress(address = "0xobserved-amount-boundary")

        postObservedEvent(
            txHash = "0xamount-max",
            address = "0xobserved-amount-boundary",
            amount = "99999999999999999999.123456789012345678",
        )
            .andExpect(status().isCreated)

        postObservedEvent(
            txHash = "0xamount-too-large",
            address = "0xobserved-amount-boundary",
            amount = "100000000000000000000",
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/validation-failed"))

        assertEquals(1, tableCount("observed_transactions"))
        assertEquals(1, tableCount("outbox_events"))
    }

    @Test
    fun `extreme exponent amounts are rejected while ordinary exponent notation still fits`() {
        createWatchedAddress(address = "0xobserved-amount-exponent")

        // precision - scale overflowed Int for these, so they used to be stored as a CONFIRMED zero.
        listOf("1e2147483647", "1e2147483648", "1e-2147483647").forEach { amount ->
            postObservedEvent(txHash = "0xamount-exponent", address = "0xobserved-amount-exponent", amount = amount)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/validation-failed"))
        }
        assertEquals(0, tableCount("observed_transactions"))
        assertEquals(0, tableCount("outbox_events"))

        postObservedEvent(txHash = "0xamount-exponent", address = "0xobserved-amount-exponent", amount = "1e2")
            .andExpect(status().isCreated)

        assertEquals("100.000000000000000000", singleString("SELECT amount::text FROM observed_transactions"))
        assertEquals("100.000000000000000000", singleString("SELECT payload ->> 'amount' FROM outbox_events"))
    }

    @Test
    fun `transaction hashes must be well formed for their chain`() {
        val sepoliaAddress = "0xAbC0000000000000000000000000000000000001"
        createWatchedAddress(address = sepoliaAddress, chainId = "eth-sepolia")
        createWatchedAddress(address = "0xobserved-txhash-format")

        postObservedEvent(chainId = "eth-sepolia", txHash = "0xe2e-tx", address = sepoliaAddress)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/invalid-request"))
            .andExpect(jsonPath("$.detail").value("txHash must be 0x followed by 64 hex digits on eth-sepolia."))
        listOf("0xhash:0", "0xhash/0", "0xhash 0", "0xhash\n0").forEach { txHash ->
            postObservedEvent(txHash = txHash, address = "0xobserved-txhash-format")
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/invalid-request"))
        }
        assertEquals(0, tableCount("observed_transactions"))

        val sepoliaTxHash = "0x" + "AB".repeat(32)
        postObservedEvent(chainId = "eth-sepolia", txHash = sepoliaTxHash, address = sepoliaAddress)
            .andExpect(status().isCreated)
        assertEquals(sepoliaTxHash.lowercase(), singleString("SELECT tx_hash FROM observed_transactions"))
    }

    @Test
    fun `identities that differ only around a colon each get their own outbox event`() {
        // Only a chain outside the EVM identity rules still accepts ':'. The outbox key used to join
        // the natural key with ':', so these two transactions shared a key and the second event was lost.
        insertEnabledChainWithUsdc("colon-chain")
        createWatchedAddress(address = "0xcollision", chainId = "colon-chain")
        createWatchedAddress(address = "0:0xcollision", chainId = "colon-chain")

        postObservedEvent(chainId = "colon-chain", txHash = "0xhash:0", address = "0xcollision")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.outboxEvents[0]").value("TRANSACTION_SEEN"))
        postObservedEvent(chainId = "colon-chain", txHash = "0xhash", address = "0:0xcollision")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.outboxEvents[0]").value("TRANSACTION_SEEN"))

        assertEquals(2, tableCount("observed_transactions"))
        assertEquals(2, tableCount("outbox_events"))
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

    private fun createWatchedAddress(address: String, asset: String = "USDC", chainId: String = "local-evm"): JsonNode {
        val accountId = createAccount()
        val result = mockMvc.perform(
            post("/api/v1/accounts/$accountId/addresses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "chainId" to chainId,
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
        chainId: String = "local-evm",
    ) =
        mockMvc.perform(
            post("/api/v1/observed-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    observedEventBody(
                        chainId = chainId,
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
            observedEventPayload(
                chainId = chainId,
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
        )

    private fun observedEventPayload(
        chainId: String = "local-evm",
        txHash: String = "0xdeadbeef",
        eventIndex: Int? = 0,
        address: String = "0xabc123",
        asset: String = "USDC",
        amount: String = "12.340000000000000000",
        blockHeight: Long? = 100,
        confirmations: Int? = 1,
        direction: String = "INBOUND",
        statusValue: String = "SEEN",
    ): MutableMap<String, Any?> =
        mutableMapOf(
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

    private fun insertEnabledChainWithUsdc(chainId: String) {
        val now = Timestamp.from(Instant.now())
        jdbcTemplate.update(
            """
            INSERT INTO chain_configs (chain_id, display_name, required_confirmations, enabled, created_at, updated_at)
            VALUES (?, 'Test chain', 3, true, ?, ?)
            """.trimIndent(),
            chainId,
            now,
            now,
        )
        jdbcTemplate.update(
            """
            INSERT INTO asset_configs (
                chain_id, asset, token_standard, contract_address, decimals, display_name, enabled, created_at, updated_at
            )
            VALUES (?, 'USDC', 'ERC20', ?, 6, NULL, true, ?, ?)
            """.trimIndent(),
            chainId,
            "0x" + "c".repeat(40),
            now,
            now,
        )
    }

    private fun cleanDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
        jdbcTemplate.update("DELETE FROM asset_configs WHERE chain_id NOT IN ('local-evm', 'eth-sepolia', 'eth-mainnet')")
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
