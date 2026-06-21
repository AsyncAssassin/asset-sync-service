package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.outbox.OutboxProcessingService
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.infrastructure.outbox.OutboxPublisherJob
import com.example.assetsync.infrastructure.provider.FakeChainProvider
import com.example.assetsync.infrastructure.provider.FakeChainProviderHealthIndicator
import com.example.assetsync.infrastructure.provider.FakeChainProviderStep
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class, ObservabilityIntegrationTests.ObservabilityTestConfiguration::class)
@SpringBootTest(
    properties = [
        "asset-sync.outbox.max-error-length=64",
        "asset-sync.outbox.retry-backoff-base-delay=5s",
    ],
)
@AutoConfigureMockMvc
class ObservabilityIntegrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val meterRegistry: MeterRegistry,
    @Autowired private val fakeChainProvider: FakeChainProvider,
    @Autowired private val outboxProcessingService: OutboxProcessingService,
    @Autowired private val publisher: ControlledOutboxEventPublisher,
    @Autowired private val applicationContext: ApplicationContext,
) {

    @BeforeEach
    fun cleanBeforeEach() {
        publisher.reset()
        fakeChainProvider.clear()
        cleanDatabase()
    }

    @AfterEach
    fun cleanAfterEach() {
        publisher.reset()
        fakeChainProvider.clear()
        cleanDatabase()
    }

    @Test
    fun `actuator health readiness and metrics endpoints are exposed`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))

        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))

        mockMvc.perform(get("/actuator/metrics"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.names", hasItem("asset.sync.outbox.backlog.total")))

        assertTrue(applicationContext.getBeansOfType(FakeChainProviderHealthIndicator::class.java).isNotEmpty())
        assertTrue(applicationContext.getBeansOfType(OutboxPublisherJob::class.java).isEmpty())
    }

    @Test
    fun `observed event ingestion increments result transition and conflict metrics`() {
        val createdBefore = counterCount(
            "asset.sync.observed.events.ingested",
            "result",
            "CREATED",
            "status",
            "SEEN",
        )
        val transitionBefore = counterCount(
            "asset.sync.observed.transaction.transitions",
            "eventType",
            "TRANSACTION_SEEN",
            "status",
            "SEEN",
        )
        val conflictBefore = counterCount("asset.sync.observed.transaction.immutable.conflicts")
        val conflictIngestedBefore = counterCount(
            "asset.sync.observed.events.ingested",
            "result",
            "CONFLICT",
            "status",
            "SEEN",
        )
        createWatchedAddress(address = "0xmetrics-observed")

        postObservedEvent(address = "0xmetrics-observed")
            .andExpect(status().isCreated)

        postObservedEvent(address = "0xmetrics-observed", amount = "99.000000000000000000")
            .andExpect(status().isConflict)

        assertEquals(
            createdBefore + 1.0,
            counterCount("asset.sync.observed.events.ingested", "result", "CREATED", "status", "SEEN"),
        )
        assertEquals(
            transitionBefore + 1.0,
            counterCount(
                "asset.sync.observed.transaction.transitions",
                "eventType",
                "TRANSACTION_SEEN",
                "status",
                "SEEN",
            ),
        )
        assertEquals(
            conflictIngestedBefore + 1.0,
            counterCount("asset.sync.observed.events.ingested", "result", "CONFLICT", "status", "SEEN"),
        )
        assertEquals(
            conflictBefore + 1.0,
            counterCount("asset.sync.observed.transaction.immutable.conflicts"),
        )
    }

    @Test
    fun `sync success and failure increment sync and provider metrics`() {
        val startedBefore = counterCount("asset.sync.sync.runs", "targetType", "ADDRESS", "status", "STARTED")
        val succeededBefore = counterCount("asset.sync.sync.runs", "targetType", "ADDRESS", "status", "SUCCEEDED")
        val failedBefore = counterCount("asset.sync.sync.runs", "targetType", "ADDRESS", "status", "FAILED")
        val providerAttemptsBefore = counterCount(
            "asset.sync.provider.fetches",
            "targetType",
            "ADDRESS",
            "status",
            "ATTEMPTED",
        )
        val providerSuccessBefore = counterCount(
            "asset.sync.provider.fetches",
            "targetType",
            "ADDRESS",
            "status",
            "SUCCEEDED",
        )
        val providerFailureBefore = counterCount(
            "asset.sync.provider.fetches",
            "targetType",
            "ADDRESS",
            "status",
            "FAILED",
        )
        val providerSuccessTimerBefore = timerCount(
            "asset.sync.provider.fetch.duration",
            "targetType",
            "ADDRESS",
            "status",
            "SUCCEEDED",
        )
        val providerFailureTimerBefore = timerCount(
            "asset.sync.provider.fetch.duration",
            "targetType",
            "ADDRESS",
            "status",
            "FAILED",
        )

        val successAddress = createWatchedAddress(address = "0xmetrics-sync-success")
        val successAddressId = successAddress["id"].asText()
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xmetrics-sync-success",
            asset = "USDC",
            events = listOf(providerEvent(txHash = "0xmetrics-sync-success", address = "0xmetrics-sync-success")),
        )

        mockMvc.perform(post("/api/v1/addresses/$successAddressId/sync"))
            .andExpect(status().isAccepted)

        val failureAddress = createWatchedAddress(address = "0xmetrics-sync-failure")
        val failureAddressId = failureAddress["id"].asText()
        fakeChainProvider.setScript(
            chainId = "local-evm",
            address = "0xmetrics-sync-failure",
            asset = "USDC",
            steps = listOf(FakeChainProviderStep.Failure("Provider timeout")),
        )

        mockMvc.perform(post("/api/v1/addresses/$failureAddressId/sync"))
            .andExpect(status().isServiceUnavailable)

        assertEquals(
            startedBefore + 2.0,
            counterCount("asset.sync.sync.runs", "targetType", "ADDRESS", "status", "STARTED"),
        )
        assertEquals(
            succeededBefore + 1.0,
            counterCount("asset.sync.sync.runs", "targetType", "ADDRESS", "status", "SUCCEEDED"),
        )
        assertEquals(
            failedBefore + 1.0,
            counterCount("asset.sync.sync.runs", "targetType", "ADDRESS", "status", "FAILED"),
        )
        assertEquals(
            providerAttemptsBefore + 2.0,
            counterCount("asset.sync.provider.fetches", "targetType", "ADDRESS", "status", "ATTEMPTED"),
        )
        assertEquals(
            providerSuccessBefore + 1.0,
            counterCount("asset.sync.provider.fetches", "targetType", "ADDRESS", "status", "SUCCEEDED"),
        )
        assertEquals(
            providerFailureBefore + 1.0,
            counterCount("asset.sync.provider.fetches", "targetType", "ADDRESS", "status", "FAILED"),
        )
        assertEquals(
            providerSuccessTimerBefore + 1,
            timerCount("asset.sync.provider.fetch.duration", "targetType", "ADDRESS", "status", "SUCCEEDED"),
        )
        assertEquals(
            providerFailureTimerBefore + 1,
            timerCount("asset.sync.provider.fetch.duration", "targetType", "ADDRESS", "status", "FAILED"),
        )
    }

    @Test
    fun `provider fetch failure metrics are recorded when fetched event processing fails`() {
        val providerAttemptsBefore = counterCount(
            "asset.sync.provider.fetches",
            "targetType",
            "ADDRESS",
            "status",
            "ATTEMPTED",
        )
        val providerFailureBefore = counterCount(
            "asset.sync.provider.fetches",
            "targetType",
            "ADDRESS",
            "status",
            "FAILED",
        )
        val providerFailureTimerBefore = timerCount(
            "asset.sync.provider.fetch.duration",
            "targetType",
            "ADDRESS",
            "status",
            "FAILED",
        )

        val watchedAddress = createWatchedAddress(address = "0xmetrics-processing-failure")
        val addressId = watchedAddress["id"].asText()
        fakeChainProvider.setEvents(
            chainId = "local-evm",
            address = "0xmetrics-processing-failure",
            asset = "USDC",
            events = listOf(
                providerEvent(
                    txHash = "0xmetrics-processing-failure",
                    address = "0xmetrics-processing-mismatch",
                ),
            ),
        )

        mockMvc.perform(post("/api/v1/addresses/$addressId/sync"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Watched address not found"))

        assertEquals(
            providerAttemptsBefore + 1.0,
            counterCount("asset.sync.provider.fetches", "targetType", "ADDRESS", "status", "ATTEMPTED"),
        )
        assertEquals(
            providerFailureBefore + 1.0,
            counterCount("asset.sync.provider.fetches", "targetType", "ADDRESS", "status", "FAILED"),
        )
        assertEquals(
            providerFailureTimerBefore + 1,
            timerCount("asset.sync.provider.fetch.duration", "targetType", "ADDRESS", "status", "FAILED"),
        )
    }

    @Test
    fun `outbox success failure and backlog metrics are recorded`() {
        val publishedBefore = counterCount(
            "asset.sync.outbox.events",
            "eventType",
            "TRANSACTION_SEEN",
            "status",
            "PUBLISHED",
        )
        val failedBefore = counterCount(
            "asset.sync.outbox.events",
            "eventType",
            "TRANSACTION_SEEN",
            "status",
            "FAILED",
        )
        val successfulBatchBefore = counterCount("asset.sync.outbox.batches", "result", "SUCCEEDED")
        val failedBatchBefore = counterCount("asset.sync.outbox.batches", "result", "FAILED")

        assertEquals(0.0, gaugeValue("asset.sync.outbox.backlog.total"))
        insertOutboxEvent()
        assertEquals(1.0, gaugeValue("asset.sync.outbox.backlog.total"))

        outboxProcessingService.processDueBatch()

        assertEquals(0.0, gaugeValue("asset.sync.outbox.backlog.total"))
        assertEquals(
            publishedBefore + 1.0,
            counterCount("asset.sync.outbox.events", "eventType", "TRANSACTION_SEEN", "status", "PUBLISHED"),
        )
        assertEquals(
            successfulBatchBefore + 1.0,
            counterCount("asset.sync.outbox.batches", "result", "SUCCEEDED"),
        )

        publisher.failure = { IllegalStateException("publisher failed") }
        insertOutboxEvent()

        outboxProcessingService.processDueBatch()

        assertEquals(1.0, gaugeValue("asset.sync.outbox.backlog.total"))
        assertEquals(
            failedBefore + 1.0,
            counterCount("asset.sync.outbox.events", "eventType", "TRANSACTION_SEEN", "status", "FAILED"),
        )
        assertEquals(
            failedBatchBefore + 1.0,
            counterCount("asset.sync.outbox.batches", "result", "FAILED"),
        )
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
        address: String,
        txHash: String = "0xmetrics-observed",
        amount: String = "12.340000000000000000",
    ) =
        mockMvc.perform(
            post("/api/v1/observed-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "chainId" to "local-evm",
                            "txHash" to txHash,
                            "eventIndex" to 0,
                            "address" to address,
                            "asset" to "USDC",
                            "amount" to amount,
                            "blockHeight" to 100,
                            "confirmations" to 1,
                            "direction" to "INBOUND",
                            "status" to "SEEN",
                        ),
                    ),
                ),
        )

    private fun providerEvent(
        txHash: String,
        address: String,
        asset: String = "USDC",
    ): ChainProviderObservedEvent =
        ChainProviderObservedEvent(
            chainId = "local-evm",
            txHash = txHash,
            eventIndex = 0,
            address = address,
            asset = asset,
            amount = BigDecimal("12.340000000000000000"),
            blockHeight = 100,
            confirmations = 1,
            direction = Direction.INBOUND,
            status = TransactionStatus.SEEN,
        )

    private fun insertOutboxEvent(id: UUID = UUID.randomUUID()): UUID {
        val now = Instant.now()
        jdbcTemplate.update(
            """
            INSERT INTO outbox_events (
                id,
                aggregate_type,
                aggregate_id,
                event_type,
                idempotency_key,
                payload,
                status,
                attempts,
                next_attempt_at,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?::jsonb, 'NEW', 0, ?, ?, ?)
            """.trimIndent(),
            id,
            "OBSERVED_TRANSACTION",
            UUID.randomUUID(),
            "TRANSACTION_SEEN",
            "observability-test:$id",
            objectMapper.writeValueAsString(
                mapOf(
                    "eventId" to id,
                    "eventType" to "TRANSACTION_SEEN",
                    "transactionId" to UUID.randomUUID(),
                    "status" to "SEEN",
                ),
            ),
            Timestamp.from(now.minusSeconds(1)),
            Timestamp.from(now),
            Timestamp.from(now),
        )
        return id
    }

    private fun counterCount(name: String, vararg tags: String): Double =
        meterRegistry.find(name).tags(*tags).counter()?.count() ?: 0.0

    private fun timerCount(name: String, vararg tags: String): Long =
        meterRegistry.find(name).tags(*tags).timer()?.count() ?: 0L

    private fun gaugeValue(name: String): Double =
        requireNotNull(meterRegistry.find(name).gauge()) { "Gauge $name was not registered." }.value()

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
    class ObservabilityTestConfiguration {

        @Bean
        @Primary
        fun controlledOutboxEventPublisher(): ControlledOutboxEventPublisher =
            ControlledOutboxEventPublisher()
    }
}
