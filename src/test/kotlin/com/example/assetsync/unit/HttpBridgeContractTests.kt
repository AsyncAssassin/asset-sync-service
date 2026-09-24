package com.example.assetsync.unit

import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.config.ProviderConfiguration
import com.example.assetsync.config.ProviderProperties
import com.example.assetsync.config.SyncProperties
import com.example.assetsync.infrastructure.provider.HttpChainProvider
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

/**
 * The HTTP bridge contract as the adapter speaks it (`docs/architecture.md`, HTTP Bridge Page
 * Contract), against a JDK `HttpServer` stub that records every request it receives. The client
 * comes from `ProviderConfiguration`, as in production.
 */
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
