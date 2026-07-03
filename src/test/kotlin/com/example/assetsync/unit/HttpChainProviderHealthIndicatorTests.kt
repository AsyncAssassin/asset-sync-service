package com.example.assetsync.unit

import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.account.WatchedAddressStatus
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.infrastructure.provider.HttpChainProvider
import com.example.assetsync.infrastructure.provider.HttpChainProviderHealthIndicator
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.actuate.health.Status
import org.springframework.web.client.RestClient

/**
 * Locks the health indicator's whole reason to exist: it reflects the real provider connectivity
 * (UP before any fetch, DOWN after a failure, UP again after recovery) rather than a constant UP.
 */
class HttpChainProviderHealthIndicatorTests {

    private val responseStatus = AtomicInteger(200)
    private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
        createContext("/") { exchange ->
            val code = responseStatus.get()
            val body = if (code == 200) """{"events":[]}""".toByteArray() else "boom".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    private val provider = HttpChainProvider(
        RestClient.builder().baseUrl("http://localhost:${server.address.port}").build(),
    )
    private val indicator = HttpChainProviderHealthIndicator(provider)

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `reflects provider connectivity across no-fetch, failure and recovery`() {
        assertEquals(Status.UP, indicator.health().status, "before any fetch")

        responseStatus.set(500)
        val failure = assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEvents(watchedAddress()) }
        assertIs<RuntimeException>(failure.cause)
        assertEquals(Status.DOWN, indicator.health().status, "after a failed fetch")

        responseStatus.set(200)
        provider.fetchObservedEvents(watchedAddress()).toList()
        assertEquals(Status.UP, indicator.health().status, "after recovery")
    }

    private fun watchedAddress(): WatchedAddress =
        WatchedAddress(
            id = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            chainId = "local-evm",
            address = "0xhealthcheck",
            asset = "USDC",
            label = null,
            status = WatchedAddressStatus.ACTIVE,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}
