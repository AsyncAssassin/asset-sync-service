package com.example.assetsync.unit

import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.config.SyncProperties
import com.example.assetsync.infrastructure.provider.HttpChainProvider
import com.example.assetsync.infrastructure.provider.HttpChainProviderHealthIndicator
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.actuate.health.Status
import org.springframework.web.client.RestClient

/**
 * Locks the health indicator's whole reason to exist: it reflects the real provider connectivity
 * (UP before any fetch, DOWN after a failure, UP again after recovery) rather than a constant UP.
 */
class HttpChainProviderHealthIndicatorTests {

    private val responseStatus = AtomicInteger(200)
    private val responseBody = AtomicReference("""{"events":[],"nextCursor":"health-final","hasMore":false}""")
    private val retryAfterHeader = AtomicReference<String?>(null)
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val code = responseStatus.get()
            val body = if (code == 200) responseBody.get().toByteArray() else "boom".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            retryAfterHeader.get()?.let { exchange.responseHeaders.add("Retry-After", it) }
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    private val provider = HttpChainProvider(
        RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build(),
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
        assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest()) }
        assertEquals(Status.DOWN, indicator.health().status, "after a failed fetch")

        responseStatus.set(200)
        provider.fetchObservedEventsPage(pageRequest())
        assertEquals(Status.UP, indicator.health().status, "after recovery")
    }

    @Test
    fun `missing events is malformed but explicit empty events page is valid`() {
        responseStatus.set(200)
        responseBody.set("""{"hasMore":false,"nextCursor":"missing-events"}""")
        assertThrows<ProviderDataInvalidException> { provider.fetchObservedEventsPage(pageRequest()) }

        responseBody.set("""{"events":[],"hasMore":false,"nextCursor":"explicit-empty"}""")
        val page = provider.fetchObservedEventsPage(pageRequest())

        assertEquals(emptyList(), page.events)
        assertEquals("explicit-empty", page.nextCursor)
        assertEquals(false, page.hasMore)
    }

    @Test
    fun `provider response over byte cap is rejected before JSON parse`() {
        responseStatus.set(200)
        responseBody.set("""{"events":[],"hasMore":false,"nextCursor":"${"x".repeat(200)}"}""")
        val cappedProvider = HttpChainProvider(
            chainProviderRestClient = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build(),
            objectMapper = jacksonObjectMapper(),
            syncProperties = SyncProperties(
                pagination = SyncProperties.Pagination(maxProviderPageBytes = 32),
            ),
        )

        assertThrows<ProviderDataInvalidException> { cappedProvider.fetchObservedEventsPage(pageRequest()) }
    }

    @Test
    fun `http 429 is retryable and parses valid retry after while ignoring invalid values`() {
        responseStatus.set(429)
        retryAfterHeader.set("2")
        val before = java.time.Instant.now()

        val throttled = assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest()) }

        val retryAfter = assertNotNull(throttled.retryAfter)
        assertTrue(retryAfter.isAfter(before))

        retryAfterHeader.set("not-a-date")
        val invalidRetryAfter = assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest()) }
        assertNull(invalidRetryAfter.retryAfter)
    }

    private fun pageRequest(): ChainProviderEventsPageRequest =
        ChainProviderEventsPageRequest(
            watchedAddressId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            chainId = "local-evm",
            address = "0xhealthcheck",
            asset = "USDC",
            cursor = null,
            limit = 100,
        )
}
