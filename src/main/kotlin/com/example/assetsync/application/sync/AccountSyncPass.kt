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
 * nor get visited twice. Addresses whose cursor lease was busy are revisited after the scan, and
 * addresses that failed terminally are recorded for the run's final error. Both lists are capped
 * so the checkpoint stays well below the 16 KiB limit of `run_checkpoint`.
 */
internal class AccountSyncPass private constructor(
    private var afterCreatedAt: Instant?,
    private var afterId: UUID?,
    var scanComplete: Boolean,
    private var visited: Int,
    private val revisit: MutableList<UUID>,
    private var failedCount: Int,
    private val failures: MutableList<AddressFailure>,
) {
    private data class AddressFailure(val watchedAddressId: UUID, val error: String)

    val scanAfterCreatedAt: Instant?
        get() = afterCreatedAt

    val scanAfterId: UUID?
        get() = afterId

    val hasFailures: Boolean
        get() = failedCount > 0

    /** The addresses still waiting for a revisit, in the order they were deferred. */
    fun pendingRevisits(): List<UUID> = revisit.toList()

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

    fun recordFailure(watchedAddressId: UUID, error: String) {
        failedCount += 1
        if (failures.size < MAX_RECORDED_FAILURES) {
            failures += AddressFailure(watchedAddressId, error.take(MAX_FAILURE_ERROR_LENGTH))
        }
    }

    /** The run's `last_error` for a pass that finished with terminal address failures. */
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
            put(FAILED_COUNT, failedCount)
            set<JsonNode>(
                FAILURES,
                factory.arrayNode().apply {
                    failures.forEach { failure ->
                        add(
                            factory.objectNode()
                                .put(FAILURE_ADDRESS_ID, failure.watchedAddressId.toString())
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
        const val MAX_RECORDED_FAILURES = 20
        const val MAX_FAILURE_ERROR_LENGTH = 120

        private const val PASS = "accountPass"
        private const val AFTER_CREATED_AT = "afterCreatedAt"
        private const val AFTER_ID = "afterId"
        private const val SCAN_COMPLETE = "scanComplete"
        private const val VISITED = "visited"
        private const val REVISIT = "revisit"
        private const val FAILED_COUNT = "failedCount"
        private const val FAILURES = "failures"
        private const val FAILURE_ADDRESS_ID = "watchedAddressId"
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
                    .mapNotNull { node -> node.textValue()?.let { runCatching { UUID.fromString(it) }.getOrNull() } }
                    .take(MAX_REVISITS)
                    .toMutableList(),
                failedCount = pass.path(FAILED_COUNT).asInt(0),
                failures = pass.path(FAILURES)
                    .mapNotNull { node ->
                        val id = node.path(FAILURE_ADDRESS_ID).textValue()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        id?.let { AddressFailure(it, node.path(FAILURE_ERROR).asText("").take(MAX_FAILURE_ERROR_LENGTH)) }
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
                failedCount = 0,
                failures = mutableListOf(),
            )
    }
}
