package com.example.assetsync

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-test stand-in for an Alchemy JSON-RPC endpoint: records every request (path, authorization
 * header, JSON-RPC method and params, raw body) and answers either through a programmable
 * [responder] or with the fixed status, body, and `Retry-After` fields. Tests point the endpoint
 * templates at it, so `{network}` becomes a path segment here instead of a subdomain.
 */
class AlchemyJsonRpcStubServer : AutoCloseable {

    data class RecordedRequest(
        val path: String,
        val authorization: String?,
        val method: String?,
        val params: JsonNode?,
        val body: String,
    )

    data class StubResponse(
        val body: String,
        val status: Int = 200,
        val retryAfter: String? = null,
        /** Held back before the response is written, to trigger the client's read timeout. */
        val delay: Duration? = null,
    )

    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** When set, decides every response; otherwise the fixed fields below apply. */
    @Volatile
    var responder: ((RecordedRequest) -> StubResponse)? = null

    @Volatile
    var responseStatus: Int = 200

    @Volatile
    var responseBody: String = DEFAULT_BODY

    @Volatile
    var retryAfter: String? = null

    private val objectMapper = jacksonObjectMapper()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val parsed = runCatching { objectMapper.readTree(body) }.getOrNull()
            val request = RecordedRequest(
                path = exchange.requestURI.path,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                method = parsed?.get("method")?.takeIf { it.isTextual }?.asText(),
                params = parsed?.get("params"),
                body = body,
            )
            requests += request
            val response = responder?.invoke(request)
                ?: StubResponse(body = responseBody, status = responseStatus, retryAfter = retryAfter)
            val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            response.retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
            response.delay?.let { Thread.sleep(it.toMillis()) }
            runCatching {
                exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        start()
    }

    val port: Int
        get() = server.address.port

    fun headerEndpointTemplate(): String = "http://localhost:$port/{network}/v2/"

    fun pathEndpointTemplate(): String = "http://localhost:$port/{network}/v2/{apiKey}"

    fun reset() {
        requests.clear()
        responder = null
        responseStatus = 200
        responseBody = DEFAULT_BODY
        retryAfter = null
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        const val DEFAULT_BODY = """{"jsonrpc":"2.0","id":1,"result":"0x10"}"""

        fun result(json: String): StubResponse = StubResponse("""{"jsonrpc":"2.0","id":1,"result":$json}""")

        fun error(code: Int, message: String): StubResponse =
            StubResponse("""{"jsonrpc":"2.0","id":1,"error":{"code":$code,"message":"$message"}}""")
    }
}
