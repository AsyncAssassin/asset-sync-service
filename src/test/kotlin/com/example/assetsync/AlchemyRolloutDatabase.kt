package com.example.assetsync

import java.nio.file.Path
import java.sql.DriverManager
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.DirectoryResourceAccessor

/**
 * Prepares a fresh database for a boot with `asset-sync.provider.type=alchemy`: applies the
 * migrations up front and disables the seeded `local-evm` chain, which has no Alchemy network.
 * This is exactly the operator step the README documents; without it the first `alchemy` boot
 * stops at the rollout preflight.
 */
object AlchemyRolloutDatabase {

    fun prepare(jdbcUrl: String, username: String, password: String) {
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
            database.defaultSchemaName = requireNotNull(connection.schema)
            database.liquibaseSchemaName = requireNotNull(connection.schema)
            DirectoryResourceAccessor(Path.of("src/main/resources")).use { resourceAccessor ->
                Liquibase("db/changelog/db.changelog-master.yaml", resourceAccessor, database)
                    .update(Contexts(), LabelExpression())
            }
            // Liquibase leaves the connection in manual-commit mode; switch back so the update is committed.
            connection.autoCommit = true
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE chain_configs SET enabled = false WHERE chain_id = 'local-evm'")
            }
        }
    }
}
