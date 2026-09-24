package com.example.assetsync.unit

import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.account.WatchedAddressStatus
import com.example.assetsync.application.sync.AccountSyncPass
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The account pass as the run checkpoint carries it from claim to claim: retries keep their
 * counts, and the checkpoint fits the 16 KiB of `run_checkpoint` with every list full.
 */
class AccountSyncPassTests {

    @Test
    fun `an address deferred to a retry is retried by the next claim, and its third failed retry records it`() {
        val stuck = UUID.randomUUID()
        val other = UUID.randomUUID()
        val pass = AccountSyncPass.from(null)
        pass.deferRetry(stuck)
        pass.deferRetry(other)
        assertEquals(emptyList(), pass.dueRetries(), "the claim that deferred them does not retry them")

        val next = AccountSyncPass.from(pass.toCheckpoint())
        assertEquals(listOf(stuck, other), next.dueRetries())
        next.retryFailed(stuck, TIMEOUT)
        next.retryFailed(stuck, TIMEOUT)

        val last = AccountSyncPass.from(next.toCheckpoint())
        assertFalse(last.hasFailures)
        last.retryFailed(stuck, TIMEOUT)

        assertEquals(listOf(other), last.dueRetries())
        assertTrue(last.failureSummary().endsWith("$stuck: still failing after 3 retries: $TIMEOUT"), last.failureSummary())
    }

    @Test
    fun `a retry that syncs pages starts its count over`() {
        val address = UUID.randomUUID()
        val pass = AccountSyncPass.from(null)
        pass.deferRetry(address)
        repeat(2) { pass.retryFailed(address, TIMEOUT) }
        pass.retryProgressed(address)
        repeat(2) { pass.retryFailed(address, TIMEOUT) }

        assertFalse(pass.hasFailures)
        assertTrue(pass.hasPendingRetries)
    }

    @Test
    fun `the retry list is capped`() {
        val pass = AccountSyncPass.from(null)
        repeat(AccountSyncPass.MAX_RETRIES) { assertTrue(pass.deferRetry(UUID.randomUUID())) }

        assertFalse(pass.deferRetry(UUID.randomUUID()))
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
        repeat(AccountSyncPass.MAX_RETRIES) { pass.deferRetry(UUID.randomUUID()) }
        // Quotes and backslashes take two bytes each in JSON, the most a kept character can take.
        repeat(AccountSyncPass.MAX_RECORDED_FAILURES + 1) { pass.recordFailure(UUID.randomUUID(), "\"\\".repeat(100)) }

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
