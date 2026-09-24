package com.example.assetsync.unit

import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.config.ProviderConfiguration
import com.example.assetsync.config.ProviderProperties
import com.example.assetsync.config.SyncProperties
import com.example.assetsync.infrastructure.provider.HttpChainProvider
import com.example.assetsync.infrastructure.provider.HttpChainProviderHealthIndicator
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.actuate.health.Status

/**
 * The HTTP bridge contract as the adapter speaks it (`docs/architecture.md`, HTTP Bridge Page
 * Contract), against a JDK `HttpServer` stub that records every request it receives. The client
 * comes from `ProviderConfiguration`, as in production.
 */
@ExtendWith(OutputCaptureExtension::class)
class HttpBridgeContractTests {

    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val handler = AtomicReference<(HttpExchange) -> Unit> { exchange -> respond(exchange, 200, IDLE_PAGE) }
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests += RecordedRequest(
                path = exchange.requestURI.rawPath,
                query = queryParams(exchange.requestURI.rawQuery),
                headers = exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.toList() },
            )
            handler.get()(exchange)
        }
        executor = Executors.newCachedThreadPool()
        start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `a cursor reaches the bridge byte for byte, whatever it holds`() {
        val provider = provider()

        // base64 padding and slashes, a JSON cursor, a percent sign and an ampersand, the simulator's token.
        listOf("a+b/c==", """{"offset":1}""", "%41&x=1", "sim:0123456789abcdef").forEach { cursor ->
            provider.fetchObservedEventsPage(pageRequest(cursor = cursor, fromBlockHeight = 100, fromEventIndex = 3))

            val received = requests.last()
            assertEquals(cursor, received.query["cursor"], "decoded by the bridge from ${received.query}")
            assertEquals("/v1/chains/local-evm/addresses/0xbridge/events", received.path)
            assertEquals(mapOf("asset" to "USDC", "limit" to "100", "cursor" to cursor, "fromBlockHeight" to "100", "fromEventIndex" to "3"), received.query)
        }
    }

    @Test
    fun `a redirect is not followed and fails as a configuration error with health down`() {
        handler.set { exchange ->
            if (exchange.requestURI.rawPath.startsWith("/moved")) {
                respond(exchange, 200, IDLE_PAGE)
            } else {
                respond(exchange, 302, "", mapOf("Location" to "http://127.0.0.1:${server.address.port}/moved"))
            }
        }
        val provider = provider()

        val failure = assertThrows<ProviderConfigurationException> { provider.fetchObservedEventsPage(pageRequest()) }

        assertEquals("Provider answered with a redirect (HTTP 302); point asset-sync.provider.base-url at the final address.", failure.message)
        assertEquals(1, requests.size, "the redirect must not be followed")
        val health = HttpChainProviderHealthIndicator(provider).health()
        assertEquals(Status.DOWN, health.status)
        assertEquals(failure.message, health.details["error"])
    }

    @Test
    fun `a rejected credential fails at once with health down, while an unknown address stays that address's error`() {
        listOf(401, 403).forEach { code ->
            handler.set { exchange -> respond(exchange, code, """{"error":"denied"}""") }
            val provider = provider()

            val failure = assertThrows<ProviderConfigurationException> { provider.fetchObservedEventsPage(pageRequest()) }

            assertEquals(
                "Provider rejected the service's credentials with HTTP $code; check asset-sync.provider.auth-header-value and base-url.",
                failure.message,
            )
            val health = HttpChainProviderHealthIndicator(provider).health()
            assertEquals(Status.DOWN, health.status, "HTTP $code")
            assertEquals(failure.message, health.details["error"])
        }

        handler.set { exchange -> respond(exchange, 200, IDLE_PAGE) }
        val provider = provider()
        provider.fetchObservedEventsPage(pageRequest())
        handler.set { exchange -> respond(exchange, 404, """{"error":"unknown address"}""") }
        assertThrows<ProviderDataInvalidException> { provider.fetchObservedEventsPage(pageRequest()) }
        val health = HttpChainProviderHealthIndicator(provider).health()
        assertEquals(Status.UP, health.status)
        assertEquals("Provider returned HTTP 404 for a watched address.", health.details["lastDataError"])
    }

    @Test
    fun `the bridge credential goes in its header and nowhere else`(output: CapturedOutput) {
        val secret = "bridge-secret-token-1234"
        val bearer = provider(ProviderProperties(baseUrl = baseUrl(), authHeaderValue = "Bearer $secret"))
        bearer.fetchObservedEventsPage(pageRequest())
        assertEquals(listOf("Bearer $secret"), requests.last().headers["authorization"])

        val apiKey = provider(ProviderProperties(baseUrl = baseUrl(), authHeaderName = "X-API-Key", authHeaderValue = secret))
        apiKey.fetchObservedEventsPage(pageRequest())
        assertEquals(listOf(secret), requests.last().headers["x-api-key"])
        assertEquals(null, requests.last().headers["authorization"])

        handler.set { exchange -> respond(exchange, 401, "") }
        val failure = assertThrows<ProviderConfigurationException> { apiKey.fetchObservedEventsPage(pageRequest()) }
        listOf(
            failure.message.orEmpty(),
            HttpChainProviderHealthIndicator(apiKey).health().toString(),
            ProviderProperties(baseUrl = baseUrl(), authHeaderValue = secret).toString(),
            output.out,
        ).forEach { text -> assertFalse(text.contains(secret), "the credential leaked into: $text") }
    }

    @Test
    fun `a credential header that could not be sent stops startup without quoting the value`() {
        val badName = assertThrows<IllegalArgumentException> { ProviderProperties(authHeaderName = "X API Key") }
        assertEquals("asset-sync.provider.auth-header-name must be an HTTP header name.", badName.message)

        val injected = assertThrows<IllegalArgumentException> { ProviderProperties(authHeaderValue = "Bearer t0ken\r\nX-Injected: 1") }
        assertEquals("asset-sync.provider.auth-header-value must not contain a line break.", injected.message)
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

    private fun provider(properties: ProviderProperties = ProviderProperties(baseUrl = "http://127.0.0.1:${server.address.port}")): HttpChainProvider =
        HttpChainProvider(
            chainProviderRestClient = ProviderConfiguration().chainProviderRestClient(properties),
            objectMapper = jacksonObjectMapper(),
            syncProperties = SyncProperties(),
        )

    private fun pageRequest(cursor: String? = null, fromBlockHeight: Long? = null, fromEventIndex: Int? = null): ChainProviderEventsPageRequest =
        ChainProviderEventsPageRequest(
            watchedAddressId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            chainId = "local-evm",
            address = "0xbridge",
            asset = "USDC",
            cursor = cursor,
            limit = 100,
            fromBlockHeight = fromBlockHeight,
            fromEventIndex = fromEventIndex,
        )

    private data class RecordedRequest(val path: String, val query: Map<String, String>, val headers: Map<String, List<String>>)

    private companion object {
        const val IDLE_PAGE = """{"events":[],"nextCursor":"bridge-next","hasMore":false}"""

        /** Decodes a query the way a servlet container does, including `+` as a space. */
        fun queryParams(rawQuery: String?): Map<String, String> =
            rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }

        fun respond(exchange: HttpExchange, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
