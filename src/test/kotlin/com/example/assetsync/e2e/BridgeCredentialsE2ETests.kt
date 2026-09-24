package com.example.assetsync.e2e

import com.example.assetsync.TestcontainersConfiguration
import com.example.assetsync.application.sync.SyncApplicationService
import com.example.assetsync.application.sync.SyncRunLifecycleService
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The HTTP bridge has no credential setting of its own, so a token can only live in the path or
 * the query of `base-url`. With the bridge unreachable, a `READ` caller sees the failure on the
 * sync run and in the health details, and neither shows the url or its token. No log line of the
 * run does either, even at TRACE, where the JDK's HTTP client would print the url.
 */
@ActiveProfiles("e2e")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
class BridgeCredentialsE2ETests(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val userDetailsManager: UserDetailsManager,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val syncRunLifecycleService: SyncRunLifecycleService,
    @Autowired private val syncApplicationService: SyncApplicationService,
) {

    @Test
    fun `an unreachable bridge shows readers neither its url nor its credentials`(output: CapturedOutput) {
        seedUser("bridge-reader", "reader-pw", "READ")
        seedUser("bridge-operator", "operator-pw", "OPERATOR")
        val operator = restTemplate.withBasicAuth("bridge-operator", "operator-pw")
        val reader = restTemplate.withBasicAuth("bridge-reader", "reader-pw")
        val accountId = operator
            .postForEntity("/api/v1/accounts", json("""{"externalRef":"bridge-${UUID.randomUUID()}"}"""), Map::class.java)
            .let { assertEquals(HttpStatus.CREATED, it.statusCode); it.body!!["id"] as String }
        val addressId = operator
            .postForEntity(
                "/api/v1/accounts/$accountId/addresses",
                json("""{"chainId":"local-evm","address":"0xbridge-${UUID.randomUUID()}","asset":"USDC"}"""),
                Map::class.java,
            )
            .let { assertEquals(HttpStatus.CREATED, it.statusCode); it.body!!["id"] as String }
        val syncRunId = operator
            .postForEntity("/api/v1/addresses/$addressId/sync", HttpEntity<Void>(HttpHeaders()), Map::class.java)
            .let { assertEquals(HttpStatus.ACCEPTED, it.statusCode); it.body!!["id"] as String }

        val claimed = syncRunLifecycleService.claimDueRuns(workerId = "bridge-credentials-test", limit = 1)
        assertEquals(1, claimed.size)
        val loggingSystem = LoggingSystem.get(javaClass.classLoader)
        loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.TRACE)
        try {
            syncApplicationService.executeClaimedSyncRun(claimed.single())
        } finally {
            loggingSystem.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO)
        }

        val run = reader.getForEntity("/api/v1/sync-runs/$syncRunId", Map::class.java)
        assertEquals(HttpStatus.OK, run.statusCode)
        assertEquals("Provider transport failure: cannot connect (ConnectException).", run.body!!["lastError"])
        val health = reader.getForEntity("/actuator/health", String::class.java).body.orEmpty()
        assertTrue(health.contains("Provider transport failure: cannot connect"), health)
        listOf(health, output.out).forEach { text ->
            SECRETS.forEach { secret -> assertFalse(text.contains(secret), "$secret leaked") }
        }
    }

    private fun json(body: String): HttpEntity<String> =
        HttpEntity(body, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON })

    private fun seedUser(username: String, password: String, role: String) {
        if (userDetailsManager.userExists(username)) {
            userDetailsManager.deleteUser(username)
        }
        userDetailsManager.createUser(User.withUsername(username).password(passwordEncoder.encode(password)).roles(role).build())
    }

    companion object {
        private const val PATH_SECRET = "PATH_SECRET"
        private const val QUERY_SECRET = "QUERY_SECRET"
        private val SECRETS = listOf(PATH_SECRET, QUERY_SECRET)

        /** A port nothing listens on, so every fetch is refused. */
        private val closedPort = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

        @JvmStatic
        @DynamicPropertySource
        fun providerProperties(registry: DynamicPropertyRegistry) {
            registry.add("asset-sync.provider.base-url") { "http://127.0.0.1:$closedPort/$PATH_SECRET?token=$QUERY_SECRET" }
        }
    }
}
