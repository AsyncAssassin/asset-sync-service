package com.example.assetsync.unit

import com.example.assetsync.AlchemyJsonRpcStubServer
import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.application.outbox.OutboxEventRepository
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.config.AlchemyAuthMode
import com.example.assetsync.config.AlchemyProviderProperties
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyJsonRpcClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.net.ServerSocket
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.web.client.RestClient

/**
 * Locks the JSON-RPC client contract against a stub endpoint: how the key travels in each auth
 * mode, how HTTP statuses and JSON-RPC error envelopes map onto the three provider exception
 * classes, and that no message ever carries the key, not even when a transport failure embeds the
 * request URL of path mode.
 */
class AlchemyJsonRpcClientTests {

    private val apiKey = "secret-key-123"
    private val stub = AlchemyJsonRpcStubServer()
    private val meterRegistry = SimpleMeterRegistry()
    private val metrics = AssetSyncMetrics(meterRegistry, Mockito.mock(OutboxEventRepository::class.java))

    @AfterTest
    fun tearDown() {
        stub.close()
    }

    @Test
    fun `header mode posts eth_blockNumber with a bearer token and parses the hex result`() {
        stub.responseBody = """{"jsonrpc":"2.0","id":1,"result":"0x1a"}"""

        assertEquals(26, client().blockNumber("eth-sepolia"))
        assertEquals(1.0, rpcCount("SUCCEEDED"))
        assertEquals(1L, meterRegistry.find("asset.sync.provider.alchemy.rpc.duration").tag("method", "eth_blockNumber").timer()?.count())

        val request = stub.requests.single()
        assertEquals("/eth-sepolia/v2/", request.path)
        assertEquals("Bearer $apiKey", request.authorization)
        assertEquals("eth_blockNumber", request.method)
        assertTrue(request.body.contains("\"jsonrpc\":\"2.0\""), request.body)
        assertTrue(request.body.contains("\"params\":[]"), request.body)
    }

    @Test
    fun `path mode puts the key into the url and sends no authorization header`() {
        assertEquals(16, client(authMode = AlchemyAuthMode.PATH).blockNumber("eth-sepolia"))

        val request = stub.requests.single()
        assertEquals("/eth-sepolia/v2/$apiKey", request.path)
        assertNull(request.authorization)
    }

    @Test
    fun `http 401 and 403 are terminal configuration failures whose messages never contain the key`() {
        listOf(AlchemyAuthMode.HEADER, AlchemyAuthMode.PATH).forEach { mode ->
            listOf(401, 403).forEach { status ->
                stub.responseStatus = status
                stub.responseBody = """{"jsonrpc":"2.0","error":{"code":-32600,"message":"Must be authenticated! key=$apiKey"}}"""

                val exception = assertThrows<ProviderConfigurationException> { client(authMode = mode).blockNumber("eth-sepolia") }

                assertTrue(exception.message!!.contains("HTTP $status for network eth-sepolia"), exception.message)
                assertNoSecret(exception.message)
            }
        }
        assertEquals(4.0, rpcCount("CONFIGURATION"))
        assertEquals(0.0, rpcCount("SUCCEEDED"))
    }

    @Test
    fun `http 429 is retryable with retry after while 408 and 5xx are retryable outages`() {
        stub.responseStatus = 429
        stub.retryAfter = "2"
        val before = Instant.now()
        val throttled = assertThrows<ChainProviderUnavailableException> { client().blockNumber("eth-sepolia") }
        val retryAfter = assertNotNull(throttled.retryAfter)
        assertTrue(retryAfter.isAfter(before))
        assertTrue(throttled.message!!.contains("HTTP 429"), throttled.message)

        stub.retryAfter = null
        listOf(408, 500, 502, 503).forEach { status ->
            stub.responseStatus = status
            val outage = assertThrows<ChainProviderUnavailableException> { client().blockNumber("eth-sepolia") }
            assertTrue(outage.message!!.contains("HTTP $status"), outage.message)
            assertNull(outage.retryAfter)
        }
    }

    @Test
    fun `other 4xx statuses are provider data failures`() {
        stub.responseStatus = 400

        val exception = assertThrows<ProviderDataInvalidException> { client().blockNumber("eth-sepolia") }

        assertTrue(exception.message!!.contains("HTTP 400 for eth_blockNumber on network eth-sepolia"), exception.message)
    }

    @Test
    fun `json rpc error envelopes are classified by code without copying the provider message`() {
        stub.responseBody = """{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Invalid access key $apiKey"}}"""
        val unauthenticated = assertThrows<ProviderConfigurationException> { client().blockNumber("eth-sepolia") }
        assertTrue(unauthenticated.message!!.contains("JSON-RPC error -32600"), unauthenticated.message)
        assertFalse(unauthenticated.message!!.contains("Invalid access key"), unauthenticated.message)
        assertNoSecret(unauthenticated.message)

        stub.responseBody = """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"invalid params"}}"""
        val invalidParams = assertThrows<ProviderDataInvalidException> { client().blockNumber("eth-sepolia") }
        assertTrue(invalidParams.message!!.contains("JSON-RPC error -32602"), invalidParams.message)

        stub.responseBody = """{"jsonrpc":"2.0","id":1,"error":{"code":429,"message":"capacity exceeded"}}"""
        val throughput = assertThrows<ChainProviderUnavailableException> { client().blockNumber("eth-sepolia") }
        assertTrue(throughput.message!!.contains("JSON-RPC error 429"), throughput.message)

        stub.responseBody = """{"jsonrpc":"2.0","id":1,"error":{"message":"no code"}}"""
        val withoutCode = assertThrows<ChainProviderUnavailableException> { client().blockNumber("eth-sepolia") }
        assertTrue(withoutCode.message!!.contains("without a code"), withoutCode.message)
    }

    @Test
    fun `malformed json, non-object payloads, missing or non-hex results, and oversized bodies are provider data failures`() {
        stub.responseBody = """{"jsonrpc":"2.0","""
        assertTrue(assertThrows<ProviderDataInvalidException> { client().blockNumber("eth-sepolia") }.message!!.contains("malformed JSON"))

        stub.responseBody = """[]"""
        assertTrue(assertThrows<ProviderDataInvalidException> { client().blockNumber("eth-sepolia") }.message!!.contains("non-object"))

        stub.responseBody = """{"jsonrpc":"2.0","id":1}"""
        assertTrue(assertThrows<ProviderDataInvalidException> { client().blockNumber("eth-sepolia") }.message!!.contains("has no result"))

        stub.responseBody = """{"jsonrpc":"2.0","id":1,"result":"12"}"""
        assertTrue(assertThrows<ProviderDataInvalidException> { client().blockNumber("eth-sepolia") }.message!!.contains("not a valid hex quantity"))

        stub.responseBody = """{"jsonrpc":"2.0","id":1,"result":12}"""
        assertTrue(assertThrows<ProviderDataInvalidException> { client().blockNumber("eth-sepolia") }.message!!.contains("not a hex quantity string"))

        stub.responseBody = """{"jsonrpc":"2.0","id":1,"result":"0x10","padding":"${"x".repeat(64)}"}"""
        assertTrue(
            assertThrows<ProviderDataInvalidException> { client(maxResponseBytes = 32).blockNumber("eth-sepolia") }
                .message!!.contains("exceeded the configured byte limit"),
        )
    }

    @Test
    fun `transport failures are retryable and scrubbed even when the url carries the key`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val client = client(
            authMode = AlchemyAuthMode.PATH,
            pathEndpointTemplate = "http://127.0.0.1:$closedPort/{network}/v2/{apiKey}",
        )

        val exception = assertThrows<ChainProviderUnavailableException> { client.blockNumber("eth-sepolia") }

        assertTrue(exception.message!!.startsWith("Alchemy request failed for network eth-sepolia: "), exception.message)
        assertTrue(exception.message!!.contains("***"), "the request url must be scrubbed: ${exception.message}")
        assertNoSecret(exception.message)
        assertNull(exception.cause, "the original cause embeds the unscrubbed url and must be dropped")
        assertTrue(exception.message!!.length <= 240)
        assertEquals(1.0, rpcCount("UNAVAILABLE"))
    }

    private fun rpcCount(result: String): Double =
        meterRegistry.find("asset.sync.provider.alchemy.rpc").tag("result", result).counters().sumOf { it.count() }

    private fun client(
        authMode: AlchemyAuthMode = AlchemyAuthMode.HEADER,
        pathEndpointTemplate: String = stub.pathEndpointTemplate(),
        maxResponseBytes: Int = AlchemyJsonRpcClient.DEFAULT_MAX_RESPONSE_BYTES,
    ): AlchemyJsonRpcClient =
        AlchemyJsonRpcClient(
            restClient = RestClient.builder().build(),
            properties = AlchemyProviderProperties(
                apiKey = apiKey,
                authMode = authMode,
                endpointTemplate = stub.headerEndpointTemplate(),
                pathEndpointTemplate = pathEndpointTemplate,
            ),
            maxResponseBytes = maxResponseBytes,
            metrics = metrics,
        )

    private fun assertNoSecret(message: String?) {
        assertNotNull(message)
        assertFalse(message.contains(apiKey), "message leaks the api key: $message")
    }
}
