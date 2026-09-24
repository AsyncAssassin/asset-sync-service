package com.example.assetsync.application.sync

import com.example.assetsync.application.account.WatchedAddress
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/**
 * Progress of one account-sync pass over the account's active watched addresses, carried in
 * `sync_runs.run_checkpoint` from claim to claim. The pass scans addresses in `(created_at, id)`
 * order after a keyset, so addresses registered or disabled between claims neither shift the scan
 * nor get visited twice. Addresses whose cursor lease was busy are revisited after the scan,
 * addresses whose sync failed retryably wait for a retry on their own schedule, and addresses that
 * failed for good are recorded for the run's final error. The lists are capped and the recorded
 * errors are kept to printable ASCII, so the checkpoint stays below the 16 KiB limit of
 * `run_checkpoint` whatever an error quotes.
 */
internal class AccountSyncPass private constructor(
    private var afterCreatedAt: Instant?,
    private var afterId: UUID?,
    var scanComplete: Boolean,
    private var visited: Int,
    private val revisit: MutableList<UUID>,
    private val retries: MutableList<AddressRetry>,
    private var lastRetryError: String?,
    private var failedCount: Int,
    private val failures: MutableList<AddressFailure>,
) {
    private data class AddressFailure(val watchedAddressId: UUID, val error: String)

    /** An address waiting to retry a retryable failure: its failed attempts so far, and when it is due. */
    private class AddressRetry(val watchedAddressId: UUID, var failedAttempts: Int, var retryAt: Instant)

    val scanAfterCreatedAt: Instant?
        get() = afterCreatedAt

    val scanAfterId: UUID?
        get() = afterId

    val hasFailures: Boolean
        get() = failedCount > 0

    val hasPendingRetries: Boolean
        get() = retries.isNotEmpty()

    /** The addresses still waiting for a revisit, in the order they were deferred. */
    fun pendingRevisits(): List<UUID> = revisit.toList()

    /** The addresses whose retry is due at [now], in the order they were deferred. */
    fun dueRetries(now: Instant): List<UUID> = retries.filterNot { it.retryAt.isAfter(now) }.map { it.watchedAddressId }

    /** When the first waiting address is due, or null when none waits. */
    fun nextRetryAt(): Instant? = retries.minOfOrNull { it.retryAt }

    /** Moves the scan past [address] once it has been synced, deferred, or recorded as failed. */
    fun advancePast(address: WatchedAddress) {
        afterCreatedAt = address.createdAt
        afterId = address.id
        visited += 1
    }

    /** Moves the scan past [address] without counting it: it left the pass before it was synced. */
    fun skipPast(address: WatchedAddress) {
        afterCreatedAt = address.createdAt
        afterId = address.id
    }

    /** Defers a busy address to the revisit phase; false once the revisit list is full. */
    fun deferBusy(watchedAddressId: UUID): Boolean {
        if (revisit.size >= MAX_REVISITS) {
            return false
        }
        revisit += watchedAddressId
        return true
    }

    fun revisitDone(watchedAddressId: UUID) {
        revisit.remove(watchedAddressId)
    }

    /**
     * Puts an address whose sync failed retryably on the retry list, due at once; the failure
     * itself counts only once the claim settles it ([countFailedAttempt]). False once the list is
     * full.
     */
    fun deferRetry(watchedAddressId: UUID, now: Instant): Boolean {
        if (retries.none { it.watchedAddressId == watchedAddressId }) {
            if (retries.size >= MAX_RETRIES) {
                return false
            }
            retries += AddressRetry(watchedAddressId, failedAttempts = 0, retryAt = now)
        }
        return true
    }

    /** Takes an address off the retry list: it synced, failed for good, or left the pass. */
    fun retryDone(watchedAddressId: UUID) {
        retries.removeIf { it.watchedAddressId == watchedAddressId }
    }

    /** The address committed pages: it is making progress, so its failed attempts start over. */
    fun retryProgressed(watchedAddressId: UUID, now: Instant) {
        retries.find { it.watchedAddressId == watchedAddressId }?.let { retry ->
            retry.failedAttempts = 0
            retry.retryAt = now
        }
    }

    /**
     * Counts a failed attempt of an address on the retry list. The [maxAttempts]th records it as
     * failed with [error]; until then it is due again at [retryAt] of its failed attempts so far.
     */
    fun countFailedAttempt(watchedAddressId: UUID, error: String, maxAttempts: Int, retryAt: (failedAttempts: Int) -> Instant) {
        val retry = retries.find { it.watchedAddressId == watchedAddressId } ?: return
        retry.failedAttempts += 1
        lastRetryError = clip(error)
        if (retry.failedAttempts >= maxAttempts) {
            retries.remove(retry)
            recordFailure(watchedAddressId, "failed ${retry.failedAttempts} times: $error")
        } else {
            retry.retryAt = retryAt(retry.failedAttempts)
        }
    }

    fun recordFailure(watchedAddressId: UUID, error: String) {
        failedCount += 1
        if (failures.size < MAX_RECORDED_FAILURES) {
            failures += AddressFailure(watchedAddressId, clip(error))
        }
    }

    /** The run's `last_error` while addresses wait for a retry, or null when none waits. */
    fun retrySummary(): String? {
        if (retries.isEmpty()) {
            return null
        }
        val waiting = if (retries.size == 1) "1 address waits" else "${retries.size} addresses wait"
        return "$waiting for a retry after a retryable provider failure" + (lastRetryError?.let { ", the last: $it" } ?: ".")
    }

    /** The run's `last_error` for a pass that finished with failed addresses. */
    fun failureSummary(): String {
        val listed = failures.joinToString("; ") { "${it.watchedAddressId}: ${it.error}" }
        val unlisted = failedCount - failures.size
        val more = if (unlisted > 0) "; and $unlisted more" else ""
        return "$failedCount of $visited addresses failed terminally: $listed$more"
    }

    fun toCheckpoint(): ObjectNode {
        val factory = JsonNodeFactory.instance
        val pass = factory.objectNode().apply {
            afterCreatedAt?.let { put(AFTER_CREATED_AT, it.toString()) }
            afterId?.let { put(AFTER_ID, it.toString()) }
            put(SCAN_COMPLETE, scanComplete)
            put(VISITED, visited)
            set<JsonNode>(REVISIT, factory.arrayNode().apply { revisit.forEach { add(it.toString()) } })
            set<JsonNode>(
                RETRIES,
                factory.arrayNode().apply {
                    retries.forEach { retry ->
                        add(
                            factory.objectNode()
                                .put(ADDRESS_ID, retry.watchedAddressId.toString())
                                .put(FAILED_ATTEMPTS, retry.failedAttempts)
                                .put(RETRY_AT, retry.retryAt.epochSecond),
                        )
                    }
                },
            )
            lastRetryError?.let { put(LAST_RETRY_ERROR, it) }
            put(FAILED_COUNT, failedCount)
            set<JsonNode>(
                FAILURES,
                factory.arrayNode().apply {
                    failures.forEach { failure ->
                        add(
                            factory.objectNode()
                                .put(ADDRESS_ID, failure.watchedAddressId.toString())
                                .put(FAILURE_ERROR, failure.error),
                        )
                    }
                },
            )
        }
        return factory.objectNode().set(PASS, pass)
    }

    companion object {
        const val MAX_REVISITS = 100
        const val MAX_RETRIES = 50
        const val MAX_RECORDED_FAILURES = 10
        const val MAX_FAILURE_ERROR_LENGTH = 120

        private const val PASS = "accountPass"
        private const val AFTER_CREATED_AT = "afterCreatedAt"
        private const val AFTER_ID = "afterId"
        private const val SCAN_COMPLETE = "scanComplete"
        private const val VISITED = "visited"
        private const val REVISIT = "revisit"
        private const val RETRIES = "retries"
        private const val FAILED_ATTEMPTS = "failedAttempts"
        private const val RETRY_AT = "retryAt"
        private const val LAST_RETRY_ERROR = "lastRetryError"
        private const val FAILED_COUNT = "failedCount"
        private const val FAILURES = "failures"
        private const val ADDRESS_ID = "watchedAddressId"
        private const val FAILURE_ERROR = "error"

        /**
         * Reads the pass from a run checkpoint. A run without one, including a run queued by an
         * earlier version with its offset-based checkpoint, starts a fresh pass; the addresses it
         * visits again only replay their idempotent cursors.
         */
        fun from(runCheckpoint: JsonNode?): AccountSyncPass {
            val pass = runCheckpoint?.get(PASS)?.takeIf { it.isObject } ?: return fresh()
            val afterCreatedAt = pass.path(AFTER_CREATED_AT).textValue()?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val afterId = pass.path(AFTER_ID).textValue()?.let(::uuidOrNull)
            if ((afterCreatedAt == null) != (afterId == null)) {
                return fresh()
            }
            return AccountSyncPass(
                afterCreatedAt = afterCreatedAt,
                afterId = afterId,
                scanComplete = pass.path(SCAN_COMPLETE).asBoolean(false),
                visited = pass.path(VISITED).asInt(0),
                revisit = pass.path(REVISIT)
                    .mapNotNull { node -> node.textValue()?.let(::uuidOrNull) }
                    .take(MAX_REVISITS)
                    .toMutableList(),
                retries = pass.path(RETRIES)
                    .mapNotNull { node ->
                        node.path(ADDRESS_ID).textValue()?.let(::uuidOrNull)?.let { id ->
                            AddressRetry(
                                watchedAddressId = id,
                                failedAttempts = node.path(FAILED_ATTEMPTS).asInt(0).coerceAtLeast(0),
                                retryAt = Instant.ofEpochSecond(node.path(RETRY_AT).asLong(0)),
                            )
                        }
                    }
                    .distinctBy { it.watchedAddressId }
                    .take(MAX_RETRIES)
                    .toMutableList(),
                lastRetryError = pass.path(LAST_RETRY_ERROR).textValue()?.let(::clip),
                failedCount = pass.path(FAILED_COUNT).asInt(0),
                failures = pass.path(FAILURES)
                    .mapNotNull { node ->
                        node.path(ADDRESS_ID).textValue()?.let(::uuidOrNull)?.let { id ->
                            AddressFailure(id, clip(node.path(FAILURE_ERROR).asText("")))
                        }
                    }
                    .take(MAX_RECORDED_FAILURES)
                    .toMutableList(),
            )
        }

        private fun fresh(): AccountSyncPass =
            AccountSyncPass(
                afterCreatedAt = null,
                afterId = null,
                scanComplete = false,
                visited = 0,
                revisit = mutableListOf(),
                retries = mutableListOf(),
                lastRetryError = null,
                failedCount = 0,
                failures = mutableListOf(),
            )

        private fun uuidOrNull(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()

        /**
         * An error as the pass keeps it: at most [MAX_FAILURE_ERROR_LENGTH] characters of printable
         * ASCII, anything else as `?`, so each one takes a bounded number of bytes in the checkpoint.
         * The log keeps the error as it was.
         */
        private fun clip(error: String): String =
            error.take(MAX_FAILURE_ERROR_LENGTH).map { if (it in ' '..'~') it else '?' }.joinToString(separator = "")
    }
}
