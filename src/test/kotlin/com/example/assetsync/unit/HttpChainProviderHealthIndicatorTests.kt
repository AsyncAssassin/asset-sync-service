package com.example.assetsync.unit

import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.config.JacksonConfiguration
import com.example.assetsync.config.JacksonConfiguration.Companion.MAX_JSON_STRING_LENGTH
import com.example.assetsync.config.SyncProperties
import com.example.assetsync.infrastructure.provider.HttpChainProvider
import com.example.assetsync.infrastructure.provider.HttpChainProviderHealthIndicator
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
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
    fun `a data error for one address keeps health up with a detail while a 5xx turns it down`() {
        responseStatus.set(200)
        provider.fetchObservedEventsPage(pageRequest())

        responseStatus.set(404)
        assertThrows<ProviderDataInvalidException> { provider.fetchObservedEventsPage(pageRequest()) }
        val afterDataError = indicator.health()
        assertEquals(Status.UP, afterDataError.status, "a 4xx for one address is not a provider outage")
        assertEquals("Provider returned HTTP 404 for a watched address.", afterDataError.details["lastDataError"])

        responseStatus.set(200)
        responseBody.set("""{"events":"not-a-list","hasMore":false}""")
        assertThrows<ProviderDataInvalidException> { provider.fetchObservedEventsPage(pageRequest()) }
        assertEquals(Status.UP, indicator.health().status, "malformed JSON is a data error too")

        responseStatus.set(503)
        assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest()) }
        val down = indicator.health()
        assertEquals(Status.DOWN, down.status)
        assertEquals("Provider returned HTTP 503.", down.details["error"])
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
    fun `unknown fields are skipped at any length while a known string past the json limit fails the page`() {
        // The mapper Spring builds, with the application's customizer applied.
        val builder = Jackson2ObjectMapperBuilder.json()
        JacksonConfiguration().jsonStringLengthLimit().customize(builder)
        val limitedProvider = HttpChainProvider(
            chainProviderRestClient = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build(),
            objectMapper = builder.build<ObjectMapper>(),
            syncProperties = SyncProperties(),
        )
        val long = "x".repeat(MAX_JSON_STRING_LENGTH + 1)
        responseStatus.set(200)
        responseBody.set(
            """{"extra":"$long","events":[{"note":"$long","txHash":"0xlimit","eventIndex":0,"address":"0xhealthcheck",""" +
                """"asset":"USDC","amount":"1.5","blockHeight":7,"confirmations":1,"direction":"INBOUND","status":"SEEN"}],""" +
                """"hasMore":false,"nextCursor":"limit-final"}""",
        )

        assertEquals(1, limitedProvider.fetchObservedEventsPage(pageRequest()).events.size)

        responseBody.set("""{"events":[],"hasMore":false,"nextCursor":"$long"}""")
        val failure = assertThrows<ProviderDataInvalidException> { limitedProvider.fetchObservedEventsPage(pageRequest()) }
        assertEquals(
            "Provider returned JSON past a size limit, such as a string over $MAX_JSON_STRING_LENGTH characters.",
            failure.message,
        )
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

    @Test
    fun `a null event is a data error for the address, not an outage`() {
        responseStatus.set(200)
        responseBody.set("""{"events":[null],"hasMore":false,"nextCursor":"null-event"}""")

        val failure = assertThrows<ProviderDataInvalidException> { provider.fetchObservedEventsPage(pageRequest()) }

        assertEquals("Provider response has a null element in its events field.", failure.message)
        val health = indicator.health()
        assertEquals(Status.UP, health.status)
        assertEquals(failure.message, health.details["lastDataError"])
    }

    @Test
    fun `a retry after beyond the representable range is ignored, and the 429 stays a rate limit`() {
        responseStatus.set(429)
        retryAfterHeader.set("99999999999999999")

        val throttled = assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest()) }

        assertEquals("Provider rate limited the request with HTTP 429.", throttled.message)
        assertNull(throttled.retryAfter)
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
