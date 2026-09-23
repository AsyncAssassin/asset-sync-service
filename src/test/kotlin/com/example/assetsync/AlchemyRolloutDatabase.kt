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
 * Prepares a fresh database for a boot with `asset-sync.provider.type=alchemy` by applying the
 * migrations up front. The seeded `local-evm` chain stays enabled without an Alchemy network: with
 * no active watched addresses on it the startup preflight only warns, so a fresh database needs no
 * operator step before the first `alchemy` boot.
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
        }
    }
}
