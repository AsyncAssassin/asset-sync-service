package com.example.assetsync

import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.zaxxer.hikari.HikariDataSource
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.exception.LiquibaseException
import liquibase.resource.DirectoryResourceAccessor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class DatabaseMigrationIntegrationTests(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val dataSource: DataSource,
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
    fun `database lock and statement timeouts are configured`() {
        assertEquals("5s", jdbcTemplate.queryForObject("SHOW lock_timeout", String::class.java))
        assertEquals("30s", jdbcTemplate.queryForObject("SHOW statement_timeout", String::class.java))
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

    @Test
    fun `local evm normalization migration halts when watched address duplicates would collide`() {
        assertNormalizationPreconditionFailsCleanly { connection ->
            val accountId = insertMigrationAccount(connection)
            insertMigrationWatchedAddress(connection, accountId, "0xABCDEF", "usdc")
            insertMigrationWatchedAddress(connection, accountId, "0xabcdef", "USDC")
        }
    }

    @Test
    fun `local evm normalization migration halts when observed transaction duplicates would collide`() {
        assertNormalizationPreconditionFailsCleanly { connection ->
            val accountId = insertMigrationAccount(connection)
            val watchedAddressId = insertMigrationWatchedAddress(connection, accountId, "0xwatch-only", "USDC")
            insertMigrationObservedTransaction(connection, watchedAddressId, "0xABCDEF", "usdc")
            insertMigrationObservedTransaction(connection, watchedAddressId, "0xabcdef", "USDC")
        }
    }

    @Test
    fun `local evm normalization migration halts when pending outbox row has stale identity casing`() {
        assertNormalizationPreconditionFailsCleanly(expectedMessage = "pending stale or legacy outbox rows") { connection ->
            insertMigrationOutboxEvent(
                connection = connection,
                status = "NEW",
                idempotencyKey = "observed-tx:local-evm:0xpending:0:0xABC:usdc:status:SEEN",
                payload = migrationOutboxPayload(txHash = "0xpending", address = "0xABC", asset = "usdc"),
            )
        }
    }

    @Test
    fun `local evm normalization migration halts when pending outbox row has legacy idempotency key`() {
        listOf("NEW", "FAILED").forEach { pendingStatus ->
            assertNormalizationPreconditionFailsCleanly(expectedMessage = "pending stale or legacy outbox rows") { connection ->
                insertMigrationOutboxEvent(
                    connection = connection,
                    status = pendingStatus,
                    idempotencyKey = "observed-tx:local-evm:0xpending:0:0xabc:USDC:status:SEEN",
                    payload = migrationOutboxPayload(txHash = "0xpending", address = "0xabc", asset = "USDC"),
                )
            }
        }
    }

    @Test
    fun `local evm normalization migration allows canonical pending outbox row with versioned idempotency key`() {
        assertNormalizationPreconditionPasses { connection ->
            insertMigrationOutboxEvent(
                connection = connection,
                status = "NEW",
                idempotencyKey = "observed-tx:local-evm:0xpending:0:0xabc:USDC:status:SEEN:v:0",
                payload = migrationOutboxPayload(txHash = "0xpending", address = "0xabc", asset = "USDC"),
            )
        }
    }

    @Test
    fun `migration 009 allows historical published outbox rows with legacy identity casing`() {
        assertNormalizationPreconditionPasses { connection ->
            insertMigrationOutboxEvent(
                connection = connection,
                status = "PUBLISHED",
                idempotencyKey = "observed-tx:local-evm:0xpublished:0:0xABC:usdc:status:SEEN",
                payload = migrationOutboxPayload(txHash = "0xpublished", address = "0xABC", asset = "usdc"),
            )
        }
    }

    @Test
    fun `migration 009 halts pending outbox rows with non numeric idempotency key version`() {
        listOf("NEW", "FAILED").forEach { pendingStatus ->
            assertNormalizationPreconditionFailsCleanly(expectedMessage = "pending stale or legacy outbox rows") { connection ->
                insertMigrationOutboxEvent(
                    connection = connection,
                    status = pendingStatus,
                    idempotencyKey = "observed-tx:local-evm:0xpending:0:0xabc:USDC:status:SEEN:v:notNumber",
                    payload = migrationOutboxPayload(txHash = "0xpending", address = "0xabc", asset = "USDC"),
                )
            }
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

    private fun assertNormalizationPreconditionFailsCleanly(
        expectedMessage: String = "case-variant duplicates",
        seedDuplicates: (Connection) -> Unit,
    ) {
        val schema = "migration_precondition_${UUID.randomUUID().toString().replace("-", "_")}"
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
            }
        }

        try {
            withMigrationSchemaConnection(schema) { connection ->
                runLiquibase(connection, changesToApply = 8)
            }
            withMigrationSchemaConnection(schema) { connection ->
                seedDuplicates(connection)
            }

            val exception = assertThrows<LiquibaseException> {
                withMigrationSchemaConnection(schema) { connection ->
                    runLiquibase(connection)
                }
            }
            assertTrue(
                exception.causalMessages().contains(expectedMessage),
                "Expected duplicate cleanup message, got: ${exception.causalMessages()}",
            )
        } finally {
            dataSource.connection.use { cleanupConnection ->
                cleanupConnection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                }
            }
        }
    }

    private fun assertNormalizationPreconditionPasses(
        seedRows: (Connection) -> Unit,
    ) {
        val schema = "migration_precondition_${UUID.randomUUID().toString().replace("-", "_")}"
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
            }
        }

        try {
            withMigrationSchemaConnection(schema) { connection ->
                runLiquibase(connection, changesToApply = 8)
            }
            withMigrationSchemaConnection(schema) { connection ->
                seedRows(connection)
            }
            withMigrationSchemaConnection(schema) { connection ->
                runLiquibase(connection)
            }
        } finally {
            dataSource.connection.use { cleanupConnection ->
                cleanupConnection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                }
            }
        }
    }

    private fun <T> withMigrationSchemaConnection(schema: String, block: (Connection) -> T): T =
        newPhysicalConnection().use { connection ->
            try {
                connection.schema = schema
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO $schema")
                }
                block(connection)
            } finally {
                if (!connection.isClosed) {
                    connection.schema = "public"
                    connection.createStatement().use { statement ->
                        statement.execute("SET search_path TO public")
                    }
                }
            }
        }

    private fun newPhysicalConnection(): Connection {
        val hikariDataSource = dataSource.unwrap(HikariDataSource::class.java)
        return DriverManager.getConnection(
            hikariDataSource.jdbcUrl,
            hikariDataSource.username,
            hikariDataSource.password,
        )
    }

    private fun runLiquibase(connection: Connection, changesToApply: Int? = null) {
        val database = DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(JdbcConnection(connection))
        database.defaultSchemaName = requireNotNull(connection.schema)
        database.liquibaseSchemaName = requireNotNull(connection.schema)

        DirectoryResourceAccessor(Path.of("src/main/resources")).use { resourceAccessor ->
            val liquibase = Liquibase("db/changelog/db.changelog-master.yaml", resourceAccessor, database)
            if (changesToApply == null) {
                liquibase.update(Contexts(), LabelExpression())
            } else {
                liquibase.update(changesToApply, Contexts(), LabelExpression())
            }
        }
    }

    private fun insertMigrationAccount(connection: Connection): UUID {
        val accountId = UUID.randomUUID()
        connection.prepareStatement(
            """
            INSERT INTO accounts (id, external_ref, status, created_at, updated_at)
            VALUES (?, ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, accountId)
            statement.setString(2, "migration-account-$accountId")
            statement.setTimestamp(3, now())
            statement.setTimestamp(4, now())
            statement.executeUpdate()
        }
        return accountId
    }

    private fun insertMigrationWatchedAddress(
        connection: Connection,
        accountId: UUID,
        address: String,
        asset: String,
    ): UUID {
        val watchedAddressId = UUID.randomUUID()
        connection.prepareStatement(
            """
            INSERT INTO watched_addresses (
                id, account_id, chain_id, address, asset, label, status, created_at, updated_at
            )
            VALUES (?, ?, 'local-evm', ?, ?, NULL, 'ACTIVE', ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, watchedAddressId)
            statement.setObject(2, accountId)
            statement.setString(3, address)
            statement.setString(4, asset)
            statement.setTimestamp(5, now())
            statement.setTimestamp(6, now())
            statement.executeUpdate()
        }
        return watchedAddressId
    }

    private fun insertMigrationObservedTransaction(
        connection: Connection,
        watchedAddressId: UUID,
        address: String,
        asset: String,
    ): UUID {
        val transactionId = UUID.randomUUID()
        connection.prepareStatement(
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
            VALUES (?, 'local-evm', '0xduplicate-after-normalization', 0, ?, ?, ?, 'INBOUND', ?, 1, 0, 'SEEN', ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, transactionId)
            statement.setObject(2, watchedAddressId)
            statement.setString(3, address)
            statement.setString(4, asset)
            statement.setBigDecimal(5, BigDecimal("1.000000000000000000"))
            statement.setTimestamp(6, now())
            statement.setTimestamp(7, now())
            statement.setTimestamp(8, now())
            statement.setTimestamp(9, now())
            statement.executeUpdate()
        }
        return transactionId
    }

    private fun insertMigrationOutboxEvent(
        connection: Connection,
        status: String,
        idempotencyKey: String,
        payload: String,
    ): UUID {
        val outboxEventId = UUID.randomUUID()
        connection.prepareStatement(
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
            VALUES (?, 'OBSERVED_TRANSACTION', ?, 'TRANSACTION_SEEN', ?, CAST(? AS jsonb), ?, 0, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, outboxEventId)
            statement.setObject(2, UUID.randomUUID())
            statement.setString(3, idempotencyKey)
            statement.setString(4, payload)
            statement.setString(5, status)
            statement.setTimestamp(6, now())
            statement.setTimestamp(7, now())
            statement.setTimestamp(8, now())
            statement.executeUpdate()
        }
        return outboxEventId
    }

    private fun migrationOutboxPayload(
        txHash: String,
        address: String,
        asset: String,
        status: String = "SEEN",
    ): String =
        """
        {
          "eventId": "${UUID.randomUUID()}",
          "eventType": "TRANSACTION_$status",
          "occurredAt": "2026-07-02T00:00:00Z",
          "transactionId": "${UUID.randomUUID()}",
          "chainId": "local-evm",
          "txHash": "$txHash",
          "eventIndex": 0,
          "address": "$address",
          "asset": "$asset",
          "amount": "1.000000000000000000",
          "direction": "INBOUND",
          "status": "$status",
          "confirmations": 0,
          "blockHeight": 1
        }
        """.trimIndent()

    private fun Throwable.causalMessages(): String =
        generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")

    private fun now(): Timestamp = Timestamp.from(Instant.now())
}
