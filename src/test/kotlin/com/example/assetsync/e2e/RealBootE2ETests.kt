package com.example.assetsync.e2e

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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
    @Autowired private val objectMapper: ObjectMapper,
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
        //    Anonymous probes get the aggregate status only; authenticated callers see the components
        //    (show-details: when-authorized), including the real HTTP provider indicator.
        val anonymousHealth = restTemplate.getForEntity("/actuator/health", String::class.java)
        assertEquals(HttpStatus.OK, anonymousHealth.statusCode, "unexpected health response: ${anonymousHealth.body}")
        assertTrue(anonymousHealth.body?.contains("\"components\"") != true, "anonymous health must not expose components")
        val readerHealth = reader().getForEntity("/actuator/health", String::class.java)
        assertEquals(HttpStatus.OK, readerHealth.statusCode)
        assertTrue(readerHealth.body?.contains("\"db\"") == true, "authenticated health must expose the db component")
        assertTrue(readerHealth.body?.contains("httpChainProvider") == true, "authenticated health must include the HTTP provider indicator")

        // 2) Unauthenticated API access is rejected — and the 401 still carries the request id,
        //    proving RequestIdFilter runs before the security chain (N10).
        val anonymous = restTemplate.getForEntity(
            "/api/v1/accounts/00000000-0000-0000-0000-000000000000",
            String::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.statusCode)
        assertTrue(anonymous.headers.containsKey("X-Request-Id"), "401 responses must carry the request id")
        //    The 401 is a ProblemDetail that echoes the request id and keeps the Basic challenge.
        assertTrue(anonymous.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)?.startsWith("Basic") == true, "401 must keep the Basic challenge")
        assertTrue(anonymous.headers.contentType?.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) == true, "401 must be a ProblemDetail")
        assertTrue(anonymous.body?.contains("errors/unauthorized") == true, "401 body: ${anonymous.body}")
        val anonymousRequestId = anonymous.headers.getFirst("X-Request-Id")
        assertTrue(anonymous.body?.contains("\"requestId\":\"$anonymousRequestId\"") == true, "401 body must echo the request id: ${anonymous.body}")
        val badCredentials = restTemplate
            .withBasicAuth("e2e-reader", "wrong-pw")
            .getForEntity("/api/v1/accounts/00000000-0000-0000-0000-000000000000", String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, badCredentials.statusCode)
        assertTrue(badCredentials.body?.contains("errors/unauthorized") == true, "bad credentials body: ${badCredentials.body}")

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
        val forbidden = reader().postForEntity("/api/v1/addresses/$addressId/sync", HttpEntity<Void>(HttpHeaders()), String::class.java)
        assertEquals(HttpStatus.FORBIDDEN, forbidden.statusCode)
        assertTrue(forbidden.headers.contentType?.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) == true, "403 must be a ProblemDetail")
        assertTrue(forbidden.body?.contains("errors/forbidden") == true, "403 body: ${forbidden.body}")

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

    @Test
    fun `a bridge that returns no cursor resumes from the checkpoint the provider sends`() {
        val accountId = operator()
            .postForEntity("/api/v1/accounts", json("""{"externalRef":"e2e-resume"}"""), Map::class.java)
            .let { assertEquals(HttpStatus.CREATED, it.statusCode); it.body!!["id"] as String }
        val addressId = operator()
            .postForEntity(
                "/api/v1/accounts/$accountId/addresses",
                json("""{"chainId":"local-evm","address":"$RESUME_ADDRESS","asset":"USDC"}"""),
                Map::class.java,
            )
            .let { assertEquals(HttpStatus.CREATED, it.statusCode); it.body!!["id"] as String }

        val first = operator().postForEntity("/api/v1/addresses/$addressId/sync", HttpEntity<Void>(HttpHeaders()), Map::class.java)
        runNextClaimedSync()
        assertEquals("SUCCEEDED", syncRunStatus(first.body!!["id"] as String))
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM observed_transactions", Int::class.java))

        // The final page carried no cursor. Without the checkpoint the bridge would serve its
        // history again, and the first event, now behind the checkpoint, would fail the run.
        val second = operator().postForEntity("/api/v1/addresses/$addressId/sync", HttpEntity<Void>(HttpHeaders()), Map::class.java)
        runNextClaimedSync()
        assertEquals("SUCCEEDED", syncRunStatus(second.body!!["id"] as String))
        assertEquals("asset=USDC&limit=100&fromBlockHeight=101&fromEventIndex=0", resumeQueries.last())
    }

    @Test
    fun `only the operator can disable a watched address, and a disabled address cannot be synced`() {
        val accountId = operator()
            .postForEntity("/api/v1/accounts", json("""{"externalRef":"e2e-disable"}"""), Map::class.java)
            .let { assertEquals(HttpStatus.CREATED, it.statusCode); it.body!!["id"] as String }
        val addressId = operator()
            .postForEntity(
                "/api/v1/accounts/$accountId/addresses",
                json("""{"chainId":"local-evm","address":"0xe2e-disable","asset":"USDC"}"""),
                Map::class.java,
            )
            .let { assertEquals(HttpStatus.CREATED, it.statusCode); it.body!!["id"] as String }
        val disable = json("""{"status":"DISABLED"}""")

        assertEquals(
            HttpStatus.FORBIDDEN,
            reader().exchange("/api/v1/addresses/$addressId", HttpMethod.PATCH, disable, String::class.java).statusCode,
        )
        val disabled = operator().exchange("/api/v1/addresses/$addressId", HttpMethod.PATCH, disable, Map::class.java)
        assertEquals(HttpStatus.OK, disabled.statusCode)
        assertEquals("DISABLED", disabled.body!!["status"])
        assertEquals(
            HttpStatus.NOT_FOUND,
            operator().postForEntity("/api/v1/addresses/$addressId/sync", HttpEntity<Void>(HttpHeaders()), String::class.java).statusCode,
        )
    }

    @Test
    fun `openapi declares http basic so swagger ui offers authorize`() {
        val apiDocs = reader().getForEntity("/v3/api-docs", String::class.java)
        assertEquals(HttpStatus.OK, apiDocs.statusCode, "unexpected api-docs response: ${apiDocs.body}")

        val document = objectMapper.readTree(apiDocs.body)
        val scheme = document.path("components").path("securitySchemes").path("basicAuth")
        assertEquals("http", scheme.path("type").asText(), "api-docs: ${apiDocs.body}")
        assertEquals("basic", scheme.path("scheme").asText(), "api-docs: ${apiDocs.body}")
        assertTrue(
            document.path("security").any { it.has("basicAuth") },
            "HTTP Basic must be the global requirement: ${document.path("security")}",
        )
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
        private const val RESUME_ADDRESS = "0xe2e-resume"

        /** Query strings the resume stub received, in order. */
        private val resumeQueries = CopyOnWriteArrayList<String>()

        private val providerStub: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = if (exchange.requestURI.path.contains("/addresses/$RESUME_ADDRESS/")) {
                    resumeQueries += exchange.requestURI.rawQuery
                    resumeBridgePage(exchange.requestURI.rawQuery)
                } else {
                    """
                        {"events":[{"txHash":"0xe2e-tx","eventIndex":0,"address":"0xe2eaddr","asset":"USDC",
                        "amount":1.000000000000000000,"blockHeight":1000,"confirmations":6,
                        "direction":"INBOUND","status":"CONFIRMED"}],
                        "nextCursor":"e2e-final","hasMore":false,"latestBlockHeight":1000,"safeBlockHeight":1000}
                    """.trimIndent().replace("\n", "")
                }
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        /**
         * A bridge that never returns a cursor: it serves the address's two events at or after
         * `(fromBlockHeight, fromEventIndex)`, or from the start of its history without them.
         */
        private fun resumeBridgePage(query: String?): String {
            val params = query.orEmpty().split("&").filter { it.contains("=") }.associate { it.substringBefore("=") to it.substringAfter("=") }
            val fromBlock = params["fromBlockHeight"]?.toLong() ?: Long.MIN_VALUE
            val fromIndex = params["fromEventIndex"]?.toInt() ?: 0
            val events = listOf(100L to "0xe2e-resume-1", 101L to "0xe2e-resume-2")
                .filter { (block, _) -> block > fromBlock || (block == fromBlock && 0 >= fromIndex) }
                .joinToString(",") { (block, txHash) ->
                    """{"txHash":"$txHash","eventIndex":0,"address":"$RESUME_ADDRESS","asset":"USDC","amount":1,""" +
                        """"blockHeight":$block,"confirmations":6,"direction":"INBOUND","status":"CONFIRMED"}"""
                }
            return """{"events":[$events],"hasMore":false,"latestBlockHeight":101,"safeBlockHeight":101}"""
        }

        @JvmStatic
        @DynamicPropertySource
        fun providerProperties(registry: DynamicPropertyRegistry) {
            registry.add("asset-sync.provider.base-url") { "http://127.0.0.1:${providerStub.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stopStub() {
            providerStub.stop(0)
        }
    }
}
