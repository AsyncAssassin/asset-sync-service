package com.example.assetsync

import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class DatabaseMigrationIntegrationTests(
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `liquibase migrations apply and seed local chain config`() {
        val chainConfig = jdbcTemplate.queryForMap(
            """
            SELECT display_name, required_confirmations, enabled
            FROM chain_configs
            WHERE chain_id = ?
            """.trimIndent(),
            "local-evm",
        )

        assertEquals("Local EVM", chainConfig["display_name"])
        assertEquals(3, (chainConfig["required_confirmations"] as Number).toInt())
        assertEquals(true, chainConfig["enabled"])
    }

    @Test
    fun `key database constraints are enforced`() {
        val accountId = insertAccount()
        val watchedAddressId = insertWatchedAddress(accountId, "0x${UUID.randomUUID().toString().replace("-", "")}", "USDC")
        val transactionId = insertObservedTransaction(watchedAddressId)
        val duplicateOutboxKey = "observed-tx:local-evm:${UUID.randomUUID()}:0:address:USDC:status:SEEN"

        assertThrows<DataIntegrityViolationException> {
            insertWatchedAddress(accountId, "0xduplicate-address", "ETH")
            insertWatchedAddress(accountId, "0xduplicate-address", "ETH")
        }

        assertThrows<DataIntegrityViolationException> {
            insertObservedTransaction(watchedAddressId, txHash = "0xduplicate-natural-key")
            insertObservedTransaction(watchedAddressId, txHash = "0xduplicate-natural-key")
        }

        assertThrows<DataIntegrityViolationException> {
            insertObservedTransaction(watchedAddressId, txHash = "0xinvalid-status", status = "PENDING")
        }

        assertThrows<DataIntegrityViolationException> {
            insertObservedTransaction(watchedAddressId, txHash = "0xnegative-confirmations", confirmations = -1)
        }

        assertThrows<DataIntegrityViolationException> {
            insertOutboxEvent(transactionId, duplicateOutboxKey)
            insertOutboxEvent(transactionId, duplicateOutboxKey)
        }
    }

    private fun insertAccount(): UUID {
        val accountId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO accounts (id, external_ref, status, created_at, updated_at)
            VALUES (?, ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            accountId,
            "account-$accountId",
            now(),
            now(),
        )
        return accountId
    }

    private fun insertWatchedAddress(accountId: UUID, address: String, asset: String): UUID {
        val watchedAddressId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO watched_addresses (
                id, account_id, chain_id, address, asset, label, status, created_at, updated_at
            )
            VALUES (?, ?, 'local-evm', ?, ?, NULL, 'ACTIVE', ?, ?)
            """.trimIndent(),
            watchedAddressId,
            accountId,
            address,
            asset,
            now(),
            now(),
        )
        return watchedAddressId
    }

    private fun insertObservedTransaction(
        watchedAddressId: UUID,
        txHash: String = "0x${UUID.randomUUID().toString().replace("-", "")}",
        status: String = "SEEN",
        confirmations: Int = 0,
    ): UUID {
        val transactionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO observed_transactions (
                id,
                chain_id,
                tx_hash,
                event_index,
                watched_address_id,
                address,
                asset,
                direction,
                amount,
                block_height,
                confirmations,
                status,
                first_seen_at,
                last_seen_at,
                created_at,
                updated_at
            )
            VALUES (?, 'local-evm', ?, 0, ?, '0xobserved-address', 'USDC', 'INBOUND', ?, 1, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            transactionId,
            txHash,
            watchedAddressId,
            BigDecimal("1.000000000000000000"),
            confirmations,
            status,
            now(),
            now(),
            now(),
            now(),
        )
        return transactionId
    }

    private fun insertOutboxEvent(transactionId: UUID, idempotencyKey: String): UUID {
        val outboxEventId = UUID.randomUUID()
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
                created_at,
                updated_at
            )
            VALUES (?, 'OBSERVED_TRANSACTION', ?, 'TRANSACTION_SEEN', ?, CAST(? AS jsonb), 'NEW', 0, ?, ?, ?)
            """.trimIndent(),
            outboxEventId,
            transactionId,
            idempotencyKey,
            """{"eventType":"TRANSACTION_SEEN"}""",
            now(),
            now(),
            now(),
        )
        return outboxEventId
    }

    private fun now(): Timestamp = Timestamp.from(Instant.now())
}
