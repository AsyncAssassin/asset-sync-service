package com.example.assetsync.unit

import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.account.WatchedAddressStatus
import com.example.assetsync.application.sync.AccountSyncPass
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account pass as the run checkpoint carries it from claim to claim: each address waits for
 * its retry on its own schedule, keeps its count across claims, and the checkpoint fits the 16 KiB
 * of `run_checkpoint` with every list full.
 */
class AccountSyncPassTests {

    private val start = Instant.parse("2026-09-24T12:00:00Z")
    private val backoff = { failedAttempts: Int -> start.plus(Duration.ofSeconds(30L * failedAttempts)) }

    @Test
    fun `an address waits for its retry, keeps its count across claims, and its last attempt records it`() {
        val stuck = UUID.randomUUID()
        val pass = AccountSyncPass.from(null)
        pass.deferRetry(stuck, start)
        assertEquals(listOf(stuck), pass.dueRetries(start))

        pass.countFailedAttempt(stuck, TIMEOUT, maxAttempts = 3, retryAt = backoff)
        assertEquals(emptyList(), pass.dueRetries(start.plusSeconds(29)), "it waits for its backoff")
        assertEquals(start.plusSeconds(30), pass.nextRetryAt())

        val next = AccountSyncPass.from(pass.toCheckpoint())
        assertEquals(listOf(stuck), next.dueRetries(start.plusSeconds(30)))
        next.countFailedAttempt(stuck, TIMEOUT, maxAttempts = 3, retryAt = backoff)
        assertFalse(next.hasFailures)
        assertEquals("1 address waits for a retry after a retryable provider failure, the last: $TIMEOUT", next.retrySummary())

        next.countFailedAttempt(stuck, TIMEOUT, maxAttempts = 3, retryAt = backoff)
        assertFalse(next.hasPendingRetries)
        assertNull(next.retrySummary())
        assertTrue(next.failureSummary().endsWith("$stuck: failed 3 times: $TIMEOUT"), next.failureSummary())
    }

    @Test
    fun `a retry that commits pages starts its count over`() {
        val address = UUID.randomUUID()
        val pass = AccountSyncPass.from(null)
        pass.deferRetry(address, start)
        repeat(2) { pass.countFailedAttempt(address, TIMEOUT, maxAttempts = 3, retryAt = backoff) }
        pass.retryProgressed(address, start.plusSeconds(100))
        repeat(2) { pass.countFailedAttempt(address, TIMEOUT, maxAttempts = 3, retryAt = backoff) }

        assertFalse(pass.hasFailures)
        assertTrue(pass.hasPendingRetries)
    }

    @Test
    fun `the retry list is capped`() {
        val pass = AccountSyncPass.from(null)
        repeat(AccountSyncPass.MAX_RETRIES) { assertTrue(pass.deferRetry(UUID.randomUUID(), start)) }

        assertFalse(pass.deferRetry(UUID.randomUUID(), start))
    }

    @Test
    fun `a recorded error keeps up to 120 characters of printable ASCII`() {
        val pass = AccountSyncPass.from(null)
        pass.recordFailure(UUID.randomUUID(), "bad\u0000byte ü" + "x".repeat(200))

        val error = pass.toCheckpoint()["accountPass"]["failures"][0]["error"].asText()
        assertEquals(AccountSyncPass.MAX_FAILURE_ERROR_LENGTH, error.length)
        assertTrue(error.startsWith("bad?byte ?x"), error)
    }

    @Test
    fun `a checkpoint with every list full and the longest errors fits run_checkpoint`() {
        val pass = AccountSyncPass.from(null)
        pass.advancePast(watchedAddress())
        repeat(AccountSyncPass.MAX_REVISITS) { pass.deferBusy(UUID.randomUUID()) }
        // Quotes and backslashes take two bytes each in JSON, the most a kept character can take.
        val longest = "\"\\".repeat(100)
        repeat(AccountSyncPass.MAX_RETRIES) {
            val address = UUID.randomUUID()
            pass.deferRetry(address, start)
            pass.countFailedAttempt(address, longest, maxAttempts = Int.MAX_VALUE, retryAt = { Instant.MAX.minusSeconds(1) })
        }
        repeat(AccountSyncPass.MAX_RECORDED_FAILURES + 1) { pass.recordFailure(UUID.randomUUID(), longest) }

        val compact = pass.toCheckpoint().toString()
        // PostgreSQL prints jsonb with a space after every ':' and ','.
        val jsonbTextBytes = compact.toByteArray().size + compact.count { it == ':' || it == ',' }
        assertTrue(jsonbTextBytes <= 16_384, "run_checkpoint holds at most 16384 bytes; this pass takes $jsonbTextBytes")
    }

    private fun watchedAddress(): WatchedAddress =
        WatchedAddress(
            id = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            chainId = "local-evm",
            address = "0xpass",
            asset = "USDC",
            label = null,
            status = WatchedAddressStatus.ACTIVE,
            createdAt = Instant.parse("2026-09-24T12:34:56.123456Z"),
            updatedAt = Instant.parse("2026-09-24T12:34:56.123456Z"),
        )

    private companion object {
        const val TIMEOUT = "Provider timeout after PT10S."
    }
}
