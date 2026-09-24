package com.example.assetsync

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Answers every request with [status] and a chunked body that does not end in time: [prefix] at
 * once, then one byte every [interval] until the client goes away or [maxDuration] passes. It
 * shows whether a client stops reading on its own terms: [clientGone] opens once a write fails
 * because the client closed the connection. It binds 127.0.0.1, like the other stubs.
 */
class TricklingHttpServer(
    private val status: Int = 200,
    private val prefix: ByteArray = ByteArray(0),
    private val interval: Duration = Duration.ofMillis(50),
    private val maxDuration: Duration = Duration.ofSeconds(20),
) : AutoCloseable {

    val bytesWritten = AtomicLong()
    val clientGone = CountDownLatch(1)

    private val executor = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(AlchemyJsonRpcStubServer.LOOPBACK, 0), 0).apply {
        createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            exchange.responseHeaders.add("Content-Type", "application/json")
            try {
                exchange.sendResponseHeaders(status, 0)
                exchange.responseBody.use { body ->
                    body.write(prefix)
                    body.flush()
                    bytesWritten.addAndGet(prefix.size.toLong())
                    val endAt = System.nanoTime() + maxDuration.toNanos()
                    while (System.nanoTime() < endAt) {
                        body.write(' '.code)
                        body.flush()
                        bytesWritten.incrementAndGet()
                        Thread.sleep(interval.toMillis())
                    }
                }
            } catch (_: IOException) {
                clientGone.countDown()
            }
        }
        executor = this@TricklingHttpServer.executor
        start()
    }

    val baseUrl: String
        get() = "http://${AlchemyJsonRpcStubServer.LOOPBACK}:${server.address.port}"

    /** Whether the client dropped the connection within [timeout] instead of reading on. */
    fun awaitClientGone(timeout: Duration): Boolean = clientGone.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
