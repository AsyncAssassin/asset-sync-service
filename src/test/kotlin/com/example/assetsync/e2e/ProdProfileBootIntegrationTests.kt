package com.example.assetsync.e2e

import com.example.assetsync.AssetSyncServiceApplication
import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.config.DEMO_OPERATOR_USERNAME
import com.example.assetsync.config.DEMO_READER_USERNAME
import com.example.assetsync.infrastructure.provider.HttpChainProvider
import com.example.assetsync.infrastructure.provider.HttpChainProviderHealthIndicator
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProvider
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProviderHealthIndicator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.userdetails.User
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Boots the real `prod` profile end to end so the prod-only wiring is exercised by a test, not just
 * config review, so a regression on the prod path cannot ship green.
 * It proves: the env-driven datasource boots, the dedicated Liquibase datasource applies the
 * migrations incl. the users table, and the env-provisioned prod admin can authenticate
 * against the DB-backed store. Fail-fast on missing secrets is verified separately (a bootRun
 * with no env dies with an unresolved-placeholder error). It also pins the provider wiring of a
 * default prod context: the HTTP bridge beans and nothing Alchemy-specific, and a blank
 * `base-url` still fails the boot. Second contexts booted against the same container cover the
 * startup guards: `demo`, `local`, and `test` refuse another profile before anything reaches a
 * database, every profile with authentication except `demo` refuses the demo users, and the prod
 * admin cannot take a demo user name.
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
    @Autowired private val context: ApplicationContext,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val userDetailsManager: UserDetailsManager,
) {

    @Test
    fun `prod profile boots, migrates via the dedicated liquibase datasource, and authenticates the env admin`() {
        // Booted under the protected chain: health is open, the API rejects anonymous access. The body
        // is part of the failure message so a wrong status shows which handler produced it.
        val health = restTemplate.getForEntity("/actuator/health", String::class.java)
        assertEquals(HttpStatus.OK, health.statusCode, "unexpected health response: ${health.body}")
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

        // Missing asset-sync.provider.type means the HTTP bridge: one port, the bridge RestClient and
        // health indicator, and no Alchemy bean at all.
        assertEquals(1, context.getBeansOfType(ChainProviderPort::class.java).size)
        assertTrue(context.getBean(ChainProviderPort::class.java) is HttpChainProvider)
        assertTrue(context.containsBean("chainProviderRestClient"))
        assertEquals(1, context.getBeansOfType(HttpChainProviderHealthIndicator::class.java).size)
        assertFalse(context.containsBean("alchemyRestClient"))
        assertTrue(context.getBeansOfType(AlchemyChainProvider::class.java).isEmpty())
        assertTrue(context.getBeansOfType(AlchemyChainProviderHealthIndicator::class.java).isEmpty())
    }

    @Test
    fun `a request the firewall rejects keeps its 400 instead of turning into a basic challenge`() {
        // StrictHttpFirewall rejects these paths before authentication and answers 400 through the
        // container's /error dispatch. That dispatch must not demand credentials, or every caller,
        // even one with valid credentials, would see 401 and a browser would ask for a password.
        val client = HttpClient.newHttpClient()
        val adminCredentials = Base64.getEncoder().encodeToString("prod-admin:prod-admin-pw".toByteArray())
        listOf("//api/v1/accounts/${UUID.randomUUID()}", "/api/v1/accounts;x=1").forEach { path ->
            listOf(null, adminCredentials).forEach { credentials ->
                val request = HttpRequest.newBuilder(URI.create(restTemplate.rootUri + path))
                    .apply { credentials?.let { header(HttpHeaders.AUTHORIZATION, "Basic $it") } }
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())

                val scenario = "$path with${if (credentials == null) "out" else ""} credentials"
                assertEquals(400, response.statusCode(), "unexpected response for $scenario: ${response.body()}")
                assertTrue(
                    response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).isEmpty,
                    "no Basic challenge expected for $scenario",
                )
            }
        }
    }

    @Test
    fun `the simulator path is not open outside demo`() {
        // prod serves no simulator, so the path falls through to the authenticated default.
        val anonymous = restTemplate.getForEntity(
            "/simulator/v1/chains/local-evm/addresses/0xabc/events?asset=USDC&limit=1",
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.statusCode, "unexpected simulator response: ${anonymous.body}")
    }

    @Test
    fun `http provider type still requires the base url`() {
        val thrown = assertFailsWith<Throwable> {
            boot(listOf("prod"), "asset-sync.provider.type" to "http", "asset-sync.provider.base-url" to "")
        }

        assertNotNull(
            causeContaining(thrown, "asset-sync.provider.base-url must be set"),
            "expected the base-url requirement in the failure chain of: $thrown",
        )
    }

    @Test
    fun `every profile with authentication except demo refuses a database that holds the demo users`() {
        // A database the demo profile once ran on: its public demo users sit in the same table. `prod`,
        // a custom protected profile without a profile file, and a start without any profile refuse it.
        withDemoUsers {
            mapOf(
                listOf("prod") to "[prod]",
                listOf("staging") to "[staging]",
                emptyList<String>() to "[default]",
            ).forEach { (profiles, shown) ->
                val thrown = assertFailsWith<Throwable>("$profiles must refuse the demo users") { boot(profiles) }

                val cause = causeContaining(thrown, "The profiles $shown found the demo users [demo-operator, demo-reader]")
                assertNotNull(cause, "expected the demo-user guard for $profiles in the failure chain of: $thrown")
                assertTrue(
                    cause.message!!.contains("DELETE FROM users WHERE username IN ('demo-operator', 'demo-reader');"),
                    cause.message,
                )
            }
            // The guard never touches the data: the operator decides.
            assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE username LIKE 'demo-%'", Int::class.java))
        }
    }

    @Test
    fun `demo, local, and test refuse another profile before anything touches the database`() {
        // Every combination points at an empty database: had one of them reached the data source or
        // Liquibase, the migrations would have created tables there.
        listOf(
            listOf("prod", "demo"),
            listOf("prod", "local"),
            listOf("prod", "test"),
            listOf("staging", "demo"),
            listOf("staging", "local"),
        ).forEach { profiles ->
            val thrown = assertFailsWith<Throwable>("$profiles must not start") { boot(profiles, *onBlankDatabase()) }

            assertNotNull(
                causeContaining(thrown, "The profiles $profiles cannot be combined"),
                "expected the profile combination guard for $profiles in the failure chain of: $thrown",
            )
        }
        assertEquals(0, blankDatabaseTableCount(), "no refused combination may reach the database")
    }

    @Test
    fun `a combination is named even when the prod secrets are missing`() {
        // Without the datasource URLs the prod profile's placeholders cannot resolve; the guard runs first.
        val thrown = assertFailsWith<Throwable> {
            boot(
                listOf("prod", "local"),
                "spring.datasource.url" to null,
                "spring.liquibase.url" to null,
                "ASSET_SYNC_ADMIN_USERNAME" to null,
                "ASSET_SYNC_ADMIN_PASSWORD" to null,
            )
        }

        assertNotNull(
            causeContaining(thrown, "The profiles [prod, local] cannot be combined"),
            "expected the profile combination guard, not a placeholder failure, in: $thrown",
        )
    }

    @Test
    fun `a protected profile starts on a database without the users table`() {
        // A custom profile runs no migrations of its own: the demo-user guard finds no user store and
        // lets the context start instead of failing on its own query.
        boot(listOf("staging"), *onBlankDatabase())

        assertEquals(0, blankDatabaseTableCount())
    }

    @Test
    fun `the prod admin cannot take a demo user name`() {
        val thrown = assertFailsWith<Throwable> {
            boot(listOf("prod"), "ASSET_SYNC_ADMIN_USERNAME" to DEMO_OPERATOR_USERNAME)
        }

        assertNotNull(
            causeContaining(thrown, "ASSET_SYNC_ADMIN_USERNAME must not be a demo user name"),
            "expected the reserved-name check in the failure chain of: $thrown",
        )
        assertFalse(userDetailsManager.userExists(DEMO_OPERATOR_USERNAME))
    }

    private fun withDemoUsers(block: () -> Unit) {
        userDetailsManager.createUser(User.withUsername(DEMO_READER_USERNAME).password("{noop}public").roles("READ").build())
        userDetailsManager.createUser(User.withUsername(DEMO_OPERATOR_USERNAME).password("{noop}public").roles("OPERATOR").build())
        try {
            block()
        } finally {
            userDetailsManager.deleteUser(DEMO_READER_USERNAME)
            userDetailsManager.deleteUser(DEMO_OPERATOR_USERNAME)
        }
    }

    private fun causeContaining(thrown: Throwable, text: String): Throwable? =
        generateSequence(thrown) { it.cause }.firstOrNull { it.message?.contains(text) == true }

    private fun onBlankDatabase(): Array<Pair<String, String?>> =
        arrayOf("spring.datasource.url" to blankDatabaseUrl, "spring.liquibase.url" to blankDatabaseUrl)

    private fun blankDatabaseTableCount(): Int =
        DriverManager.getConnection(blankDatabaseUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    /**
     * Boots a second context with [profiles] (none means Spring's default profile) against the shared
     * database and closes it at once. Each override replaces a base setting by key, or drops it when
     * its value is null: a repeated `--key=` argument would be joined into one comma-separated value.
     */
    private fun boot(profiles: List<String>, vararg overrides: Pair<String, String?>) {
        val settings = linkedMapOf<String, String?>(
            "server.port" to "0",
            "spring.main.banner-mode" to "off",
            "spring.datasource.url" to postgres.jdbcUrl,
            "spring.datasource.username" to postgres.username,
            "spring.datasource.password" to postgres.password,
            "spring.liquibase.url" to postgres.jdbcUrl,
            "spring.liquibase.user" to postgres.username,
            "spring.liquibase.password" to postgres.password,
            "ASSET_SYNC_ADMIN_USERNAME" to "prod-admin",
            "ASSET_SYNC_ADMIN_PASSWORD" to "prod-admin-pw",
            "asset-sync.provider.base-url" to "http://localhost:1",
            "asset-sync.outbox.scheduler.enabled" to "false",
            "asset-sync.outbox.retention.enabled" to "false",
            "asset-sync.sync.recovery.enabled" to "false",
            "asset-sync.sync.worker.enabled" to "false",
        )
        overrides.forEach { (key, value) -> settings[key] = value }
        SpringApplicationBuilder(AssetSyncServiceApplication::class.java)
            .profiles(*profiles.toTypedArray())
            .run(*settings.filterValues { it != null }.map { (key, value) -> "--$key=$value" }.toTypedArray())
            .close()
    }

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("asset_sync_prod")
                .withUsername("asset_sync")
                .withPassword("asset_sync")
                .also { it.start() }

        /** A second, never migrated database in the same container, for starts that must not touch the data. */
        private val blankDatabaseUrl: String = run {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { it.execute("CREATE DATABASE asset_sync_blank") }
            }
            "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)}/asset_sync_blank"
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            // Distinct Liquibase datasource: same DB, bare connection without the app-pool init-sql.
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
