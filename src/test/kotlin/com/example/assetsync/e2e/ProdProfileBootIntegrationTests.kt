package com.example.assetsync.e2e

import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Boots the real `prod` profile end to end so the prod-only wiring is exercised by a test, not just
 * config review — closing the round-2 blind spot where a prod-path regression could ship green.
 * It proves: the env-driven datasource boots (N2), the dedicated Liquibase datasource applies the
 * migrations incl. the users table (N5), and the env-provisioned prod admin can authenticate
 * against the DB-backed store (N4). Fail-fast on missing secrets is verified separately (a bootRun
 * with no env dies with an unresolved-placeholder error).
 */
@ActiveProfiles("prod")
@AutoConfigureObservability
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "asset-sync.outbox.scheduler.enabled=false",
        "asset-sync.outbox.retention.enabled=false",
        "asset-sync.sync.recovery.enabled=false",
        "asset-sync.sync.worker.enabled=false",
    ],
)
class ProdProfileBootIntegrationTests(
    @Autowired private val restTemplate: TestRestTemplate,
) {

    @Test
    fun `prod profile boots, migrates via the dedicated liquibase datasource, and authenticates the env admin`() {
        // Booted under the protected chain: health is open, the API rejects anonymous access.
        assertEquals(HttpStatus.OK, restTemplate.getForEntity("/actuator/health", String::class.java).statusCode)
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            restTemplate.getForEntity("/api/v1/accounts/${UUID.randomUUID()}", String::class.java).statusCode,
        )

        // The env-provisioned admin authenticates against the DB user store (proves 010 migrated and
        // the prod-admin runner ran): a missing account is 404, not 401/403.
        assertEquals(
            HttpStatus.NOT_FOUND,
            restTemplate
                .withBasicAuth("prod-admin", "prod-admin-pw")
                .getForEntity("/api/v1/accounts/${UUID.randomUUID()}", String::class.java)
                .statusCode,
        )
    }

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("asset_sync_prod")
                .withUsername("asset_sync")
                .withPassword("asset_sync")
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            // Distinct Liquibase datasource (N5) — same DB, bare connection without the app-pool init-sql.
            registry.add("spring.liquibase.url") { postgres.jdbcUrl }
            registry.add("spring.liquibase.user") { postgres.username }
            registry.add("spring.liquibase.password") { postgres.password }
            registry.add("asset-sync.provider.base-url") { "http://localhost:1" }
            registry.add("ASSET_SYNC_ADMIN_USERNAME") { "prod-admin" }
            registry.add("ASSET_SYNC_ADMIN_PASSWORD") { "prod-admin-pw" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
