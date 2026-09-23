package com.example.assetsync.config

import com.example.assetsync.application.transaction.ObservedTransactionOutboxPayload
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.OutboxEventType
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.model.outboxIdempotencyKey
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

internal const val DEMO_READER_USERNAME = "demo-reader"
internal const val DEMO_OPERATOR_USERNAME = "demo-operator"

/**
 * Seeds a deterministic, idempotent demo dataset under the `demo` profile so every lifecycle stage
 * and failure mode is observable in a running instance: transactions in SEEN/CONFIRMED/REVERTED,
 * outbox rows in NEW/PUBLISHED/FAILED/DEAD, and a stale STARTED sync run for the recovery sweeper to
 * pick up. Every outbox row is a real lifecycle event of its transaction, with the payload, event
 * type, and idempotency key that ingestion would have written, so the published log lines show
 * complete events. Seeded rows record `demo:seed` as their source. Fixed UUIDs + `ON CONFLICT DO
 * NOTHING` make re-runs on restart a no-op. Demo credentials are intentionally well-known and stay in
 * the database the demo ran on; `ProdDemoUserGuard` keeps the prod profile from starting on such a
 * database.
 */
@Component
@Profile("demo")
class DemoDataSeeder(
    private val jdbcTemplate: JdbcTemplate,
    private val userDetailsManager: UserDetailsManager,
    private val passwordEncoder: PasswordEncoder,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(DemoDataSeeder::class.java)

    @Transactional
    override fun run(args: ApplicationArguments?) {
        seedUser(DEMO_READER_USERNAME, "demo-reader-pw", "READ")
        seedUser(DEMO_OPERATOR_USERNAME, "demo-operator-pw", "OPERATOR")
        seedDomain()
        logger.info("demo_data_seeded account={} users=[{}, {}]", ACCOUNT_ID, DEMO_READER_USERNAME, DEMO_OPERATOR_USERNAME)
    }

    private fun seedUser(username: String, password: String, role: String) {
        if (!userDetailsManager.userExists(username)) {
            userDetailsManager.createUser(
                User.withUsername(username).password(passwordEncoder.encode(password)).roles(role).build(),
            )
        }
    }

    private fun seedDomain() {
        val now = Instant.now(clock)
        val ts = now.epoch()

        jdbcTemplate.update(
            """
            INSERT INTO accounts (id, external_ref, status, created_at, updated_at)
            VALUES (?, 'demo-account', 'ACTIVE', ?, ?) ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            ACCOUNT_ID, ts, ts,
        )
        jdbcTemplate.update(
            """
            INSERT INTO watched_addresses (id, account_id, chain_id, address, asset, label, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'demo', 'ACTIVE', ?, ?) ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            WATCHED_ADDRESS_ID, ACCOUNT_ID, CHAIN_ID, ADDRESS, ASSET, ts, ts,
        )

        seedObservedTransaction(TX_SEEN_ID, SEEN_TX_HASH, TransactionStatus.SEEN, confirmations = 1, version = 0, confirmedAt = null, revertedAt = null, ts = ts)
        seedObservedTransaction(TX_CONFIRMED_ID, CONFIRMED_TX_HASH, TransactionStatus.CONFIRMED, confirmations = 6, version = 0, confirmedAt = ts, revertedAt = null, ts = ts)
        // Version 1: the reverted transaction was seen first (version 0), then reverted.
        seedObservedTransaction(TX_REVERTED_ID, REVERTED_TX_HASH, TransactionStatus.REVERTED, confirmations = 2, version = 1, confirmedAt = null, revertedAt = ts, ts = ts)

        seedOutbox(
            id = OUTBOX_NEW_ID, transactionId = TX_SEEN_ID, txHash = SEEN_TX_HASH, eventType = OutboxEventType.TRANSACTION_SEEN,
            status = TransactionStatus.SEEN, confirmations = 1, version = 0,
            outboxStatus = "NEW", attempts = 0, publishedAt = null, lastError = null, now = now,
        )
        seedOutbox(
            id = OUTBOX_PUBLISHED_ID, transactionId = TX_CONFIRMED_ID, txHash = CONFIRMED_TX_HASH, eventType = OutboxEventType.TRANSACTION_CONFIRMED,
            status = TransactionStatus.CONFIRMED, confirmations = 6, version = 0,
            outboxStatus = "PUBLISHED", attempts = 0, publishedAt = now, lastError = null, now = now,
        )
        // The reverted transaction's two events: its first sighting went DEAD, its reversal is retrying.
        seedOutbox(
            id = OUTBOX_DEAD_ID, transactionId = TX_REVERTED_ID, txHash = REVERTED_TX_HASH, eventType = OutboxEventType.TRANSACTION_SEEN,
            status = TransactionStatus.SEEN, confirmations = 1, version = 0,
            outboxStatus = "DEAD", attempts = 10, publishedAt = null, lastError = "demo poison message", now = now,
        )
        seedOutbox(
            id = OUTBOX_FAILED_ID, transactionId = TX_REVERTED_ID, txHash = REVERTED_TX_HASH, eventType = OutboxEventType.TRANSACTION_REVERTED,
            status = TransactionStatus.REVERTED, confirmations = 2, version = 1,
            outboxStatus = "FAILED", attempts = 3, publishedAt = null, lastError = "demo transient failure", now = now,
        )

        // A STARTED run old enough for the recovery sweeper to abandon — demonstrates recovery live.
        val staleStart = now.minus(2, ChronoUnit.HOURS).epoch()
        jdbcTemplate.update(
            """
            INSERT INTO sync_runs (
                id, target_type, target_id, status, started_at, events_seen, events_changed,
                queued_at, attempts, next_attempt_at, created_at, updated_at
            )
            VALUES (?, 'ADDRESS', ?, 'STARTED', ?, 0, 0, ?, 0, ?, ?, ?) ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            STALE_RUN_ID, WATCHED_ADDRESS_ID, staleStart, staleStart, staleStart, staleStart, staleStart,
        )
    }

    private fun seedObservedTransaction(
        id: UUID,
        txHash: String,
        status: TransactionStatus,
        confirmations: Int,
        version: Long,
        confirmedAt: Timestamp?,
        revertedAt: Timestamp?,
        ts: Timestamp,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO observed_transactions (
                id, chain_id, tx_hash, event_index, watched_address_id, address, asset, direction, amount,
                block_height, confirmations, status, first_seen_at, last_seen_at, confirmed_at, reverted_at,
                version, created_at, updated_at, source
            )
            VALUES (?, ?, ?, 0, ?, ?, ?, 'INBOUND', ?::numeric, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
            """.trimIndent(),
            id, CHAIN_ID, txHash, WATCHED_ADDRESS_ID, ADDRESS, ASSET, AMOUNT, BLOCK_HEIGHT, confirmations, status.name,
            ts, ts, confirmedAt, revertedAt, version, ts, ts, SOURCE,
        )
    }

    private fun seedOutbox(
        id: UUID,
        transactionId: UUID,
        txHash: String,
        eventType: OutboxEventType,
        status: TransactionStatus,
        confirmations: Int,
        version: Long,
        outboxStatus: String,
        attempts: Int,
        publishedAt: Instant?,
        lastError: String?,
        now: Instant,
    ) {
        val payload = ObservedTransactionOutboxPayload(
            eventId = id,
            eventType = eventType.name,
            occurredAt = now,
            transactionId = transactionId,
            chainId = CHAIN_ID,
            txHash = txHash,
            eventIndex = 0,
            address = ADDRESS,
            asset = ASSET,
            amount = AMOUNT,
            direction = Direction.INBOUND.name,
            status = status.name,
            confirmations = confirmations,
            blockHeight = BLOCK_HEIGHT,
            source = SOURCE,
        )
        jdbcTemplate.update(
            """
            INSERT INTO outbox_events (
                id, aggregate_type, aggregate_id, event_type, idempotency_key, payload, status, attempts,
                next_attempt_at, published_at, last_error, created_at, updated_at
            )
            VALUES (?, 'OBSERVED_TRANSACTION', ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
            """.trimIndent(),
            id, transactionId, eventType.name, outboxIdempotencyKey(transactionId, status, version),
            objectMapper.writeValueAsString(payload), outboxStatus, attempts,
            now.epoch(), publishedAt?.epoch(), lastError, now.epoch(), now.epoch(),
        )
    }

    private fun Instant.epoch(): Timestamp = Timestamp.from(this)

    private companion object {
        const val CHAIN_ID = "local-evm"
        const val ADDRESS = "0xdemoaddr"
        const val ASSET = "USDC"
        const val AMOUNT = "1.000000000000000000"
        const val BLOCK_HEIGHT = 1_000L
        const val SOURCE = "demo:seed"
        const val SEEN_TX_HASH = "0xdemo-seen"
        const val CONFIRMED_TX_HASH = "0xdemo-confirmed"
        const val REVERTED_TX_HASH = "0xdemo-reverted"

        val ACCOUNT_ID: UUID = UUID.fromString("d0000000-0000-0000-0000-0000000000a1")
        val WATCHED_ADDRESS_ID: UUID = UUID.fromString("d0000000-0000-0000-0000-0000000000b1")
        val TX_SEEN_ID: UUID = UUID.fromString("d0000000-0000-0000-0000-0000000000c1")
        val TX_CONFIRMED_ID: UUID = UUID.fromString("d0000000-0000-0000-0000-0000000000c2")
        val TX_REVERTED_ID: UUID = UUID.fromString("d0000000-0000-0000-0000-0000000000c3")
        val OUTBOX_NEW_ID: UUID = UUID.fromString("d0000000-0000-0000-0000-0000000000e1")
        val OUTBOX_PUBLISHED_ID: UUID = UUID.fromString("d0000000-0000-0000-0000-0000000000e2")
        val OUTBOX_FAILED_ID: UUID = UUID.fromString("d0000000-0000-0000-0000-0000000000e3")
        val OUTBOX_DEAD_ID: UUID = UUID.fromString("d0000000-0000-0000-0000-0000000000e4")
        val STALE_RUN_ID: UUID = UUID.fromString("d0000000-0000-0000-0000-0000000000f1")
    }
}
