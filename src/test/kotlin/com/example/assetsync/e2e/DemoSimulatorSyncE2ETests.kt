package com.example.assetsync.e2e

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.example.assetsync.config.ProviderProperties
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.util.TestSocketUtils

/**
 * The `demo` profile end to end over real HTTP: the operator registers watched addresses, and a
 * sync runs through `HttpChainProvider` into the bundled simulator on the server's own port, the
 * same self-call a demo makes. `server.port` is fixed before the context starts and nothing sets
 * the provider base URL, so the test also proves the demo base URL follows `server.port`. Every
 * seeded chain must sync, `eth-sepolia` included, whose ingest rules accept only real 32-byte
 * transaction hashes. The worker is off; the test claims and executes the runs itself.
 */
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = [
        "asset-sync.outbox.scheduler.enabled=false",
        "asset-sync.sync.recovery.enabled=false",
        "asset-sync.sync.worker.enabled=false",
    ],
)
class DemoSimulatorSyncE2ETests(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val providerProperties: ProviderProperties,
    @Autowired private val syncRunLifecycleService: SyncRunLifecycleService,
    @Autowired private val syncApplicationService: SyncApplicationService,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
) {

    @Test
    fun `the simulator base url follows server port`() {
        assertEquals("http://localhost:$serverPort/simulator", providerProperties.baseUrl)
    }

    @Test
    fun `a demo sync succeeds on every seeded chain through the real http bridge`() {
        val accountId = createAccount()
        listOf(
            "local-evm" to "0xdemo-e2e-${UUID.randomUUID().toString().take(8)}",
            "eth-sepolia" to "0x${randomHex(bytes = 20)}",
        ).forEach { (chainId, address) ->
            val addressId = registerAddress(accountId = accountId, chainId = chainId, address = address)
            val syncRunId = submitSync(addressId)

            syncRunLifecycleService.claimDueRuns(workerId = "demo-e2e-${UUID.randomUUID()}", limit = 10)
                .forEach(syncApplicationService::executeClaimedSyncRun)

            val run = jdbcTemplate.queryForMap(
                "SELECT status, events_seen, events_changed, last_error FROM sync_runs WHERE id = ?",
                syncRunId,
            )
            assertEquals("SUCCEEDED", run["status"], "$chainId sync failed: ${run["last_error"]}")
            assertEquals(1, run["events_seen"], "$chainId events seen")
            assertEquals(1, run["events_changed"], "$chainId events changed")
            val txHash = jdbcTemplate.queryForObject(
                "SELECT tx_hash FROM observed_transactions WHERE watched_address_id = ?",
                String::class.java,
                UUID.fromString(addressId),
            )
            assertTrue(Regex("^0x[0-9a-f]{64}$").matches(txHash.orEmpty()), "$chainId stored $txHash")
        }
    }

    private fun createAccount(): String {
        val response = operator().postForEntity(
            "/api/v1/accounts",
            json("""{"externalRef":"demo-e2e-${UUID.randomUUID()}"}"""),
            String::class.java,
        )
        assertEquals(HttpStatus.CREATED, response.statusCode, "account creation: ${response.body}")
        return objectMapper.readTree(response.body)["id"].asText()
    }

    private fun registerAddress(accountId: String, chainId: String, address: String): String {
        val response = operator().postForEntity(
            "/api/v1/accounts/$accountId/addresses",
            json("""{"chainId":"$chainId","address":"$address","asset":"USDC"}"""),
            String::class.java,
        )
        assertEquals(HttpStatus.CREATED, response.statusCode, "$chainId registration: ${response.body}")
        return objectMapper.readTree(response.body)["id"].asText()
    }

    private fun submitSync(addressId: String): UUID {
        val response = operator().postForEntity("/api/v1/addresses/$addressId/sync", null, String::class.java)
        assertEquals(HttpStatus.ACCEPTED, response.statusCode, "sync submission: ${response.body}")
        return UUID.fromString(objectMapper.readTree(response.body)["id"].asText())
    }

    private fun operator() = restTemplate.withBasicAuth("demo-operator", "demo-operator-pw")

    private fun json(body: String): HttpEntity<String> =
        HttpEntity(body, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON })

    private fun randomHex(bytes: Int): String =
        HexFormat.of().formatHex(ByteArray(bytes).also(SecureRandom()::nextBytes))

    companion object {
        private val serverPort = TestSocketUtils.findAvailableTcpPort()

        @JvmStatic
        @DynamicPropertySource
        fun fixServerPort(registry: DynamicPropertyRegistry) {
            registry.add("server.port") { serverPort }
        }
    }
}
