package com.example.assetsync.e2e

import com.example.assetsync.AssetSyncServiceApplication
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.UserDetailsManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Stops PostgreSQL under two running contexts and checks what callers get: every API endpoint
 * answers `503 database-unavailable`. Under the permissive `test` chain the request reaches
 * Spring MVC, where the transaction manager fails before the first query. Under the protected
 * `e2e` chain HTTP Basic cannot read its user store, so the answer must not blame the credentials
 * with a `401` and a challenge. The class owns its container: stopping the shared Testcontainers
 * database would break the other test classes.
 */
class DatabaseOutageIntegrationTests {

    private val objectMapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()

    @Test
    fun `every endpoint answers 503 database-unavailable while the database is down`() {
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("asset_sync_outage")
            .withUsername("asset_sync")
            .withPassword("asset_sync")
            .also { it.start() }
        val contexts = mutableListOf<ConfigurableApplicationContext>()
        try {
            val permissive = boot(postgres, "test").also(contexts::add)
            val protected = boot(postgres, "e2e").also(contexts::add)
            val credentials = createOperator(protected)
            contexts.forEach(::assertJdbcTimeouts)
            assertEquals(200, send(protected, "GET", "/actuator/health").statusCode(), "health before the outage")

            postgres.stop()

            val mismatches = endpoints.flatMap { endpoint ->
                listOf(
                    endpoint.describe("test") to send(permissive, endpoint.method, endpoint.path, endpoint.body),
                    endpoint.describe("e2e, operator") to
                        send(protected, endpoint.method, endpoint.path, endpoint.body, credentials),
                ).mapNotNull { (scenario, response) -> databaseUnavailableMismatch(scenario, response) }
            }
            assertTrue(mismatches.isEmpty(), mismatches.joinToString(separator = "\n", prefix = "\n"))

            // The request id reaches the security-chain answer too.
            val secured = send(protected, "GET", "/api/v1/accounts/${UUID.randomUUID()}", credentials = credentials)
            assertTrue(objectMapper.readTree(secured.body()).hasNonNull("requestId"), secured.body())

            // Without credentials nothing needs the database: the challenge is unchanged.
            val anonymous = send(protected, "GET", "/api/v1/accounts/${UUID.randomUUID()}")
            assertEquals(401, anonymous.statusCode(), anonymous.body())
            assertEquals(
                "Basic realm=\"asset-sync-service\"",
                anonymous.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse(null),
            )
            contexts.forEach { context ->
                assertEquals(503, send(context, "GET", "/actuator/health").statusCode(), "health during the outage")
            }
        } finally {
            contexts.forEach(ConfigurableApplicationContext::close)
            postgres.stop()
        }
    }

    private data class Endpoint(val method: String, val path: String, val body: String? = null) {
        fun describe(profile: String) = "$method $path ($profile)"
    }

    private val id = UUID.randomUUID()

    private val endpoints = listOf(
        Endpoint("POST", "/api/v1/accounts", """{"externalRef":"outage"}"""),
        Endpoint("GET", "/api/v1/accounts/$id"),
        Endpoint(
            "POST",
            "/api/v1/accounts/$id/addresses",
            """{"chainId":"local-evm","address":"0xoutage","asset":"USDC"}""",
        ),
        Endpoint("GET", "/api/v1/accounts/$id/addresses"),
        Endpoint("PATCH", "/api/v1/addresses/$id", """{"status":"DISABLED"}"""),
        Endpoint("POST", "/api/v1/addresses/$id/sync"),
        Endpoint("POST", "/api/v1/accounts/$id/sync"),
        Endpoint("GET", "/api/v1/sync-runs/$id"),
        Endpoint(
            "POST",
            "/api/v1/observed-events",
            """
            {"chainId":"local-evm","txHash":"0xoutage","eventIndex":0,"address":"0xoutage","asset":"USDC",
             "amount":"1.5","blockHeight":100,"confirmations":1,"direction":"INBOUND","status":"SEEN"}
            """.trimIndent(),
        ),
    )

    private fun databaseUnavailableMismatch(scenario: String, response: HttpResponse<String>): String? {
        val type = runCatching { objectMapper.readTree(response.body())["type"]?.asText() }.getOrNull()
        val challenge = response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse(null)
        val expected = response.statusCode() == 503 &&
            type == "https://asset-sync-service/errors/database-unavailable" &&
            challenge == null
        return if (expected) null else "$scenario: ${response.statusCode()} challenge=$challenge ${response.body()}"
    }

    /** The pool hands out connections with the configured JDBC socket and connect timeouts. */
    private fun assertJdbcTimeouts(context: ConfigurableApplicationContext) {
        context.getBean(DataSource::class.java).connection.use { connection ->
            assertEquals(40_000, connection.networkTimeout, "socketTimeout of a pooled connection")
        }
    }

    private fun createOperator(context: ConfigurableApplicationContext): String {
        val encoder = context.getBean(PasswordEncoder::class.java)
        context.getBean(UserDetailsManager::class.java).createUser(
            User.withUsername("outage-operator").password(encoder.encode("outage-pw")).roles("OPERATOR").build(),
        )
        return Base64.getEncoder().encodeToString("outage-operator:outage-pw".toByteArray())
    }

    private fun send(
        context: ConfigurableApplicationContext,
        method: String,
        path: String,
        body: String? = null,
        credentials: String? = null,
    ): HttpResponse<String> {
        val port = (context as WebServerApplicationContext).webServer.port
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody(),
            )
            .apply {
                body?.let { header(HttpHeaders.CONTENT_TYPE, "application/json") }
                credentials?.let { header(HttpHeaders.AUTHORIZATION, "Basic $it") }
            }
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /**
     * A one-second pool wait keeps every failing request short; the outage shows the same
     * exceptions as with the default 30 seconds.
     */
    private fun boot(postgres: PostgreSQLContainer<*>, profile: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(AssetSyncServiceApplication::class.java)
            .profiles(profile)
            .run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--spring.datasource.hikari.connection-timeout=1000",
                "--spring.datasource.hikari.validation-timeout=500",
                "--asset-sync.outbox.scheduler.enabled=false",
                "--asset-sync.outbox.retention.enabled=false",
                "--asset-sync.sync.recovery.enabled=false",
                "--asset-sync.sync.worker.enabled=false",
            )
}
