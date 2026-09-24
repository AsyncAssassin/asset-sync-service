package com.example.assetsync.unit

import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.config.ProviderConfiguration
import com.example.assetsync.config.ProviderProperties
import com.example.assetsync.infrastructure.provider.HttpChainProvider
import com.example.assetsync.infrastructure.provider.HttpChainProviderHealthIndicator
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.web.client.RestClient

/**
 * The bridge has no credential setting of its own, so a token can only live in the path or the
 * query of `base-url`. A transport failure must describe itself without that URL: its text reaches
 * the health details and the `lastError` that `READ` callers see. The bridge client comes from
 * ProviderConfiguration, as in production.
 */
@ExtendWith(OutputCaptureExtension::class)
class HttpChainProviderTransportFailureTests {

    @Test
    fun `a refused connection is named by its kind, without the url`(output: CapturedOutput) {
        val closedPort = ServerSocket(0).use { it.localPort }
        val provider = HttpChainProvider(bridgeClient(closedPort))

        val failure = assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest()) }

        assertEquals("Provider transport failure: cannot connect (ConnectException).", failure.message)
        assertNull(failure.cause, "the cause quotes the url")
        val health = HttpChainProviderHealthIndicator(provider).health()
        assertEquals(Status.DOWN, health.status)
        assertEquals(failure.message, health.details["error"])
        assertNoSecret(health.toString())
        // The WARN line keeps the cause chain for diagnosis, with the url cut out.
        assertTrue(output.out.contains("""causes=ResourceAccessException: I/O error on GET request for "<url>": Connection refused"""), output.out)
        assertNoSecret(output.out)
    }

    @Test
    fun `a read timeout is named by its kind`(output: CapturedOutput) {
        // Accepted by the kernel's backlog and never answered.
        ServerSocket(0).use { silent ->
            val provider = HttpChainProvider(bridgeClient(silent.localPort, readTimeout = Duration.ofMillis(200)))

            val failure = assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest()) }

            assertEquals("Provider transport failure: timeout (SocketTimeoutException).", failure.message)
        }
        assertNoSecret(output.out)
    }

    @Test
    fun `another http client is classified by the same direct cause`() {
        // The JDK HttpClient, RestClient's default, wraps the ConnectException around a
        // ClosedChannelException; the kind comes from the exception RestClient wraps.
        val closedPort = ServerSocket(0).use { it.localPort }
        val provider = HttpChainProvider(RestClient.builder().baseUrl(secretBaseUrl(closedPort)).build())

        val failure = assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest()) }

        assertEquals("Provider transport failure: cannot connect (ConnectException).", failure.message)
    }

    @Test
    fun `a base url with a user name or password is refused without quoting it`() {
        val refused = assertThrows<IllegalArgumentException> {
            ProviderConfiguration().chainProviderRestClient(
                ProviderProperties(baseUrl = "http://bridge-user:$USERINFO_SECRET@127.0.0.1:1/bridge"),
            )
        }

        assertEquals(
            "asset-sync.provider.base-url must not carry a user name or password: the HTTP client never sends them.",
            refused.message,
        )
    }

    private fun bridgeClient(port: Int, readTimeout: Duration = Duration.ofSeconds(5)): RestClient =
        ProviderConfiguration().chainProviderRestClient(
            ProviderProperties(baseUrl = secretBaseUrl(port), readTimeout = readTimeout),
        )

    private fun secretBaseUrl(port: Int) = "http://127.0.0.1:$port/$PATH_SECRET?token=$QUERY_SECRET"

    private fun assertNoSecret(text: String) {
        listOf(USERINFO_SECRET, PATH_SECRET, QUERY_SECRET).forEach { secret ->
            assertFalse(text.contains(secret), "$secret leaked into: $text")
        }
    }

    private fun pageRequest(): ChainProviderEventsPageRequest =
        ChainProviderEventsPageRequest(
            watchedAddressId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            chainId = "local-evm",
            address = "0xtransport",
            asset = "USDC",
            cursor = null,
            limit = 100,
        )

    private companion object {
        const val USERINFO_SECRET = "USERINFO_SECRET"
        const val PATH_SECRET = "PATH_SECRET"
        const val QUERY_SECRET = "QUERY_SECRET"
    }
}
