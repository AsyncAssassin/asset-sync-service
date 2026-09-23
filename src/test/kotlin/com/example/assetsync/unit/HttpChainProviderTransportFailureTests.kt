package com.example.assetsync.unit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.infrastructure.provider.HttpChainProvider
import com.example.assetsync.infrastructure.provider.HttpChainProviderHealthIndicator
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/**
 * The bridge's credentials can only live in `base-url`, in its userinfo or its path. A transport
 * failure must describe itself without that URL: its text reaches the health details, the WARN
 * log, and the `lastError` that `READ` callers see.
 */
@ExtendWith(OutputCaptureExtension::class)
class HttpChainProviderTransportFailureTests {

    private val servers = mutableListOf<HttpServer>()
    private val providerLogger = LoggerFactory.getLogger(HttpChainProvider::class.java) as Logger
    private val previousLevel = providerLogger.level

    @AfterTest
    fun tearDown() {
        servers.forEach { it.stop(0) }
        providerLogger.level = previousLevel
    }

    @Test
    fun `a refused connection is reported by its kind, without the url or its credentials`(output: CapturedOutput) {
        providerLogger.level = Level.DEBUG
        val closedPort = ServerSocket(0).use { it.localPort }
        val provider = HttpChainProvider(bridgeClient(secretBaseUrl(closedPort)))

        val failure = assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest()) }

        assertEquals("Provider transport failure: connection refused (ConnectException).", failure.message)
        assertNull(failure.cause, "the cause quotes the url")
        val health = HttpChainProviderHealthIndicator(provider).health()
        assertEquals(Status.DOWN, health.status)
        assertEquals(failure.message, health.details["error"])
        assertNoSecret(health.toString())
        // The DEBUG line keeps the root cause for diagnosis, without the url either.
        assertTrue(output.out.contains("http_provider_transport_failure"), output.out)
        assertNoSecret(output.out)
    }

    @Test
    fun `the kind is found anywhere in the cause chain`() {
        // The JDK HttpClient, RestClient's default, wraps the ConnectException around a
        // ClosedChannelException, so the deepest cause alone would only say "I/O error".
        val closedPort = ServerSocket(0).use { it.localPort }
        val provider = HttpChainProvider(RestClient.builder().baseUrl(secretBaseUrl(closedPort)).build())

        val failure = assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest()) }

        assertEquals("Provider transport failure: connection refused (ConnectException).", failure.message)
    }

    @Test
    fun `a read timeout is reported by its kind`(output: CapturedOutput) {
        val silent = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                Thread.sleep(2_000)
                exchange.close()
            }
            start()
        }.also(servers::add)
        val provider = HttpChainProvider(bridgeClient(secretBaseUrl(silent.address.port), readTimeout = Duration.ofMillis(200)))

        val failure = assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest()) }

        assertEquals("Provider transport failure: timeout (SocketTimeoutException).", failure.message)
        assertNoSecret(output.out)
    }

    /** The request factory ProviderConfiguration gives the bridge client in production. */
    private fun bridgeClient(baseUrl: String, readTimeout: Duration = Duration.ofSeconds(5)): RestClient =
        RestClient.builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(2))
                    setReadTimeout(readTimeout)
                },
            )
            .baseUrl(baseUrl)
            .build()

    private fun secretBaseUrl(port: Int) = "http://bridge-user:$USERINFO_SECRET@127.0.0.1:$port/$PATH_SECRET?token=$QUERY_SECRET"

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
