package com.example.assetsync.e2e

import com.example.assetsync.AlchemyJsonRpcStubServer
import com.example.assetsync.AlchemyRolloutDatabase
import com.example.assetsync.AssetSyncServiceApplication
import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.config.AlchemyProviderProperties
import com.example.assetsync.config.ProviderProperties
import com.example.assetsync.config.ProviderType
import com.example.assetsync.infrastructure.provider.HttpChainProvider
import com.example.assetsync.infrastructure.provider.HttpChainProviderHealthIndicator
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProvider
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProviderHealthIndicator
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyJsonRpcClient
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyProviderState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

private const val ALCHEMY_BOOT_API_KEY = "boot-secret-key-123"

/**
 * Boots the real `prod` profile with `asset-sync.provider.type=alchemy` and no HTTP `base-url`:
 * only the Alchemy beans exist, the startup preflight probes `eth-sepolia` with a bearer token
 * against an in-test JSON-RPC stub, and health names the provider without leaking the key. The
 * negative cases boot separate contexts and prove the fail-fast rules: a missing key, a rejected
 * key, an enabled chain with active watched addresses but no network mapping, and legacy watched
 * addresses each stop the process with a scrubbed `ProviderConfigurationException`, while an
 * Alchemy that is only unavailable at startup does not.
 *
 * The database is migrated before the first boot and otherwise left as fresh: the seeded
 * `local-evm` chain stays enabled without a network mapping, which only warns while it has no
 * active watched addresses.
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
        "asset-sync.provider.type=alchemy",
        "asset-sync.provider.alchemy.api-key=$ALCHEMY_BOOT_API_KEY",
    ],
)
class AlchemyProviderBootIntegrationTests(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val context: ApplicationContext,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val providerProperties: ProviderProperties,
    @Autowired private val alchemyProperties: AlchemyProviderProperties,
) {

    @Test
    fun `alchemy type boots without a base url, probes sepolia with a bearer token, and wires only alchemy beans`() {
        assertEquals(ProviderType.ALCHEMY, providerProperties.type)
        assertEquals("", providerProperties.baseUrl, "the HTTP bridge base-url must stay unset")
        assertEquals(
            true,
            jdbcTemplate.queryForObject("SELECT enabled FROM chain_configs WHERE chain_id = 'local-evm'", Boolean::class.java),
            "a fresh database boots with the unmapped local-evm chain still enabled",
        )
        assertNull(alchemyProperties.networkFor("eth-sepolia")?.startBlock, "an empty env placeholder binds start-block to null")

        // Exactly one port, the Alchemy one; no HTTP bridge bean of any kind.
        assertEquals(1, context.getBeansOfType(ChainProviderPort::class.java).size)
        assertTrue(context.getBean(ChainProviderPort::class.java) is AlchemyChainProvider)
        assertTrue(context.containsBean("alchemyRestClient"))
        assertFalse(context.containsBean("chainProviderRestClient"))
        assertTrue(context.getBeansOfType(HttpChainProvider::class.java).isEmpty())
        assertTrue(context.getBeansOfType(HttpChainProviderHealthIndicator::class.java).isEmpty())
        assertEquals(1, context.getBeansOfType(AlchemyJsonRpcClient::class.java).size)
        assertEquals(1, context.getBeansOfType(AlchemyChainProviderHealthIndicator::class.java).size)

        // The preflight probed the one required network in header mode; the key never hit a URL.
        val probes = stub.requests.filter { it.method == "eth_blockNumber" }
        assertTrue(probes.isNotEmpty(), "startup must probe eth_blockNumber")
        assertEquals(setOf("/eth-sepolia/v2/"), probes.map { it.path }.toSet())
        assertTrue(probes.all { it.authorization == "Bearer $ALCHEMY_BOOT_API_KEY" }, probes.toString())
        assertTrue(stub.requests.none { it.path.contains(ALCHEMY_BOOT_API_KEY) }, stub.requests.toString())

        // Health: aggregate for anonymous probes, Alchemy details for the env admin, no secrets.
        val anonymousHealth = restTemplate.getForEntity("/actuator/health", String::class.java)
        assertEquals(HttpStatus.OK, anonymousHealth.statusCode, "unexpected health response: ${anonymousHealth.body}")
        assertTrue(anonymousHealth.body?.contains("\"components\"") != true, "anonymous health must not expose components")

        val adminHealth = restTemplate.withBasicAuth("prod-admin", "prod-admin-pw").getForEntity("/actuator/health", String::class.java)
        assertEquals(HttpStatus.OK, adminHealth.statusCode, "unexpected health response: ${adminHealth.body}")
        val body = requireNotNull(adminHealth.body)
        assertTrue(body.contains("\"alchemyChainProvider\""), body)
        assertFalse(body.contains("httpChainProvider"), body)
        assertTrue(body.contains("\"provider\":\"alchemy\""), body)
        assertTrue(body.contains("\"authMode\":\"header\""), body)
        assertTrue(body.contains("\"networks\":[\"eth-sepolia\"]"), body)
        assertTrue(body.contains("\"state\":\"probe-succeeded\""), body)
        assertFalse(body.contains(ALCHEMY_BOOT_API_KEY), "health must not leak the api key: $body")
        assertFalse(body.contains("/v2/"), "health must not leak the endpoint: $body")
    }

    @Test
    fun `a missing api key fails startup before any probe`() {
        val requestsBefore = stub.requests.size

        val failure = bootFailure("asset-sync.provider.alchemy.api-key" to "")

        assertTrue(failure.message!!.contains("api-key must be set"), failure.message)
        assertEquals(requestsBefore, stub.requests.size, "no probe may run without a key")
    }

    @Test
    fun `a rejected api key fails startup with a scrubbed message`() {
        stub.responseStatus = 401
        try {
            val failure = bootFailure()

            assertTrue(failure.message!!.contains("startup probe failed for network eth-sepolia"), failure.message)
            assertTrue(failure.message!!.contains("HTTP 401"), failure.message)
            assertFalse(failure.message!!.contains(ALCHEMY_BOOT_API_KEY), failure.message)
        } finally {
            stub.responseStatus = 200
        }
    }

    @Test
    fun `an enabled chain with active watched addresses but no network mapping fails startup`() {
        val accountId = insertAccount()
        jdbcTemplate.update(
            """
            INSERT INTO watched_addresses (id, account_id, chain_id, address, asset, label, status, created_at, updated_at)
            VALUES (?, ?, 'local-evm', '0xalchemy-boot-local', 'USDC', NULL, 'ACTIVE', now(), now())
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
        )
        try {
            val failure = bootFailure()

            assertTrue(failure.message!!.contains("with active watched addresses but no Alchemy network mapping: [local-evm]"), failure.message)
            assertTrue(failure.message!!.contains("disable them in chain_configs"), failure.message)
        } finally {
            jdbcTemplate.update("DELETE FROM watched_addresses WHERE account_id = ?", accountId)
            jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId)
        }
    }

    @Test
    fun `an alchemy unavailable at startup does not stop the service and reports probe-failed`() {
        stub.responseStatus = 503
        try {
            bootContext().use { booted ->
                val provider = booted.getBean(AlchemyChainProvider::class.java)
                assertEquals(AlchemyProviderState.PROBE_FAILED, provider.state())

                val health = booted.getBean(AlchemyChainProviderHealthIndicator::class.java).health()
                assertEquals(Status.DOWN, health.status)
                assertEquals("probe-failed", health.details["state"])
                assertEquals(emptyList<String>(), health.details["networks"])
                assertEquals(
                    "Alchemy startup probe failed for network eth-sepolia: Alchemy returned HTTP 503 for network eth-sepolia.",
                    health.details["error"],
                )
                assertFalse(health.details.toString().contains(ALCHEMY_BOOT_API_KEY), health.details.toString())
            }
        } finally {
            stub.responseStatus = 200
        }
    }

    @Test
    fun `active watched addresses without an enabled asset config fail startup`() {
        val accountId = insertAccount()
        jdbcTemplate.update(
            """
            INSERT INTO watched_addresses (id, account_id, chain_id, address, asset, label, status, created_at, updated_at)
            VALUES (?, ?, 'eth-sepolia', ?, 'DAI', NULL, 'ACTIVE', now(), now())
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            "0x${UUID.randomUUID().toString().replace("-", "")}00000000",
        )
        try {
            val failure = bootFailure()

            assertTrue(failure.message!!.contains("active watched addresses without an enabled asset config: (eth-sepolia, DAI) x1"), failure.message)
        } finally {
            jdbcTemplate.update("DELETE FROM watched_addresses WHERE account_id = ?", accountId)
            jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId)
        }
    }

    private fun insertAccount(): UUID {
        val accountId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO accounts (id, external_ref, status, created_at, updated_at) VALUES (?, ?, 'ACTIVE', now(), now())",
            accountId,
            "alchemy-boot-$accountId",
        )
        return accountId
    }

    private fun bootFailure(vararg overrides: Pair<String, String>): ProviderConfigurationException {
        val thrown = assertFailsWith<Throwable> { bootContext(*overrides).close() }
        val cause = generateSequence(thrown) { it.cause }.filterIsInstance<ProviderConfigurationException>().firstOrNull()
        assertNotNull(cause, "expected a ProviderConfigurationException in the failure chain of: $thrown")
        return cause
    }

    /**
     * Boots a separate prod context with the same wiring as the cached one; command-line args
     * override every other property source. Overrides replace base entries by key, because a
     * repeated `--key=` argument would be joined into one comma-separated value instead.
     */
    private fun bootContext(vararg overrides: Pair<String, String>): ConfigurableApplicationContext {
        val properties = mapOf(
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
            "asset-sync.outbox.scheduler.enabled" to "false",
            "asset-sync.outbox.retention.enabled" to "false",
            "asset-sync.sync.recovery.enabled" to "false",
            "asset-sync.sync.worker.enabled" to "false",
            "asset-sync.provider.type" to "alchemy",
            "asset-sync.provider.alchemy.api-key" to ALCHEMY_BOOT_API_KEY,
            "asset-sync.provider.alchemy.endpoint-template" to stub.headerEndpointTemplate(),
        ) + overrides
        val arguments = properties.map { (key, value) -> "--$key=$value" }
        return SpringApplicationBuilder(AssetSyncServiceApplication::class.java)
            .profiles("prod")
            .run(*arguments.toTypedArray())
    }

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("asset_sync_alchemy")
                .withUsername("asset_sync")
                .withPassword("asset_sync")
                .also { it.start() }

        private val stub = AlchemyJsonRpcStubServer()

        init {
            AlchemyRolloutDatabase.prepare(postgres.jdbcUrl, postgres.username, postgres.password)
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.liquibase.url") { postgres.jdbcUrl }
            registry.add("spring.liquibase.user") { postgres.username }
            registry.add("spring.liquibase.password") { postgres.password }
            registry.add("ASSET_SYNC_ADMIN_USERNAME") { "prod-admin" }
            registry.add("ASSET_SYNC_ADMIN_PASSWORD") { "prod-admin-pw" }
            registry.add("asset-sync.provider.alchemy.endpoint-template") { stub.headerEndpointTemplate() }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            stub.close()
            postgres.stop()
        }
    }
}
