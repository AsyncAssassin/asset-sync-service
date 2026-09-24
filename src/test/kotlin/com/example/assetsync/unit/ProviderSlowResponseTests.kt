package com.example.assetsync.unit

import com.example.assetsync.TricklingHttpServer
import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.config.AlchemyProviderConfiguration
import com.example.assetsync.config.AlchemyProviderProperties
import com.example.assetsync.config.ProviderConfiguration
import com.example.assetsync.config.ProviderProperties
import com.example.assetsync.config.SyncProperties
import com.example.assetsync.infrastructure.provider.HttpChainProvider
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyJsonRpcClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.assertThrows

/**
 * A provider that answers slowly, with too much, or without end must not keep the thread of the
 * provider pool once the fetch has given up. The read stops when the provider timeout cancels the
 * fetch, and a body that is refused or never read is closed unread: Spring's `close()` of the
 * response drains what is left, which kept the thread reading the whole body. Both adapters share
 * the bounded read and the production clients, so both are covered.
 */
class ProviderSlowResponseTests {

    private val servers = mutableListOf<TricklingHttpServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { it.close() }
    }

    @Test
    fun `a cancelled fetch of a trickling bridge page frees the provider thread`() {
        val server = trickling()
        val provider = bridge(server)

        assertThreadFreedAfterCancel { provider.fetchObservedEventsPage(pageRequest()) }
        assertTrue(server.awaitClientGone(Duration.ofSeconds(2)), "the connection must be dropped, not drained")
    }

    @Test
    fun `an oversized bridge body that keeps coming fails at the limit without being drained`() {
        val server = trickling(prefix = ByteArray(4096) { ' '.code.toByte() })
        val provider = bridge(server, maxProviderPageBytes = 1024)

        val failure = assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            assertThrows<ProviderDataInvalidException> { provider.fetchObservedEventsPage(pageRequest()) }
        }

        assertEquals("Provider response exceeded the configured byte limit.", failure.message)
        assertTrue(server.awaitClientGone(Duration.ofSeconds(2)))
    }

    @Test
    fun `a bridge error status with an endless body fails at once`() {
        val server = trickling(status = 503)
        val provider = bridge(server)

        val failure = assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest()) }
        }

        assertEquals("Provider returned HTTP 503.", failure.message)
        assertTrue(server.awaitClientGone(Duration.ofSeconds(2)))
    }

    @Test
    fun `alchemy stops reading in the same three cases`() {
        val slow = trickling()
        assertThreadFreedAfterCancel { alchemy(slow).blockNumber("eth-sepolia") }
        assertTrue(slow.awaitClientGone(Duration.ofSeconds(2)))

        val oversized = trickling(prefix = ByteArray(4096) { ' '.code.toByte() })
        assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            assertThrows<ProviderDataInvalidException> { alchemy(oversized, maxResponseBytes = 1024).blockNumber("eth-sepolia") }
        }
        assertTrue(oversized.awaitClientGone(Duration.ofSeconds(2)))

        val failing = trickling(status = 503)
        assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            assertThrows<ChainProviderUnavailableException> { alchemy(failing).blockNumber("eth-sepolia") }
        }
        assertTrue(failing.awaitClientGone(Duration.ofSeconds(2)))
    }

    /**
     * Runs [fetch] on a one-thread pool like the provider pool (`SynchronousQueue`, abort policy),
     * cancels it the way the provider timeout does, and requires the thread back soon after.
     */
    private fun assertThreadFreedAfterCancel(fetch: () -> Any) {
        val pool = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, SynchronousQueue(), ThreadPoolExecutor.AbortPolicy())
        try {
            val future = pool.submit(Callable { fetch() })
            assertThrows<TimeoutException> { future.get(300, TimeUnit.MILLISECONDS) }
            future.cancel(true)

            // The next fetch is accepted once the thread is back; a submit races the worker's return
            // to the queue, so it is retried until the deadline.
            val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
            var accepted = false
            while (!accepted && System.nanoTime() < deadline) {
                accepted = runCatching { pool.submit(Callable { "next" }).get(1, TimeUnit.SECONDS) == "next" }.getOrDefault(false)
                if (!accepted) {
                    Thread.sleep(20)
                }
            }
            assertTrue(accepted, "the provider thread is still reading after the cancel")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun trickling(status: Int = 200, prefix: ByteArray = ByteArray(0)): TricklingHttpServer =
        TricklingHttpServer(status = status, prefix = prefix).also { servers += it }

    private fun bridge(server: TricklingHttpServer, maxProviderPageBytes: Int = 1_048_576): HttpChainProvider =
        HttpChainProvider(
            chainProviderRestClient = ProviderConfiguration().chainProviderRestClient(
                ProviderProperties(baseUrl = server.baseUrl, readTimeout = Duration.ofSeconds(2)),
            ),
            objectMapper = jacksonObjectMapper(),
            syncProperties = SyncProperties(pagination = SyncProperties.Pagination(maxProviderPageBytes = maxProviderPageBytes)),
        )

    private fun alchemy(server: TricklingHttpServer, maxResponseBytes: Int = AlchemyJsonRpcClient.DEFAULT_MAX_RESPONSE_BYTES): AlchemyJsonRpcClient =
        AlchemyJsonRpcClient(
            restClient = AlchemyProviderConfiguration().alchemyRestClient(ProviderProperties(readTimeout = Duration.ofSeconds(2))),
            properties = AlchemyProviderProperties(apiKey = "slow-test-key", endpointTemplate = "${server.baseUrl}/{network}/v2/"),
            maxResponseBytes = maxResponseBytes,
        )

    private fun pageRequest(): ChainProviderEventsPageRequest =
        ChainProviderEventsPageRequest(
            watchedAddressId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            chainId = "local-evm",
            address = "0xslow",
            asset = "USDC",
            cursor = null,
            limit = 100,
        )
}
