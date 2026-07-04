package com.example.assetsync.config

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

/**
 * Seeds a deterministic, idempotent demo dataset under the `demo` profile so every lifecycle stage
 * and failure mode is observable in a running instance: transactions in SEEN/CONFIRMED/REVERTED,
 * outbox rows in NEW/PUBLISHED/FAILED/DEAD, and a stale STARTED sync run for the recovery sweeper to
 * pick up. Fixed UUIDs + `ON CONFLICT DO NOTHING` make re-runs on restart a no-op. Demo credentials
 * are intentionally well-known and exist only under this profile.
 */
@Component
@Profile("demo")
class DemoDataSeeder(
    private val jdbcTemplate: JdbcTemplate,
    private val userDetailsManager: UserDetailsManager,
    private val passwordEncoder: PasswordEncoder,
    private val clock: Clock,
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(DemoDataSeeder::class.java)

    @Transactional
    override fun run(args: ApplicationArguments?) {
        seedUser("demo-reader", "demo-reader-pw", "READ")
        seedUser("demo-operator", "demo-operator-pw", "OPERATOR")
        seedDomain()
        logger.info("demo_data_seeded account={} users=[demo-reader, demo-operator]", ACCOUNT_ID)
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
            VALUES (?, ?, 'local-evm', '0xdemoaddr', 'USDC', 'demo', 'ACTIVE', ?, ?) ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            WATCHED_ADDRESS_ID, ACCOUNT_ID, ts, ts,
        )

        seedObservedTransaction(TX_SEEN_ID, "0xdemo-seen", "SEEN", confirmations = 1, confirmedAt = null, revertedAt = null, ts = ts)
        seedObservedTransaction(TX_CONFIRMED_ID, "0xdemo-confirmed", "CONFIRMED", confirmations = 6, confirmedAt = ts, revertedAt = null, ts = ts)
        seedObservedTransaction(TX_REVERTED_ID, "0xdemo-reverted", "REVERTED", confirmations = 2, confirmedAt = null, revertedAt = ts, ts = ts)

        seedOutbox(OUTBOX_NEW_ID, TX_SEEN_ID, "demo:new", "NEW", attempts = 0, nextAttemptAt = ts, publishedAt = null, lastError = null)
        seedOutbox(OUTBOX_PUBLISHED_ID, TX_CONFIRMED_ID, "demo:published", "PUBLISHED", attempts = 0, nextAttemptAt = ts, publishedAt = ts, lastError = null)
        seedOutbox(OUTBOX_FAILED_ID, TX_REVERTED_ID, "demo:failed", "FAILED", attempts = 3, nextAttemptAt = ts, publishedAt = null, lastError = "demo transient failure")
        seedOutbox(OUTBOX_DEAD_ID, TX_REVERTED_ID, "demo:dead", "DEAD", attempts = 10, nextAttemptAt = ts, publishedAt = null, lastError = "demo poison message")

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
        status: String,
        confirmations: Int,
        confirmedAt: java.sql.Timestamp?,
        revertedAt: java.sql.Timestamp?,
        ts: java.sql.Timestamp,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO observed_transactions (
                id, chain_id, tx_hash, event_index, watched_address_id, address, asset, direction, amount,
                block_height, confirmations, status, first_seen_at, last_seen_at, confirmed_at, reverted_at,
                version, created_at, updated_at
            )
            VALUES (?, 'local-evm', ?, 0, ?, '0xdemoaddr', 'USDC', 'INBOUND', 1.000000000000000000,
                    1000, ?, ?, ?, ?, ?, ?, 0, ?, ?) ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            id, txHash, WATCHED_ADDRESS_ID, confirmations, status, ts, ts, confirmedAt, revertedAt, ts, ts,
        )
    }

    private fun seedOutbox(
        id: UUID,
        aggregateId: UUID,
        idempotencyKey: String,
        status: String,
        attempts: Int,
        nextAttemptAt: java.sql.Timestamp,
        publishedAt: java.sql.Timestamp?,
        lastError: String?,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO outbox_events (
                id, aggregate_type, aggregate_id, event_type, idempotency_key, payload, status, attempts,
                next_attempt_at, published_at, last_error, created_at, updated_at
            )
            VALUES (?, 'OBSERVED_TRANSACTION', ?, 'TRANSACTION_SEEN', ?, '{"eventType":"TRANSACTION_SEEN"}'::jsonb, ?, ?,
                    ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            id, aggregateId, idempotencyKey, status, attempts, nextAttemptAt, publishedAt, lastError, nextAttemptAt, nextAttemptAt,
        )
    }

    private fun Instant.epoch(): java.sql.Timestamp = java.sql.Timestamp.from(this)

    private companion object {
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
