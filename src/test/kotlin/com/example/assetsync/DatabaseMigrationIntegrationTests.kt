package com.example.assetsync

import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
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
            insertObservedTransaction(watchedAddressId, txHash = "0xnegative-block-height", blockHeight = -1)
        }

        assertThrows<DataIntegrityViolationException> {
            insertOutboxEvent(transactionId, duplicateOutboxKey)
            insertOutboxEvent(transactionId, duplicateOutboxKey)
        }
    }

    @Test
    fun `async sync run migration supports queue fields and in-flight uniqueness`() {
        val columns = jdbcTemplate.queryForList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_name = 'sync_runs'
            """.trimIndent(),
            String::class.java,
        ).toSet()
        assertTrue(columns.containsAll(setOf("queued_at", "locked_by", "lock_token", "locked_until", "heartbeat_at", "attempts", "next_attempt_at")))

        val targetId = UUID.randomUUID()
        val queued = insertSyncRun(status = "QUEUED", targetId = targetId, startedAt = null, finishedAt = null)
        assertEquals(null, jdbcTemplate.queryForObject("SELECT started_at FROM sync_runs WHERE id = ?", Timestamp::class.java, queued))

        assertThrows<DataIntegrityViolationException> {
            insertSyncRun(status = "QUEUED", targetId = targetId, startedAt = null, finishedAt = null)
        }

        val terminalTargetId = UUID.randomUUID()
        insertSyncRun(status = "SUCCEEDED", targetId = terminalTargetId, startedAt = now(), finishedAt = now())
        insertSyncRun(status = "QUEUED", targetId = terminalTargetId, startedAt = null, finishedAt = null)

        val legacyTargetId = UUID.randomUUID()
        insertSyncRun(status = "STARTED", targetId = legacyTargetId, startedAt = now(), finishedAt = null)
        insertSyncRun(status = "QUEUED", targetId = legacyTargetId, startedAt = null, finishedAt = null)

        assertThrows<DataIntegrityViolationException> {
            insertSyncRun(status = "RUNNING", targetId = UUID.randomUUID(), startedAt = now(), finishedAt = null)
        }
    }

    @Test
    fun `migration 013 upgrades legacy sync runs and enforces async queue invariants`() {
        val schema = "migration_013_${UUID.randomUUID().toString().replace("-", "_")}"
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
            }
        }

        try {
            withMigrationSchemaConnection(schema) { connection ->
                runLiquibase(connection, changesToApply = 12)

                val startedAt = Instant.parse("2026-07-01T10:00:00Z")
                val finishedAt = Instant.parse("2026-07-01T10:05:00Z")
                val legacyStartedTarget = UUID.randomUUID()
                val legacySucceededTarget = UUID.randomUUID()
                val legacyFailedTarget = UUID.randomUUID()
                val legacyStarted = insertLegacyMigrationSyncRun(
                    connection = connection,
                    status = "STARTED",
                    targetId = legacyStartedTarget,
                    startedAt = startedAt,
                    finishedAt = null,
                    eventsSeen = 7,
                    eventsChanged = 3,
                )
                val legacySucceeded = insertLegacyMigrationSyncRun(
                    connection = connection,
                    status = "SUCCEEDED",
                    targetId = legacySucceededTarget,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                )
                val legacyFailed = insertLegacyMigrationSyncRun(
                    connection = connection,
                    status = "FAILED",
                    targetId = legacyFailedTarget,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                )

                runLiquibase(connection)

                listOf(legacyStarted, legacySucceeded, legacyFailed).forEach { id ->
                    assertEquals(0, queryMigrationInt(connection, "SELECT attempts FROM sync_runs WHERE id = ?", id))
                    assertEquals(null, queryMigrationString(connection, "SELECT locked_by FROM sync_runs WHERE id = ?", id))
                    assertEquals(null, queryMigrationString(connection, "SELECT lock_token::text FROM sync_runs WHERE id = ?", id))
                    assertEquals(null, queryMigrationTimestamp(connection, "SELECT locked_until FROM sync_runs WHERE id = ?", id))
                    assertEquals(null, queryMigrationTimestamp(connection, "SELECT heartbeat_at FROM sync_runs WHERE id = ?", id))
                    assertEquals(
                        startedAt,
                        queryMigrationTimestamp(connection, "SELECT queued_at FROM sync_runs WHERE id = ?", id)?.toInstant(),
                    )
                    assertEquals(
                        startedAt,
                        queryMigrationTimestamp(connection, "SELECT next_attempt_at FROM sync_runs WHERE id = ?", id)?.toInstant(),
                    )
                }
                assertEquals("STARTED", queryMigrationString(connection, "SELECT status FROM sync_runs WHERE id = ?", legacyStarted))

                val queuedForLegacyStarted = insertPost013MigrationSyncRun(
                    connection = connection,
                    status = "QUEUED",
                    targetId = legacyStartedTarget,
                    startedAt = null,
                    finishedAt = null,
                )
                assertEquals(
                    null,
                    queryMigrationTimestamp(connection, "SELECT started_at FROM sync_runs WHERE id = ?", queuedForLegacyStarted),
                )

                val runningTarget = UUID.randomUUID()
                insertPost013MigrationSyncRun(
                    connection = connection,
                    status = "RUNNING",
                    targetId = runningTarget,
                    startedAt = startedAt,
                    finishedAt = null,
                    lockedBy = "migration-test-worker",
                    lockToken = UUID.randomUUID(),
                    lockedUntil = startedAt.plusSeconds(60),
                    heartbeatAt = startedAt,
                    attempts = 1,
                )

                val duplicateInFlightTarget = UUID.randomUUID()
                insertPost013MigrationSyncRun(
                    connection = connection,
                    status = "QUEUED",
                    targetId = duplicateInFlightTarget,
                    startedAt = null,
                    finishedAt = null,
                )
                val duplicateInFlightSavepoint = connection.setSavepoint("duplicate_in_flight")
                assertThrows<SQLException> {
                    insertPost013MigrationSyncRun(
                        connection = connection,
                        status = "RUNNING",
                        targetId = duplicateInFlightTarget,
                        startedAt = startedAt,
                        finishedAt = null,
                        lockedBy = "migration-test-worker",
                        lockToken = UUID.randomUUID(),
                        lockedUntil = startedAt.plusSeconds(60),
                        heartbeatAt = startedAt,
                        attempts = 1,
                    )
                }
                connection.rollback(duplicateInFlightSavepoint)

                insertPost013MigrationSyncRun(
                    connection = connection,
                    status = "QUEUED",
                    targetId = legacySucceededTarget,
                    startedAt = null,
                    finishedAt = null,
                )
                insertPost013MigrationSyncRun(
                    connection = connection,
                    status = "QUEUED",
                    targetId = legacyFailedTarget,
                    startedAt = null,
                    finishedAt = null,
                )
            }
        } finally {
            dataSource.connection.use { cleanupConnection ->
                cleanupConnection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                }
            }
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
        blockHeight: Long = 1,
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
            VALUES (?, 'local-evm', ?, 0, ?, '0xobserved-address', 'USDC', 'INBOUND', ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            transactionId,
            txHash,
            watchedAddressId,
            BigDecimal("1.000000000000000000"),
            blockHeight,
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

    private fun insertSyncRun(
        status: String,
        targetId: UUID,
        startedAt: Timestamp?,
        finishedAt: Timestamp?,
    ): UUID {
        val syncRunId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO sync_runs (
                id,
                target_type,
                target_id,
                status,
                started_at,
                finished_at,
                events_seen,
                events_changed,
                queued_at,
                attempts,
                next_attempt_at,
                created_at,
                updated_at
            )
            VALUES (?, 'ADDRESS', ?, ?, ?, ?, 0, 0, ?, 0, ?, ?, ?)
            """.trimIndent(),
            syncRunId,
            targetId,
            status,
            startedAt,
            finishedAt,
            now(),
            now(),
            now(),
            now(),
        )
        return syncRunId
    }

    private fun insertLegacyMigrationSyncRun(
        connection: Connection,
        status: String,
        targetId: UUID,
        startedAt: Instant,
        finishedAt: Instant?,
        eventsSeen: Int = 0,
        eventsChanged: Int = 0,
    ): UUID {
        val syncRunId = UUID.randomUUID()
        connection.prepareStatement(
            """
            INSERT INTO sync_runs (
                id,
                target_type,
                target_id,
                status,
                started_at,
                finished_at,
                events_seen,
                events_changed,
                created_at,
                updated_at
            )
            VALUES (?, 'ADDRESS', ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, syncRunId)
            statement.setObject(2, targetId)
            statement.setString(3, status)
            statement.setTimestamp(4, Timestamp.from(startedAt))
            statement.setTimestamp(5, finishedAt?.let { Timestamp.from(it) })
            statement.setInt(6, eventsSeen)
            statement.setInt(7, eventsChanged)
            statement.setTimestamp(8, Timestamp.from(startedAt))
            statement.setTimestamp(9, Timestamp.from(startedAt))
            statement.executeUpdate()
        }
        return syncRunId
    }

    private fun insertPost013MigrationSyncRun(
        connection: Connection,
        status: String,
        targetId: UUID,
        startedAt: Instant?,
        finishedAt: Instant?,
        lockedBy: String? = null,
        lockToken: UUID? = null,
        lockedUntil: Instant? = null,
        heartbeatAt: Instant? = null,
        attempts: Int = 0,
    ): UUID {
        val syncRunId = UUID.randomUUID()
        val now = Instant.parse("2026-07-01T11:00:00Z")
        connection.prepareStatement(
            """
            INSERT INTO sync_runs (
                id,
                target_type,
                target_id,
                status,
                started_at,
                finished_at,
                events_seen,
                events_changed,
                queued_at,
                locked_by,
                lock_token,
                locked_until,
                heartbeat_at,
                attempts,
                next_attempt_at,
                created_at,
                updated_at
            )
            VALUES (?, 'ADDRESS', ?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, syncRunId)
            statement.setObject(2, targetId)
            statement.setString(3, status)
            statement.setTimestamp(4, startedAt?.let { Timestamp.from(it) })
            statement.setTimestamp(5, finishedAt?.let { Timestamp.from(it) })
            statement.setTimestamp(6, Timestamp.from(now))
            statement.setString(7, lockedBy)
            statement.setObject(8, lockToken)
            statement.setTimestamp(9, lockedUntil?.let { Timestamp.from(it) })
            statement.setTimestamp(10, heartbeatAt?.let { Timestamp.from(it) })
            statement.setInt(11, attempts)
            statement.setTimestamp(12, Timestamp.from(now))
            statement.setTimestamp(13, Timestamp.from(now))
            statement.setTimestamp(14, Timestamp.from(now))
            statement.executeUpdate()
        }
        return syncRunId
    }

    private fun queryMigrationString(connection: Connection, sql: String, vararg args: Any?): String? =
        queryMigrationOne(connection, sql, { it.getString(1) }, *args)

    private fun queryMigrationInt(connection: Connection, sql: String, vararg args: Any?): Int =
        queryMigrationOne(connection, sql, { it.getInt(1) }, *args)

    private fun queryMigrationTimestamp(connection: Connection, sql: String, vararg args: Any?): Timestamp? =
        queryMigrationOne(connection, sql, { it.getTimestamp(1) }, *args)

    private fun <T> queryMigrationOne(
        connection: Connection,
        sql: String,
        mapper: (ResultSet) -> T,
        vararg args: Any?,
    ): T {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, arg ->
                statement.setObject(index + 1, arg)
            }
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next(), "Expected one row for query: $sql")
                return mapper(resultSet)
            }
        }
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
