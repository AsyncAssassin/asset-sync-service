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
 * addresses whose sync failed retryably are retried in later claims, and addresses that failed
 * terminally are recorded for the run's final error. The lists are capped and the recorded errors
 * are kept to printable ASCII, so the checkpoint stays below the 16 KiB limit of `run_checkpoint`
 * whatever an error quotes.
 */
internal class AccountSyncPass private constructor(
    private var afterCreatedAt: Instant?,
    private var afterId: UUID?,
    var scanComplete: Boolean,
    private var visited: Int,
    private val revisit: MutableList<UUID>,
    private val retries: MutableList<AddressRetry>,
    private var failedCount: Int,
    private val failures: MutableList<AddressFailure>,
) {
    private data class AddressFailure(val watchedAddressId: UUID, val error: String)

    /** An address waiting to retry a retryable failure, and how many of its retries failed as well. */
    private class AddressRetry(val watchedAddressId: UUID, var failedRetries: Int)

    /** The addresses this claim deferred to a retry; their first retry belongs to the next claim. */
    private val deferredThisClaim = mutableSetOf<UUID>()

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

    /** The addresses due for a retry in this claim, in the order they were deferred. */
    fun dueRetries(): List<UUID> = retries.map { it.watchedAddressId }.filterNot { it in deferredThisClaim }

    /** Moves the scan past [address] once it has been synced, deferred, or recorded as failed. */
    fun advancePast(address: WatchedAddress) {
        afterCreatedAt = address.createdAt
        afterId = address.id
        visited += 1
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

    /** Defers an address whose sync failed retryably to a retry in a later claim; false once the retry list is full. */
    fun deferRetry(watchedAddressId: UUID): Boolean {
        if (retries.none { it.watchedAddressId == watchedAddressId }) {
            if (retries.size >= MAX_RETRIES) {
                return false
            }
            retries += AddressRetry(watchedAddressId, failedRetries = 0)
        }
        deferredThisClaim += watchedAddressId
        return true
    }

    /** Takes an address off the retry list: it synced, failed terminally, or left the pass. */
    fun retryDone(watchedAddressId: UUID) {
        retries.removeIf { it.watchedAddressId == watchedAddressId }
    }

    /** A retry synced pages but left some for the next claim: its failed retries start over. */
    fun retryProgressed(watchedAddressId: UUID) {
        retries.find { it.watchedAddressId == watchedAddressId }?.failedRetries = 0
    }

    /**
     * Counts a failed retry of an address. The [MAX_FAILED_RETRIES]th takes it off the retry list
     * and records it as failed with [error].
     */
    fun retryFailed(watchedAddressId: UUID, error: String) {
        val retry = retries.find { it.watchedAddressId == watchedAddressId } ?: return
        retry.failedRetries += 1
        if (retry.failedRetries >= MAX_FAILED_RETRIES) {
            retries.remove(retry)
            recordFailure(watchedAddressId, "still failing after ${retry.failedRetries} retries: $error")
        }
    }

    fun recordFailure(watchedAddressId: UUID, error: String) {
        failedCount += 1
        if (failures.size < MAX_RECORDED_FAILURES) {
            failures += AddressFailure(watchedAddressId, clip(error))
        }
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
                                .put(FAILED_RETRIES, retry.failedRetries),
                        )
                    }
                },
            )
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
        const val MAX_FAILED_RETRIES = 3
        const val MAX_RECORDED_FAILURES = 20
        const val MAX_FAILURE_ERROR_LENGTH = 120

        private const val PASS = "accountPass"
        private const val AFTER_CREATED_AT = "afterCreatedAt"
        private const val AFTER_ID = "afterId"
        private const val SCAN_COMPLETE = "scanComplete"
        private const val VISITED = "visited"
        private const val REVISIT = "revisit"
        private const val RETRIES = "retries"
        private const val FAILED_RETRIES = "failedRetries"
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
            val afterId = pass.path(AFTER_ID).textValue()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
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
                // A pass saved by 0.4.1 has no retries: its retryable failures failed the claim.
                retries = pass.path(RETRIES)
                    .mapNotNull { node ->
                        node.path(ADDRESS_ID).textValue()?.let(::uuidOrNull)?.let { id ->
                            AddressRetry(id, node.path(FAILED_RETRIES).asInt(0).coerceIn(0, MAX_FAILED_RETRIES - 1))
                        }
                    }
                    .distinctBy { it.watchedAddressId }
                    .take(MAX_RETRIES)
                    .toMutableList(),
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
