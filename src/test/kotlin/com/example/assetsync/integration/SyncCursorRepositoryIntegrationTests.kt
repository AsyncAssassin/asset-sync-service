package com.example.assetsync.integration

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.AdvanceSyncCheckpointCommand
import com.example.assetsync.application.sync.SyncCursorRepository
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SyncCursorRepositoryIntegrationTests(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val syncCursorRepository: SyncCursorRepository,
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
    fun `cursor lease acquire release and expired cleanup are fenced`() {
        val watchedAddressId = insertWatchedAddress()
        val now = Instant.now()

        val ensured = syncCursorRepository.ensureCursor(watchedAddressId = watchedAddressId, now = now)
        assertEquals(watchedAddressId, ensured.watchedAddressId)

        val tokenA = UUID.randomUUID()
        val leaseA = syncCursorRepository.tryAcquireCursorLease(
            watchedAddressId = watchedAddressId,
            lockedBy = "worker-a",
            lockToken = tokenA,
            now = now,
            leaseUntil = now.plusSeconds(30),
        )
        assertNotNull(leaseA)

        val busy = syncCursorRepository.tryAcquireCursorLease(
            watchedAddressId = watchedAddressId,
            lockedBy = "worker-b",
            lockToken = UUID.randomUUID(),
            now = now.plusSeconds(1),
            leaseUntil = now.plusSeconds(31),
        )
        assertNull(busy)
        assertFalse(
            syncCursorRepository.extendCursorLease(
                watchedAddressId = watchedAddressId,
                lockedBy = "worker-b",
                lockToken = UUID.randomUUID(),
                leaseUntil = now.plusSeconds(40),
                updatedAt = now.plusSeconds(2),
            ),
        )
        assertFalse(
            syncCursorRepository.releaseCursorLeaseFenced(
                watchedAddressId = watchedAddressId,
                lockedBy = "worker-b",
                lockToken = UUID.randomUUID(),
                updatedAt = now.plusSeconds(3),
            ),
        )

        assertEquals(1, syncCursorRepository.clearExpiredCursorLeases(now = now.plusSeconds(31), limit = 10))
        val tokenB = UUID.randomUUID()
        val leaseB = syncCursorRepository.tryAcquireCursorLease(
            watchedAddressId = watchedAddressId,
            lockedBy = "worker-b",
            lockToken = tokenB,
            now = now.plusSeconds(32),
            leaseUntil = now.plusSeconds(62),
        )
        assertNotNull(leaseB)
        assertEquals("worker-b", leaseB.lockedBy)
    }

    @Test
    fun `checkpoint advance is version fenced and preserves high water on cursor only pages`() {
        val watchedAddressId = insertWatchedAddress()
        val now = Instant.now()
        syncCursorRepository.ensureCursor(watchedAddressId = watchedAddressId, now = now)
        val token = UUID.randomUUID()
        val lease = requireNotNull(
            syncCursorRepository.tryAcquireCursorLease(
                watchedAddressId = watchedAddressId,
                lockedBy = "worker-a",
                lockToken = token,
                now = now,
                leaseUntil = now.plusSeconds(30),
            ),
        )

        val checkpoint = JsonNodeFactory.instance.objectNode().put("provider", "test")
        val firstAdvance = requireNotNull(
            syncCursorRepository.advanceCheckpointFenced(
                AdvanceSyncCheckpointCommand(
                    watchedAddressId = watchedAddressId,
                    lockedBy = "worker-a",
                    lockToken = token,
                    expectedVersion = lease.cursor.version,
                    leaseCheckedAt = now.plusSeconds(1),
                    providerCursor = "cursor-1",
                    checkpoint = checkpoint,
                    lastProcessedBlockHeight = 100,
                    lastProcessedEventIndex = 7,
                    lastFinalizedBlockHeight = 95,
                    cursorUpdatedAt = now.plusSeconds(1),
                    updatedAt = now.plusSeconds(1),
                ),
            ),
        )
        assertEquals(1, firstAdvance.version)

        val staleTokenAdvance = syncCursorRepository.advanceCheckpointFenced(
            AdvanceSyncCheckpointCommand(
                watchedAddressId = watchedAddressId,
                lockedBy = "worker-a",
                lockToken = UUID.randomUUID(),
                expectedVersion = firstAdvance.version,
                leaseCheckedAt = now.plusSeconds(2),
                providerCursor = "cursor-stale",
                checkpoint = checkpoint,
                lastProcessedBlockHeight = 101,
                lastProcessedEventIndex = 0,
                lastFinalizedBlockHeight = 96,
                cursorUpdatedAt = now.plusSeconds(2),
                updatedAt = now.plusSeconds(2),
            ),
        )
        assertNull(staleTokenAdvance)

        val regressionAdvance = syncCursorRepository.advanceCheckpointFenced(
            AdvanceSyncCheckpointCommand(
                watchedAddressId = watchedAddressId,
                lockedBy = "worker-a",
                lockToken = token,
                expectedVersion = firstAdvance.version,
                leaseCheckedAt = now.plusSeconds(2),
                providerCursor = "cursor-regression",
                checkpoint = checkpoint,
                lastProcessedBlockHeight = 99,
                lastProcessedEventIndex = 100,
                lastFinalizedBlockHeight = 96,
                cursorUpdatedAt = now.plusSeconds(2),
                updatedAt = now.plusSeconds(2),
            ),
        )
        assertNull(regressionAdvance)

        val cursorOnlyAdvance = requireNotNull(
            syncCursorRepository.advanceCheckpointFenced(
                AdvanceSyncCheckpointCommand(
                    watchedAddressId = watchedAddressId,
                    lockedBy = "worker-a",
                    lockToken = token,
                    expectedVersion = firstAdvance.version,
                    leaseCheckedAt = now.plusSeconds(3),
                    providerCursor = "cursor-2",
                    checkpoint = checkpoint,
                    lastProcessedBlockHeight = null,
                    lastProcessedEventIndex = null,
                    lastFinalizedBlockHeight = null,
                    cursorUpdatedAt = now.plusSeconds(3),
                    updatedAt = now.plusSeconds(3),
                ),
            ),
        )
        assertEquals("cursor-2", cursorOnlyAdvance.providerCursor)
        assertEquals(100, cursorOnlyAdvance.lastProcessedBlockHeight)
        assertEquals(7, cursorOnlyAdvance.lastProcessedEventIndex)
        assertEquals(95, cursorOnlyAdvance.lastFinalizedBlockHeight)
        assertEquals(2, cursorOnlyAdvance.version)
    }

    @Test
    fun `expired lease owner cannot advance checkpoint even with matching token and version`() {
        val watchedAddressId = insertWatchedAddress()
        val now = Instant.now()
        syncCursorRepository.ensureCursor(watchedAddressId = watchedAddressId, now = now)
        val token = UUID.randomUUID()
        val lease = requireNotNull(
            syncCursorRepository.tryAcquireCursorLease(
                watchedAddressId = watchedAddressId,
                lockedBy = "worker-a",
                lockToken = token,
                now = now,
                leaseUntil = now.plusSeconds(5),
            ),
        )

        val expiredAdvance = syncCursorRepository.advanceCheckpointFenced(
            AdvanceSyncCheckpointCommand(
                watchedAddressId = watchedAddressId,
                lockedBy = "worker-a",
                lockToken = token,
                expectedVersion = lease.cursor.version,
                leaseCheckedAt = now.plusSeconds(6),
                providerCursor = "cursor-after-expiry",
                checkpoint = JsonNodeFactory.instance.objectNode(),
                lastProcessedBlockHeight = 10,
                lastProcessedEventIndex = 0,
                lastFinalizedBlockHeight = 10,
                cursorUpdatedAt = now.plusSeconds(6),
                updatedAt = now.plusSeconds(6),
            ),
        )

        assertNull(expiredAdvance)
        val stored = requireNotNull(syncCursorRepository.findCursor(watchedAddressId))
        assertNull(stored.providerCursor)
        assertEquals(0, stored.version)
    }

    @Test
    fun `stale owner after expired lease reclaim cannot advance checkpoint`() {
        val watchedAddressId = insertWatchedAddress()
        val now = Instant.now()
        syncCursorRepository.ensureCursor(watchedAddressId = watchedAddressId, now = now)
        val tokenA = UUID.randomUUID()
        val leaseA = requireNotNull(
            syncCursorRepository.tryAcquireCursorLease(
                watchedAddressId = watchedAddressId,
                lockedBy = "worker-a",
                lockToken = tokenA,
                now = now,
                leaseUntil = now.plusSeconds(5),
            ),
        )

        assertEquals(1, syncCursorRepository.clearExpiredCursorLeases(now = now.plusSeconds(6), limit = 10))
        val tokenB = UUID.randomUUID()
        assertNotNull(
            syncCursorRepository.tryAcquireCursorLease(
                watchedAddressId = watchedAddressId,
                lockedBy = "worker-b",
                lockToken = tokenB,
                now = now.plusSeconds(7),
                leaseUntil = now.plusSeconds(37),
            ),
        )

        val staleAdvance = syncCursorRepository.advanceCheckpointFenced(
            AdvanceSyncCheckpointCommand(
                watchedAddressId = watchedAddressId,
                lockedBy = "worker-a",
                lockToken = tokenA,
                expectedVersion = leaseA.cursor.version,
                leaseCheckedAt = now.plusSeconds(8),
                providerCursor = "cursor-stale-owner",
                checkpoint = JsonNodeFactory.instance.objectNode(),
                lastProcessedBlockHeight = 10,
                lastProcessedEventIndex = 0,
                lastFinalizedBlockHeight = 10,
                cursorUpdatedAt = now.plusSeconds(8),
                updatedAt = now.plusSeconds(8),
            ),
        )

        assertNull(staleAdvance)
        val stored = requireNotNull(syncCursorRepository.findCursor(watchedAddressId))
        assertNull(stored.providerCursor)
        assertEquals("worker-b", stored.lockedBy)
        assertEquals(tokenB, stored.lockToken)
        assertEquals(0, stored.version)
    }

    private fun insertWatchedAddress(): UUID {
        val accountId = UUID.randomUUID()
        val watchedAddressId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        jdbcTemplate.update(
            """
            INSERT INTO accounts (id, external_ref, status, created_at, updated_at)
            VALUES (?, ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            accountId,
            "cursor-account-$accountId",
            now,
            now,
        )
        jdbcTemplate.update(
            """
            INSERT INTO watched_addresses (
                id, account_id, chain_id, address, asset, label, status, created_at, updated_at
            )
            VALUES (?, ?, 'local-evm', ?, 'USDC', NULL, 'ACTIVE', ?, ?)
            """.trimIndent(),
            watchedAddressId,
            accountId,
            "0xcursor-${watchedAddressId.toString().replace("-", "")}",
            now,
            now,
        )
        return watchedAddressId
    }

    private fun cleanDatabase() {
        jdbcTemplate.update("DELETE FROM sync_cursors")
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
    }
}
