package com.example.assetsync.e2e

import com.example.assetsync.AssetSyncServiceApplication
import com.fasterxml.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariDataSource
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
 * answers `503 database-unavailable`. Under the permissive `test` chain the request reaches Spring
 * MVC and fails on its first database access: opening the transaction, a query outside one, or the
 * rollback on a connection the outage broke. Under the protected `e2e` chain HTTP Basic cannot read
 * its user store, so the answer must not blame the credentials with a `401` and a challenge. The
 * class owns its container: stopping the shared Testcontainers database would break the other test
 * classes.
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
            // Before the outage the operator's credentials work, and a username the user store cannot
            // hold is an ordinary bad credential, not a database failure.
            assertEquals(404, send(protected, "GET", "/api/v1/accounts/${UUID.randomUUID()}", credentials = credentials).statusCode())
            val unstorable = send(protected, "GET", "/actuator/health", credentials = basic("\u0000", "pw"))
            assertEquals(401, unstorable.statusCode(), unstorable.body())
            assertEquals(CHALLENGE, unstorable.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse(null))

            postgres.stop()

            val responses = endpoints.flatMap { endpoint ->
                listOf(
                    endpoint.describe("test") to sendAsync(permissive, endpoint.method, endpoint.path, endpoint.body),
                    endpoint.describe("e2e, operator") to
                        sendAsync(protected, endpoint.method, endpoint.path, endpoint.body, credentials),
                )
            }
            val mismatches = responses.mapNotNull { (scenario, response) -> databaseUnavailableMismatch(scenario, response.join()) }
            assertTrue(mismatches.isEmpty(), mismatches.joinToString(separator = "\n", prefix = "\n"))

            // The request id reaches the security-chain answer too.
            val secured = send(protected, "GET", "/api/v1/accounts/${UUID.randomUUID()}", credentials = credentials)
            assertTrue(objectMapper.readTree(secured.body()).hasNonNull("requestId"), secured.body())

            // Without credentials nothing needs the database: the challenge is unchanged.
            val anonymous = send(protected, "GET", "/api/v1/accounts/${UUID.randomUUID()}")
            assertEquals(401, anonymous.statusCode(), anonymous.body())
            assertEquals(CHALLENGE, anonymous.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse(null))
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

    /**
     * The pool carries the configured pgjdbc timeouts, and the socket timeout stays above the
     * statement timeout the connection init SQL sets, so a slow query still ends with the server's
     * own error.
     */
    private fun assertJdbcTimeouts(context: ConfigurableApplicationContext) {
        val dataSource = context.getBean(DataSource::class.java)
        assertEquals("5", dataSource.unwrap(HikariDataSource::class.java).dataSourceProperties.getProperty("connectTimeout"))
        dataSource.connection.use { connection ->
            val statementTimeoutMillis = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT setting::int FROM pg_settings WHERE name = 'statement_timeout'")
                    .use { rows -> rows.next(); rows.getInt(1) }
            }
            assertEquals(40_000, connection.networkTimeout, "socketTimeout of a pooled connection")
            assertTrue(connection.networkTimeout > statementTimeoutMillis, "statement_timeout is ${statementTimeoutMillis}ms")
        }
    }

    private fun createOperator(context: ConfigurableApplicationContext): String {
        val encoder = context.getBean(PasswordEncoder::class.java)
        context.getBean(UserDetailsManager::class.java).createUser(
            User.withUsername("outage-operator").password(encoder.encode("outage-pw")).roles("OPERATOR").build(),
        )
        return basic("outage-operator", "outage-pw")
    }

    private fun basic(username: String, password: String): String =
        Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    private fun send(
        context: ConfigurableApplicationContext,
        method: String,
        path: String,
        body: String? = null,
        credentials: String? = null,
    ): HttpResponse<String> = sendAsync(context, method, path, body, credentials).join()

    private fun sendAsync(
        context: ConfigurableApplicationContext,
        method: String,
        path: String,
        body: String? = null,
        credentials: String? = null,
    ) = client.sendAsync(request(context, method, path, body, credentials), HttpResponse.BodyHandlers.ofString())

    private fun request(
        context: ConfigurableApplicationContext,
        method: String,
        path: String,
        body: String?,
        credentials: String?,
    ): HttpRequest {
        val port = (context as WebServerApplicationContext).webServer.port
        return HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody(),
            )
            .apply {
                body?.let { header(HttpHeaders.CONTENT_TYPE, "application/json") }
                credentials?.let { header(HttpHeaders.AUTHORIZATION, "Basic $it") }
            }
            .build()
    }

    /**
     * Hikari's shortest pool wait keeps every failing request short; the outage shows the same
     * exceptions as with the default 30 seconds. The `test` and `e2e` profiles switch off the
     * scheduled jobs.
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
                "--spring.datasource.hikari.connection-timeout=250",
                "--spring.datasource.hikari.validation-timeout=250",
            )

    private companion object {
        const val CHALLENGE = "Basic realm=\"asset-sync-service\""
    }
}
