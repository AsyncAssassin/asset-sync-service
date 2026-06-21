package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.outbox.OutboxEvent
import com.example.assetsync.application.outbox.OutboxEventPublisher
import com.example.assetsync.application.outbox.OutboxProcessingResult
import com.example.assetsync.application.outbox.OutboxProcessingService
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "asset-sync.outbox.max-error-length=64",
        "asset-sync.outbox.retry-backoff-base-delay=5s",
    ],
)
class OutboxProcessingIntegrationTests(
    @Autowired private val outboxProcessingService: OutboxProcessingService,
    @Autowired private val publisher: ControlledOutboxEventPublisher,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @BeforeEach
    fun cleanBeforeEach() {
        publisher.reset()
        cleanDatabase()
    }

    @AfterEach
    fun cleanAfterEach() {
        publisher.reset()
        cleanDatabase()
    }

    @Test
    fun `due NEW event is published and marked PUBLISHED`() {
        val eventId = insertOutboxEvent(status = "NEW")

        val result = outboxProcessingService.processDueBatch()

        assertEquals(OutboxProcessingResult(claimed = 1, published = 1, failed = 0), result)
        assertEquals(listOf(eventId), publisher.publishedIds.toList())
        assertEquals("PUBLISHED", singleString("SELECT status FROM outbox_events WHERE id = ?", eventId))
        assertNotNull(singleTimestamp("SELECT published_at FROM outbox_events WHERE id = ?", eventId))
        assertNull(nullableString("SELECT last_error FROM outbox_events WHERE id = ?", eventId))
    }

    @Test
    fun `due FAILED event is retried and marked PUBLISHED on success`() {
        val eventId = insertOutboxEvent(
            status = "FAILED",
            attempts = 2,
            lastError = "previous failure",
        )

        val result = outboxProcessingService.processDueBatch()

        assertEquals(OutboxProcessingResult(claimed = 1, published = 1, failed = 0), result)
        assertEquals(listOf(eventId), publisher.publishedIds.toList())
        assertEquals("PUBLISHED", singleString("SELECT status FROM outbox_events WHERE id = ?", eventId))
        assertEquals(2, singleInt("SELECT attempts FROM outbox_events WHERE id = ?", eventId))
        assertNull(nullableString("SELECT last_error FROM outbox_events WHERE id = ?", eventId))
    }

    @Test
    fun `publisher failure increments attempts stores bounded error and schedules retry`() {
        val eventId = insertOutboxEvent(status = "NEW", attempts = 2)
        publisher.failure = { IllegalStateException("x".repeat(200)) }
        val beforeProcessing = Instant.now()

        val result = outboxProcessingService.processDueBatch()

        assertEquals(OutboxProcessingResult(claimed = 1, published = 0, failed = 1), result)
        assertTrue(publisher.publishedIds.isEmpty())
        assertEquals("FAILED", singleString("SELECT status FROM outbox_events WHERE id = ?", eventId))
        assertEquals(3, singleInt("SELECT attempts FROM outbox_events WHERE id = ?", eventId))
        val lastError = singleString("SELECT last_error FROM outbox_events WHERE id = ?", eventId)
        assertTrue(lastError.startsWith("IllegalStateException: "))
        assertTrue(lastError.length <= 64)
        val nextAttemptAt = singleTimestamp("SELECT next_attempt_at FROM outbox_events WHERE id = ?", eventId).toInstant()
        assertTrue(nextAttemptAt.isAfter(beforeProcessing))
    }

    @Test
    fun `event with future next_attempt_at is not claimed`() {
        val eventId = insertOutboxEvent(
            status = "NEW",
            nextAttemptAt = Instant.now().plusSeconds(3600),
        )

        val result = outboxProcessingService.processDueBatch()

        assertEquals(OutboxProcessingResult(claimed = 0, published = 0, failed = 0), result)
        assertTrue(publisher.publishedIds.isEmpty())
        assertEquals("NEW", singleString("SELECT status FROM outbox_events WHERE id = ?", eventId))
    }

    @Test
    fun `already PUBLISHED event is not claimed`() {
        val eventId = insertOutboxEvent(
            status = "PUBLISHED",
            publishedAt = Instant.now().minusSeconds(5),
        )

        val result = outboxProcessingService.processDueBatch()

        assertEquals(OutboxProcessingResult(claimed = 0, published = 0, failed = 0), result)
        assertTrue(publisher.publishedIds.isEmpty())
        assertEquals("PUBLISHED", singleString("SELECT status FROM outbox_events WHERE id = ?", eventId))
    }

    @Test
    fun `two concurrent processors claim disjoint rows with skip locked`() {
        val eventIds = (1..4).map { insertOutboxEvent() }
        publisher.barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = (1..2).map {
                executor.submit<OutboxProcessingResult> {
                    outboxProcessingService.processDueBatch(batchSize = 2)
                }
            }

            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(
                listOf(
                    OutboxProcessingResult(claimed = 2, published = 2, failed = 0),
                    OutboxProcessingResult(claimed = 2, published = 2, failed = 0),
                ),
                results,
            )
            assertEquals(eventIds.toSet(), publisher.publishedIds.toSet())
            assertEquals(4, singleInt("SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED'"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `processing an empty batch is a no-op`() {
        val result = outboxProcessingService.processDueBatch()

        assertEquals(OutboxProcessingResult(claimed = 0, published = 0, failed = 0), result)
        assertTrue(publisher.publishedIds.isEmpty())
    }

    private fun insertOutboxEvent(
        id: UUID = UUID.randomUUID(),
        status: String = "NEW",
        attempts: Int = 0,
        nextAttemptAt: Instant = Instant.now().minusSeconds(1),
        publishedAt: Instant? = null,
        lastError: String? = null,
        createdAt: Instant = Instant.now(),
    ): UUID {
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
                published_at,
                last_error,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            "OBSERVED_TRANSACTION",
            UUID.randomUUID(),
            "TRANSACTION_SEEN",
            "test-outbox:$id",
            payloadJson(id),
            status,
            attempts,
            Timestamp.from(nextAttemptAt),
            publishedAt?.let(Timestamp::from),
            lastError,
            Timestamp.from(createdAt),
            Timestamp.from(createdAt),
        )
        return id
    }

    private fun payloadJson(eventId: UUID): String =
        objectMapper.writeValueAsString(
            mapOf(
                "eventId" to eventId,
                "eventType" to "TRANSACTION_SEEN",
                "transactionId" to UUID.randomUUID(),
                "status" to "SEEN",
            ),
        )

    private fun cleanDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_events")
    }

    private fun singleString(sql: String, vararg args: Any): String =
        requireNotNull(jdbcTemplate.queryForObject(sql, String::class.java, *args))

    private fun nullableString(sql: String, vararg args: Any): String? =
        jdbcTemplate.queryForObject(sql, String::class.java, *args)

    private fun singleInt(sql: String, vararg args: Any): Int =
        requireNotNull(jdbcTemplate.queryForObject(sql, Int::class.java, *args))

    private fun singleTimestamp(sql: String, vararg args: Any): Timestamp =
        requireNotNull(jdbcTemplate.queryForObject(sql, Timestamp::class.java, *args))

    @TestConfiguration
    class OutboxPublisherTestConfiguration {

        @Bean
        @Primary
        fun controlledOutboxEventPublisher(): ControlledOutboxEventPublisher =
            ControlledOutboxEventPublisher()
    }
}

class ControlledOutboxEventPublisher : OutboxEventPublisher {
    val publishedIds: MutableList<UUID> = CopyOnWriteArrayList()

    @Volatile
    var failure: ((OutboxEvent) -> RuntimeException)? = null

    @Volatile
    var barrier: CyclicBarrier? = null

    private val barrierThreads = ConcurrentHashMap.newKeySet<Long>()

    override fun publish(event: OutboxEvent) {
        failure?.invoke(event)?.let { throw it }
        awaitBarrierOncePerThread()
        publishedIds.add(event.id)
    }

    fun reset() {
        publishedIds.clear()
        failure = null
        barrier = null
        barrierThreads.clear()
    }

    private fun awaitBarrierOncePerThread() {
        val currentBarrier = barrier ?: return
        if (!barrierThreads.add(Thread.currentThread().threadId())) {
            return
        }

        currentBarrier.await(10, TimeUnit.SECONDS)
    }
}
