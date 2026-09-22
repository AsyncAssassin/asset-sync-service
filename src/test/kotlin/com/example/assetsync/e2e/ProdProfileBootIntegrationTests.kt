package com.example.assetsync.e2e

import com.example.assetsync.AssetSyncServiceApplication
import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.infrastructure.provider.HttpChainProvider
import com.example.assetsync.infrastructure.provider.HttpChainProviderHealthIndicator
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProvider
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProviderHealthIndicator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
 * with no env dies with an unresolved-placeholder error). It also pins the provider wiring of a
 * default prod context: the HTTP bridge beans and nothing Alchemy-specific, and a blank
 * `base-url` still fails the boot.
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
            SpringApplicationBuilder(AssetSyncServiceApplication::class.java)
                .profiles("prod")
                .run(
                    "--server.port=0",
                    "--spring.main.banner-mode=off",
                    "--spring.datasource.url=${postgres.jdbcUrl}",
                    "--spring.datasource.username=${postgres.username}",
                    "--spring.datasource.password=${postgres.password}",
                    "--spring.liquibase.url=${postgres.jdbcUrl}",
                    "--spring.liquibase.user=${postgres.username}",
                    "--spring.liquibase.password=${postgres.password}",
                    "--ASSET_SYNC_ADMIN_USERNAME=prod-admin",
                    "--ASSET_SYNC_ADMIN_PASSWORD=prod-admin-pw",
                    "--asset-sync.outbox.scheduler.enabled=false",
                    "--asset-sync.outbox.retention.enabled=false",
                    "--asset-sync.sync.recovery.enabled=false",
                    "--asset-sync.sync.worker.enabled=false",
                    "--asset-sync.provider.type=http",
                    "--asset-sync.provider.base-url=",
                )
                .close()
        }

        val cause = generateSequence(thrown) { it.cause }
            .firstOrNull { it.message?.contains("asset-sync.provider.base-url must be set") == true }
        assertNotNull(cause, "expected the base-url requirement in the failure chain of: $thrown")
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
