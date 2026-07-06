package com.example.assetsync.infrastructure.provider

import com.example.assetsync.application.sync.ChainProviderEventsPage
import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.config.SyncProperties
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Real chain provider: fetches observed events over HTTP from a configured endpoint (a real
 * indexer in prod; the bundled simulator in demo; a WireMock stub in the e2e tests). Active on
 * every non-local/test profile — this is the bean that makes a prod context bootable.
 */
@Component
@Profile("!local & !test")
class HttpChainProvider @Autowired constructor(
    private val chainProviderRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val syncProperties: SyncProperties,
) : ChainProviderPort {

    constructor(chainProviderRestClient: RestClient) : this(
        chainProviderRestClient = chainProviderRestClient,
        objectMapper = jacksonObjectMapper(),
        syncProperties = SyncProperties(),
    )

    private val logger = LoggerFactory.getLogger(HttpChainProvider::class.java)

    @Volatile
    private var lastFetchHealthy: Boolean? = null

    @Volatile
    private var lastError: String? = null

    override fun fetchObservedEventsPage(request: ChainProviderEventsPageRequest): ChainProviderEventsPage =
        try {
            val page = requireNotNull(
                chainProviderRestClient
                    .get()
                    .uri { uriBuilder ->
                        val builder = uriBuilder
                            .path("/v1/chains/{chainId}/addresses/{address}/events")
                            .queryParam("asset", request.asset)
                            .queryParam("limit", request.limit)
                        if (request.cursor != null) {
                            builder.queryParam("cursor", request.cursor)
                        }
                        builder.build(request.chainId, request.address)
                    }
                    .exchange { _, response ->
                        val statusCode = response.statusCode
                        when {
                            statusCode.is2xxSuccessful -> parseSuccessfulResponse(request = request, body = response.body)
                            statusCode.value() == HttpStatus.REQUEST_TIMEOUT.value() ->
                                throw ChainProviderUnavailableException("Provider request timed out with HTTP ${statusCode.value()}.")
                            statusCode.value() == HttpStatus.TOO_MANY_REQUESTS.value() ->
                                throw ChainProviderUnavailableException(
                                    message = "Provider rate limited the request with HTTP 429.",
                                    retryAfter = parseRetryAfter(response.headers.getFirst(HttpHeaders.RETRY_AFTER)),
                                )
                            statusCode.is5xxServerError ->
                                throw ChainProviderUnavailableException("Provider returned HTTP ${statusCode.value()}.")
                            statusCode.is4xxClientError ->
                                throw ProviderDataInvalidException("Provider returned HTTP ${statusCode.value()} for a watched address.")
                            else ->
                                throw ChainProviderUnavailableException("Provider returned unexpected HTTP ${statusCode.value()}.")
                        }
                    },
            ) { "Provider exchange returned no page." }
            lastFetchHealthy = true
            lastError = null
            logger.info(
                "http_provider_page_fetch_succeeded chainId={} address={} asset={} limit={} events={} hasMore={}",
                request.chainId,
                request.address,
                request.asset,
                request.limit,
                page.events.size,
                page.hasMore,
            )
            page
        } catch (exception: ProviderDataInvalidException) {
            recordFailure(request = request, exception = exception)
            throw exception
        } catch (exception: ChainProviderUnavailableException) {
            recordFailure(request = request, exception = exception)
            throw exception
        } catch (exception: RuntimeException) {
            recordFailure(request = request, exception = exception)
            throw ChainProviderUnavailableException(exception.message ?: "Provider request failed.", exception)
        }

    fun lastFetchHealthy(): Boolean? = lastFetchHealthy

    fun lastError(): String? = lastError

    private fun parseSuccessfulResponse(request: ChainProviderEventsPageRequest, body: InputStream): ChainProviderEventsPage {
        val bytes = readBounded(body, syncProperties.pagination.maxProviderPageBytes)
        val response = try {
            objectMapper.readValue(bytes, ProviderEventsPageResponse::class.java)
        } catch (exception: JsonProcessingException) {
            throw ProviderDataInvalidException("Provider returned malformed JSON.", exception)
        }

        val events = response.events
            ?: throw ProviderDataInvalidException("Provider response is missing required events field.")
        val hasMore = response.hasMore
            ?: throw ProviderDataInvalidException("Provider response is missing required hasMore field.")
        val nextCursor = normalizeCursor(response)
        return ChainProviderEventsPage(
            events = events.map { it.toDomain(request.chainId) },
            nextCursor = nextCursor,
            hasMore = hasMore,
            latestBlockHeight = response.latestBlockHeight,
            safeBlockHeight = response.safeBlockHeight,
            metadata = response.metadata,
        )
    }

    private fun normalizeCursor(response: ProviderEventsPageResponse): String? {
        if (response.nextCursor != null && response.resumeCursor != null && response.nextCursor != response.resumeCursor) {
            throw ProviderDataInvalidException("Provider returned conflicting nextCursor and resumeCursor values.")
        }
        return response.resumeCursor ?: response.nextCursor
    }

    private fun readBounded(body: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
        var total = 0
        while (true) {
            val read = body.read(buffer)
            if (read < 0) {
                return output.toByteArray()
            }
            total += read
            if (total > maxBytes) {
                throw ProviderDataInvalidException("Provider response exceeded the configured byte limit.")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun parseRetryAfter(value: String?): Instant? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        trimmed.toLongOrNull()?.let { seconds ->
            if (seconds <= 0) {
                return null
            }
            return Instant.now().plusSeconds(seconds)
        }
        val parsed = runCatching {
            Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(trimmed))
        }.getOrNull()
        return parsed?.takeIf { it.isAfter(Instant.now()) }
    }

    private fun recordFailure(request: ChainProviderEventsPageRequest, exception: RuntimeException) {
        lastFetchHealthy = false
        lastError = exception.message?.take(240)
        logger.warn(
            "http_provider_page_fetch_failed chainId={} address={} asset={} limit={} error={}",
            request.chainId,
            request.address,
            request.asset,
            request.limit,
            lastError,
        )
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
    }
}

data class ProviderEventsPageResponse(
    val events: List<ProviderEvent>? = null,
    val nextCursor: String? = null,
    val resumeCursor: String? = null,
    val hasMore: Boolean? = null,
    val latestBlockHeight: Long? = null,
    val safeBlockHeight: Long? = null,
    val metadata: ObjectNode? = null,
)

data class ProviderEvent(
    val txHash: String,
    val eventIndex: Int,
    val address: String,
    val asset: String,
    val amount: BigDecimal,
    val blockHeight: Long,
    val confirmations: Int,
    val direction: Direction,
    val status: TransactionStatus,
) {
    fun toDomain(chainId: String): ChainProviderObservedEvent =
        ChainProviderObservedEvent(
            chainId = chainId,
            txHash = txHash,
            eventIndex = eventIndex,
            address = address,
            asset = asset,
            amount = amount,
            blockHeight = blockHeight,
            confirmations = confirmations,
            direction = direction,
            status = status,
        )
}
