package com.example.assetsync

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-test stand-in for an Alchemy JSON-RPC endpoint: records every request (path, authorization
 * header, JSON-RPC method, raw body) and answers with a configurable status, body, and
 * `Retry-After` header. Tests point the endpoint templates at it, so `{network}` becomes a path
 * segment here instead of a subdomain.
 */
class AlchemyJsonRpcStubServer : AutoCloseable {

    data class RecordedRequest(
        val path: String,
        val authorization: String?,
        val method: String?,
        val body: String,
    )

    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile
    var responseStatus: Int = 200

    @Volatile
    var responseBody: String = """{"jsonrpc":"2.0","id":1,"result":"0x10"}"""

    @Volatile
    var retryAfter: String? = null

    private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            requests += RecordedRequest(
                path = exchange.requestURI.path,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                method = METHOD_PATTERN.find(body)?.groupValues?.get(1),
                body = body,
            )
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val port: Int
        get() = server.address.port

    fun headerEndpointTemplate(): String = "http://localhost:$port/{network}/v2/"

    fun pathEndpointTemplate(): String = "http://localhost:$port/{network}/v2/{apiKey}"

    fun reset() {
        requests.clear()
        responseStatus = 200
        responseBody = """{"jsonrpc":"2.0","id":1,"result":"0x10"}"""
        retryAfter = null
    }

    override fun close() {
        server.stop(0)
    }

    private companion object {
        val METHOD_PATTERN = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"")
    }
}
