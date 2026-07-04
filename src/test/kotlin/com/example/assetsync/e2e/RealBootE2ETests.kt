package com.example.assetsync.e2e

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The capstone non-mock test: boots a real embedded Tomcat under the `e2e` profile (the protected
 * security chain and the real `HttpChainProvider` are active — the same wiring as prod), drives it
 * over HTTP, and proves auth + the real provider path end to end. The provider points at an
 * in-test JDK HttpServer stub (deterministic, no extra dependency, port fixed before context start
 * via @DynamicPropertySource). Schedulers are OFF (application-e2e.yml) so assertions are
 * deterministic. This is the layer that would have caught N2 (prod bootability) and N4 (auth).
 */
@ActiveProfiles("e2e")
@Import(TestcontainersConfiguration::class)
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealBootE2ETests(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val userDetailsManager: UserDetailsManager,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val syncRunLifecycleService: SyncRunLifecycleService,
    @Autowired private val syncApplicationService: SyncApplicationService,
) {

    @BeforeEach
    fun setUp() {
        cleanDomain()
        seedUser("e2e-reader", "reader-pw", "READ")
        seedUser("e2e-operator", "operator-pw", "OPERATOR")
    }

    @Test
    fun `boots prod-like, enforces roles, and drives the real provider path`() {
        // 1) Health is open; the app booted under a real servlet container (no endpoint collision).
        assertEquals(HttpStatus.OK, restTemplate.getForEntity("/actuator/health", String::class.java).statusCode)

        // 2) Unauthenticated API access is rejected — and the 401 still carries the request id,
        //    proving RequestIdFilter runs before the security chain (N10).
        val anonymous = restTemplate.getForEntity(
            "/api/v1/accounts/00000000-0000-0000-0000-000000000000",
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.statusCode)
        assertTrue(anonymous.headers.containsKey("X-Request-Id"), "401 responses must carry the request id")

        // 3) Operator creates an account + watched address (mutations require OPERATOR).
        val accountId = operator()
            .postForEntity("/api/v1/accounts", json("""{"externalRef":"e2e-acct"}"""), Map::class.java)
            .let { assertEquals(HttpStatus.CREATED, it.statusCode); it.body!!["id"] as String }

        val addressId = operator()
            .postForEntity(
                "/api/v1/accounts/$accountId/addresses",
                json("""{"chainId":"local-evm","address":"0xe2eaddr","asset":"USDC","label":"e2e"}"""),
                Map::class.java,
            )
            .let { assertEquals(HttpStatus.CREATED, it.statusCode); it.body!!["id"] as String }

        // 4) Reader may read but must NOT mutate (role slice).
        assertEquals(
            HttpStatus.OK,
            reader().getForEntity("/api/v1/accounts/$accountId", String::class.java).statusCode,
        )
        assertEquals(
            HttpStatus.FORBIDDEN,
            reader().postForEntity("/api/v1/addresses/$addressId/sync", HttpEntity<Void>(HttpHeaders()), String::class.java).statusCode,
        )

        // 5) Operator sync drives the REAL HttpChainProvider -> in-test stub -> ingestion.
        val sync = operator()
            .postForEntity("/api/v1/addresses/$addressId/sync", HttpEntity<Void>(HttpHeaders()), Map::class.java)
        assertEquals(HttpStatus.ACCEPTED, sync.statusCode)
        assertEquals("QUEUED", sync.body!!["status"])
        assertNotNull(sync.headers.location)
        runNextClaimedSync()
        assertEquals("SUCCEEDED", syncRunStatus(sync.body!!["id"] as String))
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM observed_transactions", Int::class.java))
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java))

        // 6) Prometheus is served by Boot's autoconfig endpoint and exposes application meters.
        val prometheus = operator().getForEntity("/actuator/prometheus", String::class.java)
        assertEquals(HttpStatus.OK, prometheus.statusCode)
        assertTrue(prometheus.body?.contains("asset_sync") == true)
    }

    private fun operator() = restTemplate.withBasicAuth("e2e-operator", "operator-pw")

    private fun reader() = restTemplate.withBasicAuth("e2e-reader", "reader-pw")

    private fun json(body: String): HttpEntity<String> =
        HttpEntity(body, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON })

    private fun seedUser(username: String, password: String, role: String) {
        if (userDetailsManager.userExists(username)) {
            userDetailsManager.deleteUser(username)
        }
        userDetailsManager.createUser(
            User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles(role)
                .build(),
        )
    }

    private fun cleanDomain() {
        jdbcTemplate.update("DELETE FROM outbox_events")
        jdbcTemplate.update("DELETE FROM sync_runs")
        jdbcTemplate.update("DELETE FROM observed_transactions")
        jdbcTemplate.update("DELETE FROM watched_addresses")
        jdbcTemplate.update("DELETE FROM accounts")
    }

    private fun runNextClaimedSync() {
        val claimed = syncRunLifecycleService.claimDueRuns(
            workerId = "e2e-test-worker-${UUID.randomUUID()}",
            limit = 1,
        )
        assertEquals(1, claimed.size)
        syncApplicationService.executeClaimedSyncRun(claimed.single())
    }

    private fun syncRunStatus(syncRunId: String): String =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT status FROM sync_runs WHERE id = ?",
                String::class.java,
                UUID.fromString(syncRunId),
            ),
        )

    companion object {
        private val providerStub: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange ->
                val body = """
                    {"events":[{"txHash":"0xe2e-tx","eventIndex":0,"address":"0xe2eaddr","asset":"USDC",
                    "amount":1.000000000000000000,"blockHeight":1000,"confirmations":6,
                    "direction":"INBOUND","status":"CONFIRMED"}]}
                """.trimIndent().replace("\n", "")
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun providerProperties(registry: DynamicPropertyRegistry) {
            registry.add("asset-sync.provider.base-url") { "http://localhost:${providerStub.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stopStub() {
            providerStub.stop(0)
        }
    }
}
