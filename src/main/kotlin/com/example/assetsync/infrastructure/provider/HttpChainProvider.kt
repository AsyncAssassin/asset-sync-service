package com.example.assetsync.infrastructure.provider

import com.example.assetsync.application.sync.ChainProviderEventsPage
import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.config.ConditionalOnHttpChainProvider
import com.example.assetsync.config.SyncProperties
import com.example.assetsync.config.JacksonConfiguration.Companion.MAX_JSON_STRING_LENGTH
import com.example.assetsync.config.exceedsJsonReadLimit
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.time.Instant
import javax.net.ssl.SSLException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * HTTP bridge chain provider: fetches observed events over HTTP from a configured endpoint (a real
 * indexer in prod; the bundled simulator in demo; a JDK HttpServer stub in the e2e tests). Active
 * on every non-local/test profile whenever `asset-sync.provider.type` is `http` or absent — the
 * bean that makes a default prod context bootable.
 */
@Component
@Profile("!local & !test")
@ConditionalOnHttpChainProvider
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

    override val providerName: String = "http"

    private val logger = LoggerFactory.getLogger(HttpChainProvider::class.java)

    @Volatile
    private var lastFetchHealthy: Boolean? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastDataError: String? = null

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
                        // The durable checkpoint, so a bridge resumes from it when no cursor is sent.
                        request.fromBlockHeight?.let { builder.queryParam("fromBlockHeight", it) }
                        request.fromEventIndex?.let { builder.queryParam("fromEventIndex", it) }
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
            lastDataError = null
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
            recordDataError(request = request, exception = exception)
            throw exception
        } catch (exception: ChainProviderUnavailableException) {
            recordFailure(request = request, exception = exception)
            throw exception
        } catch (exception: RuntimeException) {
            val failure = ChainProviderUnavailableException(failureMessage(exception))
            recordFailure(request = request, exception = failure, causes = exception.causeChainWithoutUrls())
            throw failure
        }

    fun lastFetchHealthy(): Boolean? = lastFetchHealthy

    fun lastError(): String? = lastError

    fun lastDataError(): String? = lastDataError

    private fun parseSuccessfulResponse(request: ChainProviderEventsPageRequest, body: InputStream): ChainProviderEventsPage {
        val bytes = readBounded(body, syncProperties.pagination.maxProviderPageBytes)
        val response = try {
            objectMapper.readValue(bytes, ProviderEventsPageResponse::class.java)
        } catch (exception: JsonProcessingException) {
            val reason = if (exception.exceedsJsonReadLimit()) {
                "Provider returned JSON past a size limit, such as a string over $MAX_JSON_STRING_LENGTH characters."
            } else {
                "Provider returned malformed JSON."
            }
            throw ProviderDataInvalidException(reason, exception)
        }

        val events = response.events?.let { listed ->
            listed.filterNotNull().takeIf { it.size == listed.size }
                ?: throw ProviderDataInvalidException("Provider response has a null element in its events field.")
        } ?: throw ProviderDataInvalidException("Provider response is missing required events field.")
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

    private fun readBounded(body: InputStream, maxBytes: Int): ByteArray =
        ProviderHttpSupport.readBounded(body, maxBytes) {
            ProviderDataInvalidException("Provider response exceeded the configured byte limit.")
        }

    private fun parseRetryAfter(value: String?): Instant? = ProviderHttpSupport.parseRetryAfter(value)

    /**
     * A fixed description of a failure the bridge did not report itself: a transport failure is
     * named by the kind of the I/O error RestClient wraps, anything else by its class. The
     * exception's own text is never used: Spring's `ResourceAccessException` quotes the request URL,
     * whose path or query may carry the bridge credentials, and this text reaches the health
     * details and the `lastError` of sync runs that `READ` callers see. The cause is not attached
     * for the same reason; the WARN log gets the cause chain with every URL cut out.
     */
    private fun failureMessage(exception: RuntimeException): String {
        val cause = exception.cause as? IOException
            ?: return "Provider request failed (${exception.javaClass.simpleName})."
        val kind = when (cause) {
            is SocketTimeoutException, is HttpTimeoutException -> "timeout"
            // Refused, unreachable, or an operating-system connect timeout: the text tells them apart.
            is ConnectException -> "cannot connect"
            is UnknownHostException -> "unknown host"
            is SSLException -> "TLS failure"
            else -> "I/O error"
        }
        return "Provider transport failure: $kind (${cause.javaClass.simpleName})."
    }

    private fun Throwable.causeChainWithoutUrls(): String =
        generateSequence(this) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message?.replace(URL_PATTERN, "<url>")}" }

    private fun recordFailure(request: ChainProviderEventsPageRequest, exception: RuntimeException, causes: String? = null) {
        lastFetchHealthy = false
        lastError = exception.message?.take(240)
        logger.warn(
            "http_provider_page_fetch_failed chainId={} address={} asset={} limit={} error={} causes={}",
            request.chainId,
            request.address,
            request.asset,
            request.limit,
            lastError,
            causes,
        )
    }

    /**
     * The bridge answered, but not with a usable page for this address (a 4xx, malformed JSON, an
     * oversized body). That says nothing about the provider's availability for other addresses,
     * so it leaves the connectivity state alone and is only reported as a health detail.
     */
    private fun recordDataError(request: ChainProviderEventsPageRequest, exception: ProviderDataInvalidException) {
        lastDataError = exception.message?.take(240)
        logger.warn(
            "http_provider_page_fetch_failed chainId={} address={} asset={} limit={} error={}",
            request.chainId,
            request.address,
            request.asset,
            request.limit,
            lastDataError,
        )
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 16

        /** A URL up to the first whitespace or quote, so the quote Spring puts around it survives. */
        val URL_PATTERN = Regex("[A-Za-z][A-Za-z0-9+.-]*://[^\\s\"'<>]+")
    }
}

// Unknown properties are skipped as they are read instead of buffered until the known ones are
// complete, so extra bridge fields cost neither memory nor the string cap.
@JsonIgnoreProperties(ignoreUnknown = true)
data class ProviderEventsPageResponse(
    val events: List<ProviderEvent?>? = null,
    val nextCursor: String? = null,
    val resumeCursor: String? = null,
    val hasMore: Boolean? = null,
    val latestBlockHeight: Long? = null,
    val safeBlockHeight: Long? = null,
    val metadata: ObjectNode? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
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
