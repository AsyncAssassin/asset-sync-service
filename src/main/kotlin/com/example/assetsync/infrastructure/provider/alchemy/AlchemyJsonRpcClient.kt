package com.example.assetsync.infrastructure.provider.alchemy

import com.example.assetsync.application.observability.AssetSyncMetrics
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.config.AlchemyAuthMode
import com.example.assetsync.config.AlchemyProviderProperties
import com.example.assetsync.infrastructure.provider.ProviderHttpSupport
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.Timer
import java.io.InputStream
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

/**
 * Minimal Alchemy JSON-RPC client. It is the only place that builds Alchemy URLs or attaches the
 * API key, and every exception it throws carries a bounded, scrubbed message: never the endpoint,
 * the authorization header, or the provider's error text. HTTP 401/403 and the JSON-RPC
 * `-32600` envelope are configuration failures (terminal for a sync run); 429, 408, 5xx, and
 * transport errors are retryable outages; malformed payloads are provider-data failures. The
 * optional local rate limiter runs before every request, bounded by the caller's deadline, and the
 * optional metrics record every call with its outcome and duration.
 */
class AlchemyJsonRpcClient(
    private val restClient: RestClient,
    private val properties: AlchemyProviderProperties,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val rateLimiter: AlchemyRateLimiter? = null,
    private val metrics: AssetSyncMetrics? = null,
) {

    private val logger = LoggerFactory.getLogger(AlchemyJsonRpcClient::class.java)
    private val scrubber = AlchemySecretScrubber(properties.apiKey)
    private val requestIds = AtomicLong()

    /** `eth_blockNumber`: the cheapest call that proves the key, the endpoint, and the auth mode work. */
    fun blockNumber(network: String, deadline: Instant? = null): Long =
        parseHexQuantity(
            call(network = network, method = "eth_blockNumber", params = emptyList(), deadline = deadline),
            "eth_blockNumber result",
        )

    /**
     * `eth_getBlockByNumber(tag, false)` for a finality tag such as `safe` or `finalized`; null
     * when the node answers with a null block (tag unknown or not yet available).
     */
    fun blockNumberByTag(network: String, tag: String, deadline: Instant? = null): Long? {
        val block = call(
            network = network,
            method = "eth_getBlockByNumber",
            params = listOf(tag, false),
            deadline = deadline,
            allowNullResult = true,
        )
        if (block.isNull) {
            return null
        }
        if (!block.isObject) {
            throw ProviderDataInvalidException("Alchemy eth_getBlockByNumber result for tag $tag is not a block object.")
        }
        val number = block.get("number")
            ?: throw ProviderDataInvalidException("Alchemy eth_getBlockByNumber result for tag $tag has no number.")
        return parseHexQuantity(number, "eth_getBlockByNumber number for tag $tag")
    }

    /** `alchemy_getAssetTransfers` for one stream of one block range; `pageKey` stays in memory. */
    fun assetTransfers(network: String, params: AlchemyTransfersParams, deadline: Instant? = null): AlchemyTransfersResult {
        val result = call(network = network, method = "alchemy_getAssetTransfers", params = listOf(params), deadline = deadline)
        val parsed = try {
            objectMapper.treeToValue(result, AlchemyTransfersResult::class.java)
        } catch (exception: JsonProcessingException) {
            throw ProviderDataInvalidException("Alchemy transfers response for network $network has an unexpected shape.")
        } catch (exception: IllegalArgumentException) {
            throw ProviderDataInvalidException("Alchemy transfers response for network $network has an unexpected shape.")
        }
        if (parsed.transfers == null) {
            throw ProviderDataInvalidException("Alchemy transfers response for network $network is missing the transfers field.")
        }
        return parsed
    }

    fun call(
        network: String,
        method: String,
        params: List<Any?>,
        deadline: Instant? = null,
        allowNullResult: Boolean = false,
    ): JsonNode {
        val meters = metrics
        val sample = meters?.startAlchemyRpcTimer()
        return try {
            rateLimiter?.acquire(deadline)
            val payload = objectMapper.writeValueAsBytes(
                JsonRpcRequest(id = requestIds.incrementAndGet(), method = method, params = params),
            )
            val result = requireNotNull(
                restClient
                    .post()
                    .uri(endpointFor(network))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers { headers ->
                        if (properties.authMode == AlchemyAuthMode.HEADER) {
                            headers.setBearerAuth(properties.apiKey)
                        }
                    }
                    .body(payload)
                    .exchange { _, response ->
                        handleResponse(network = network, method = method, response = response, allowNullResult = allowNullResult)
                    },
            ) { "Alchemy exchange returned no result." }
            logger.debug("alchemy_rpc_succeeded network={} method={}", network, method)
            if (meters != null && sample != null) {
                meters.recordAlchemyRpc(network, method, RESULT_SUCCEEDED, sample)
            }
            result
        } catch (exception: ProviderConfigurationException) {
            throw failed(network, method, exception, sample, RESULT_CONFIGURATION)
        } catch (exception: ChainProviderUnavailableException) {
            throw failed(network, method, exception, sample, RESULT_UNAVAILABLE)
        } catch (exception: ProviderDataInvalidException) {
            throw failed(network, method, exception, sample, RESULT_INVALID)
        } catch (exception: RuntimeException) {
            // Transport failures from RestClient embed the request URL, which carries the key in path
            // mode: rethrow a scrubbed, bounded message and deliberately drop the original cause.
            val message = scrubber
                .scrub("Alchemy request failed for network $network: ${exception.javaClass.simpleName}: ${exception.message}")
                .take(MAX_MESSAGE_LENGTH)
            throw failed(network, method, ChainProviderUnavailableException(message), sample, RESULT_UNAVAILABLE)
        }
    }

    private fun endpointFor(network: String): URI =
        when (properties.authMode) {
            AlchemyAuthMode.HEADER -> URI.create(
                properties.endpointTemplate.replace(AlchemyProviderProperties.NETWORK_PLACEHOLDER, network),
            )
            AlchemyAuthMode.PATH -> URI.create(
                properties.pathEndpointTemplate
                    .replace(AlchemyProviderProperties.NETWORK_PLACEHOLDER, network)
                    .replace(AlchemyProviderProperties.API_KEY_PLACEHOLDER, properties.apiKey),
            )
        }

    private fun handleResponse(
        network: String,
        method: String,
        response: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse,
        allowNullResult: Boolean,
    ): JsonNode {
        val status = response.statusCode
        val code = status.value()
        return when {
            status.is2xxSuccessful -> parseResult(network = network, method = method, body = response.body, allowNullResult = allowNullResult)
            code == HttpStatus.UNAUTHORIZED.value() || code == HttpStatus.FORBIDDEN.value() ->
                throw ProviderConfigurationException(
                    "Alchemy returned HTTP $code for network $network; check the API key, the app status, and its allowlist.",
                )
            code == HttpStatus.TOO_MANY_REQUESTS.value() ->
                throw ChainProviderUnavailableException(
                    message = "Alchemy rate limited the request with HTTP 429 for network $network.",
                    retryAfter = ProviderHttpSupport.parseRetryAfter(response.headers.getFirst(HttpHeaders.RETRY_AFTER)),
                )
            code == HttpStatus.REQUEST_TIMEOUT.value() || status.is5xxServerError ->
                throw ChainProviderUnavailableException("Alchemy returned HTTP $code for network $network.")
            status.is4xxClientError ->
                throw ProviderDataInvalidException("Alchemy returned HTTP $code for $method on network $network.")
            else ->
                throw ChainProviderUnavailableException("Alchemy returned unexpected HTTP $code for network $network.")
        }
    }

    private fun parseResult(network: String, method: String, body: InputStream, allowNullResult: Boolean): JsonNode {
        val bytes = ProviderHttpSupport.readBounded(body, maxResponseBytes) {
            ProviderDataInvalidException("Alchemy response for $method on network $network exceeded the configured byte limit.")
        }
        val node = try {
            objectMapper.readTree(bytes)
        } catch (exception: JsonProcessingException) {
            throw ProviderDataInvalidException("Alchemy returned malformed JSON for $method on network $network.")
        }
        if (node == null || node.isMissingNode || !node.isObject) {
            throw ProviderDataInvalidException("Alchemy returned a non-object JSON-RPC response for $method on network $network.")
        }
        val error = node.get("error")
        if (error != null && !error.isNull) {
            throw classifyJsonRpcError(network = network, method = method, error = error)
        }
        val result = node.get("result")
        if (result == null || result.isNull) {
            if (allowNullResult && result != null) {
                return result
            }
            throw ProviderDataInvalidException("Alchemy JSON-RPC response for $method on network $network has no result.")
        }
        return result
    }

    /** Only the numeric code is kept: provider error text is never copied into messages. */
    private fun classifyJsonRpcError(network: String, method: String, error: JsonNode): RuntimeException {
        val code = error.get("code")?.takeIf { it.isIntegralNumber }?.asInt()
        return when (code) {
            JSON_RPC_INVALID_REQUEST -> ProviderConfigurationException(
                "Alchemy JSON-RPC error $code for $method on network $network; the request was rejected as invalid or unauthenticated.",
            )
            JSON_RPC_PARSE_ERROR, JSON_RPC_METHOD_NOT_FOUND, JSON_RPC_INVALID_PARAMS -> ProviderDataInvalidException(
                "Alchemy JSON-RPC error $code for $method on network $network.",
            )
            else -> ChainProviderUnavailableException(
                "Alchemy JSON-RPC error ${code ?: "without a code"} for $method on network $network.",
            )
        }
    }

    private fun parseHexQuantity(node: JsonNode, what: String): Long {
        val text = node.takeIf { it.isTextual }?.asText()
            ?: throw ProviderDataInvalidException("Alchemy $what is not a hex quantity string.")
        if (!HEX_QUANTITY.matches(text)) {
            throw ProviderDataInvalidException("Alchemy $what is not a valid hex quantity.")
        }
        return text.substring(2).toLong(radix = 16)
    }

    private fun <T : RuntimeException> failed(
        network: String,
        method: String,
        exception: T,
        sample: Timer.Sample?,
        result: String,
    ): T {
        logger.warn("alchemy_rpc_failed network={} method={} error={}", network, method, scrubber.scrub(exception.message))
        sample?.let { metrics?.recordAlchemyRpc(network, method, result, it) }
        return exception
    }

    private data class JsonRpcRequest(
        val jsonrpc: String = "2.0",
        val id: Long,
        val method: String,
        val params: List<Any?>,
    )

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024
        const val RESULT_SUCCEEDED = "SUCCEEDED"
        const val RESULT_UNAVAILABLE = "UNAVAILABLE"
        const val RESULT_INVALID = "INVALID"
        const val RESULT_CONFIGURATION = "CONFIGURATION"
        private const val MAX_MESSAGE_LENGTH = 240
        private const val JSON_RPC_PARSE_ERROR = -32700
        private const val JSON_RPC_INVALID_REQUEST = -32600
        private const val JSON_RPC_METHOD_NOT_FOUND = -32601
        private const val JSON_RPC_INVALID_PARAMS = -32602
        private val HEX_QUANTITY = Regex("^0x[0-9a-fA-F]{1,15}$")
    }
}
