package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.outbox.OutboxEvent
import com.example.assetsync.application.outbox.OutboxEventPublisher
import com.example.assetsync.application.outbox.OutboxEventRepository
import com.example.assetsync.application.outbox.OutboxFailedUpdate
import com.example.assetsync.application.outbox.OutboxProcessingResult
import com.example.assetsync.application.outbox.OutboxProcessingService
import com.example.assetsync.application.outbox.OutboxPublishedUpdate
import com.example.assetsync.application.outbox.OutboxRetentionService
import com.example.assetsync.application.outbox.OutboxStatus
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import org.springframework.transaction.support.TransactionTemplate

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "asset-sync.outbox.max-error-length=64",
        "asset-sync.outbox.retry-backoff-base-delay=5s",
        "asset-sync.outbox.retry-backoff-max-delay=1m",
        "asset-sync.outbox.processing-lease=30s",
        "asset-sync.outbox.max-attempts=4",
    ],
)
class OutboxProcessingIntegrationTests(
    @Autowired private val outboxProcessingService: OutboxProcessingService,
    @Autowired private val outboxEventRepository: OutboxEventRepository,
    @Autowired private val outboxRetentionService: OutboxRetentionService,
    @Autowired private val publisher: ControlledOutboxEventPublisher,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val transactionTemplate: TransactionTemplate,
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
    fun `publisher failure at max attempts marks event dead and removes it from backlog`() {
        val eventId = insertOutboxEvent(status = "NEW", attempts = 3)
        publisher.failure = { IllegalStateException("poison") }

        val result = outboxProcessingService.processDueBatch()

        assertEquals(OutboxProcessingResult(claimed = 1, published = 0, failed = 1), result)
        assertEquals("DEAD", singleString("SELECT status FROM outbox_events WHERE id = ?", eventId))
        assertEquals(4, singleInt("SELECT attempts FROM outbox_events WHERE id = ?", eventId))
        assertEquals(0, singleInt("SELECT count(*) FROM outbox_events WHERE status IN ('NEW', 'FAILED')"))

        assertEquals(
            OutboxProcessingResult(claimed = 0, published = 0, failed = 0),
            outboxProcessingService.processDueBatch(),
        )
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
    fun `dead event is not claimed`() {
        val eventId = insertOutboxEvent(status = "DEAD", attempts = 4, lastError = "poison")

        val result = outboxProcessingService.processDueBatch()

        assertEquals(OutboxProcessingResult(claimed = 0, published = 0, failed = 0), result)
        assertTrue(publisher.publishedIds.isEmpty())
        assertEquals("DEAD", singleString("SELECT status FROM outbox_events WHERE id = ?", eventId))
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
    fun `leased event is not claimed by another processor while publish is in flight`() {
        val eventId = insertOutboxEvent(status = "NEW")
        val publishStarted = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        publisher.beforePublish = {
            publishStarted.countDown()
            assertTrue(releasePublish.await(10, TimeUnit.SECONDS))
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val firstProcessor = executor.submit<OutboxProcessingResult> {
                outboxProcessingService.processDueBatch(batchSize = 1)
            }
            assertTrue(publishStarted.await(10, TimeUnit.SECONDS))
            val leasedUntil = singleTimestamp("SELECT next_attempt_at FROM outbox_events WHERE id = ?", eventId).toInstant()
            assertTrue(leasedUntil.isAfter(Instant.now()))

            val secondResult = outboxProcessingService.processDueBatch(batchSize = 1)
            assertEquals(OutboxProcessingResult(claimed = 0, published = 0, failed = 0), secondResult)

            releasePublish.countDown()
            assertEquals(
                OutboxProcessingResult(claimed = 1, published = 1, failed = 0),
                firstProcessor.get(10, TimeUnit.SECONDS),
            )
            assertEquals(listOf(eventId), publisher.publishedIds.toList())
            assertEquals("PUBLISHED", singleString("SELECT status FROM outbox_events WHERE id = ?", eventId))
        } finally {
            releasePublish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stale failure completion cannot overwrite published row from newer lease owner`() {
        val eventId = insertOutboxEvent(status = "NEW")
        val claimAStartedAt = Instant.now()
        val claimedByA = claimOne(
            now = claimAStartedAt,
            leaseUntil = claimAStartedAt.plusSeconds(1),
        )
        val claimBStartedAt = claimedByA.nextAttemptAt.plusMillis(1)
        val claimedByB = claimOne(
            now = claimBStartedAt,
            leaseUntil = claimBStartedAt.plusSeconds(30),
        )

        assertEquals(eventId, claimedByA.id)
        assertEquals(eventId, claimedByB.id)
        assertTrue(
            inTransaction {
                outboxEventRepository.markPublished(
                    OutboxPublishedUpdate(
                        id = eventId,
                        claimedLeaseUntil = claimedByB.nextAttemptAt,
                        publishedAt = claimBStartedAt.plusSeconds(1),
                        updatedAt = claimBStartedAt.plusSeconds(1),
                    ),
                )
            },
        )

        val staleMarked = inTransaction {
            outboxEventRepository.markFailed(
                OutboxFailedUpdate(
                    id = eventId,
                    claimedLeaseUntil = claimedByA.nextAttemptAt,
                    status = OutboxStatus.FAILED,
                    attempts = claimedByA.attempts + 1,
                    lastError = "late publisher error",
                    nextAttemptAt = claimBStartedAt.plusSeconds(60),
                    updatedAt = claimBStartedAt.plusSeconds(2),
                ),
            )
        }

        assertFalse(staleMarked)
        assertEquals("PUBLISHED", singleString("SELECT status FROM outbox_events WHERE id = ?", eventId))
        assertEquals(0, singleInt("SELECT attempts FROM outbox_events WHERE id = ?", eventId))
        assertNull(nullableString("SELECT last_error FROM outbox_events WHERE id = ?", eventId))
        assertNotNull(singleTimestamp("SELECT published_at FROM outbox_events WHERE id = ?", eventId))
    }

    @Test
    fun `stale publish completion cannot overwrite failed row from newer lease owner`() {
        val eventId = insertOutboxEvent(status = "NEW")
        val claimAStartedAt = Instant.now()
        val claimedByA = claimOne(
            now = claimAStartedAt,
            leaseUntil = claimAStartedAt.plusSeconds(1),
        )
        val claimBStartedAt = claimedByA.nextAttemptAt.plusMillis(1)
        val claimedByB = claimOne(
            now = claimBStartedAt,
            leaseUntil = claimBStartedAt.plusSeconds(30),
        )

        assertTrue(
            inTransaction {
                outboxEventRepository.markFailed(
                    OutboxFailedUpdate(
                        id = eventId,
                        claimedLeaseUntil = claimedByB.nextAttemptAt,
                        status = OutboxStatus.FAILED,
                        attempts = claimedByB.attempts + 1,
                        lastError = "new owner failure",
                        nextAttemptAt = claimBStartedAt.plusSeconds(60),
                        updatedAt = claimBStartedAt.plusSeconds(1),
                    ),
                )
            },
        )

        val staleMarked = inTransaction {
            outboxEventRepository.markPublished(
                OutboxPublishedUpdate(
                    id = eventId,
                    claimedLeaseUntil = claimedByA.nextAttemptAt,
                    publishedAt = claimBStartedAt.plusSeconds(2),
                    updatedAt = claimBStartedAt.plusSeconds(2),
                ),
            )
        }

        assertFalse(staleMarked)
        assertEquals("FAILED", singleString("SELECT status FROM outbox_events WHERE id = ?", eventId))
        assertEquals(1, singleInt("SELECT attempts FROM outbox_events WHERE id = ?", eventId))
        assertEquals("new owner failure", singleString("SELECT last_error FROM outbox_events WHERE id = ?", eventId))
        assertNull(nullableString("SELECT published_at FROM outbox_events WHERE id = ?", eventId))
    }

    @Test
    fun `mark published failure affects only that event and does not roll back the batch`() {
        val eventIds = (1..3).map { insertOutboxEvent() }
        val beforeProcessing = Instant.now()
        installPublishMarkFailureTrigger(eventIds[1])

        val result = outboxProcessingService.processDueBatch(batchSize = 3)

        assertEquals(OutboxProcessingResult(claimed = 3, published = 2, failed = 1), result)
        assertEquals(eventIds.toSet(), publisher.publishedIds.toSet())
        assertEquals("PUBLISHED", singleString("SELECT status FROM outbox_events WHERE id = ?", eventIds[0]))
        assertEquals("NEW", singleString("SELECT status FROM outbox_events WHERE id = ?", eventIds[1]))
        assertEquals("PUBLISHED", singleString("SELECT status FROM outbox_events WHERE id = ?", eventIds[2]))
        assertEquals(0, singleInt("SELECT attempts FROM outbox_events WHERE id = ?", eventIds[1]))
        assertNull(nullableString("SELECT last_error FROM outbox_events WHERE id = ?", eventIds[1]))
        assertNull(jdbcTemplate.queryForObject("SELECT published_at FROM outbox_events WHERE id = ?", Timestamp::class.java, eventIds[1]))
        assertTrue(
            singleTimestamp("SELECT next_attempt_at FROM outbox_events WHERE id = ?", eventIds[1])
                .toInstant()
                .isAfter(beforeProcessing),
        )
    }

    @Test
    fun `mark published failure after successful publish does not consume max attempt or dead-letter`() {
        val eventId = insertOutboxEvent(
            status = "FAILED",
            attempts = 3,
            lastError = "previous publish failure",
        )
        val beforeProcessing = Instant.now()
        installPublishMarkFailureTrigger(eventId)

        val result = outboxProcessingService.processDueBatch(batchSize = 1)

        assertEquals(OutboxProcessingResult(claimed = 1, published = 0, failed = 1), result)
        assertEquals(listOf(eventId), publisher.publishedIds.toList())
        assertEquals("FAILED", singleString("SELECT status FROM outbox_events WHERE id = ?", eventId))
        assertEquals(3, singleInt("SELECT attempts FROM outbox_events WHERE id = ?", eventId))
        assertEquals("previous publish failure", singleString("SELECT last_error FROM outbox_events WHERE id = ?", eventId))
        assertNull(jdbcTemplate.queryForObject("SELECT published_at FROM outbox_events WHERE id = ?", Timestamp::class.java, eventId))
        assertTrue(
            singleTimestamp("SELECT next_attempt_at FROM outbox_events WHERE id = ?", eventId)
                .toInstant()
                .isAfter(beforeProcessing),
        )
    }

    @Test
    fun `retention deletes only old published events`() {
        val oldPublished = insertOutboxEvent(
            status = "PUBLISHED",
            publishedAt = Instant.now().minusSeconds(8 * 24 * 60 * 60),
            createdAt = Instant.now().minusSeconds(8 * 24 * 60 * 60),
        )
        val recentPublished = insertOutboxEvent(
            status = "PUBLISHED",
            publishedAt = Instant.now().minusSeconds(24 * 60 * 60),
            createdAt = Instant.now().minusSeconds(24 * 60 * 60),
        )
        val activeNew = insertOutboxEvent(
            status = "NEW",
            createdAt = Instant.now().minusSeconds(8 * 24 * 60 * 60),
        )
        val dead = insertOutboxEvent(
            status = "DEAD",
            attempts = 4,
            lastError = "poison",
            createdAt = Instant.now().minusSeconds(8 * 24 * 60 * 60),
        )

        val deleted = outboxRetentionService.deleteExpiredPublished()

        assertEquals(1, deleted)
        assertEquals(0, singleInt("SELECT count(*) FROM outbox_events WHERE id = ?", oldPublished))
        assertEquals(1, singleInt("SELECT count(*) FROM outbox_events WHERE id = ?", recentPublished))
        assertEquals(1, singleInt("SELECT count(*) FROM outbox_events WHERE id = ?", activeNew))
        assertEquals(1, singleInt("SELECT count(*) FROM outbox_events WHERE id = ?", dead))
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
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_outbox_publish_mark ON outbox_events")
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_outbox_publish_mark()")
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

    private fun claimOne(now: Instant, leaseUntil: Instant): OutboxEvent =
        inTransaction {
            outboxEventRepository.claimDueEvents(limit = 1, now = now, leaseUntil = leaseUntil).single()
        }

    private fun <T> inTransaction(block: () -> T): T =
        requireNotNull(transactionTemplate.execute { block() })

    private fun installPublishMarkFailureTrigger(eventId: UUID) {
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION fail_outbox_publish_mark()
            RETURNS trigger AS $$
            BEGIN
                IF NEW.id = '$eventId'::uuid AND NEW.status = 'PUBLISHED' THEN
                    RAISE EXCEPTION 'forced publish mark failure';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER fail_outbox_publish_mark
            BEFORE UPDATE ON outbox_events
            FOR EACH ROW
            EXECUTE FUNCTION fail_outbox_publish_mark()
            """.trimIndent(),
        )
    }

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

    @Volatile
    var beforePublish: ((OutboxEvent) -> Unit)? = null

    private val barrierThreads = ConcurrentHashMap.newKeySet<Long>()

    override fun publish(event: OutboxEvent) {
        failure?.invoke(event)?.let { throw it }
        awaitBarrierOncePerThread()
        beforePublish?.invoke(event)
        publishedIds.add(event.id)
    }

    fun reset() {
        publishedIds.clear()
        failure = null
        barrier = null
        beforePublish = null
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
