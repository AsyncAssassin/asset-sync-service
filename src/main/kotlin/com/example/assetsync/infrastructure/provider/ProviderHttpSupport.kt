package com.example.assetsync.infrastructure.provider

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.http.client.ClientHttpRequestFactory

/**
 * HTTP helpers shared by the provider adapters: their request factory, `Retry-After` parsing for
 * 429 backpressure, and a bounded body read that refuses oversized responses before any JSON
 * parsing.
 */
internal object ProviderHttpSupport {

    private const val BUFFER_SIZE = 8 * 1024

    /**
     * `HttpURLConnection` with the configured timeouts that never follows a redirect. The operator
     * configures both endpoints, so a redirect means a wrong or moved endpoint, which retries cannot
     * fix; following it would also send the bridge credentials or the Alchemy key somewhere else.
     */
    fun requestFactory(connectTimeout: Duration, readTimeout: Duration): ClientHttpRequestFactory =
        ClientHttpRequestFactoryBuilder.simple().build(
            ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout)
                .withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW),
        )

    /**
     * The kind of a transport failure, such as `timeout (SocketTimeoutException)`, from the I/O
     * error RestClient wraps, or null when the failure is not one. The exception's own text is never
     * used: Spring's `ResourceAccessException` quotes the request URL, where a bridge token or the
     * secrets of a custom Alchemy endpoint can live.
     */
    fun transportKind(exception: RuntimeException): String? {
        val cause = exception.cause as? IOException ?: return null
        val kind = when (cause) {
            is SocketTimeoutException, is HttpTimeoutException -> "timeout"
            // Refused, unreachable, or an operating-system connect timeout: the text tells them apart.
            is ConnectException -> "cannot connect"
            is UnknownHostException -> "unknown host"
            is SSLException -> "TLS failure"
            else -> "I/O error"
        }
        return "$kind (${cause.javaClass.simpleName})"
    }

    /**
     * A cause chain for a WARN line, with every URL cut out; see [transportKind]. [scrub] runs on
     * each message first, so a secret that would end the URL match early is removed whole.
     */
    fun causeChainWithoutUrls(exception: Throwable, scrub: (String) -> String = { it }): String =
        generateSequence(exception) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message?.let(scrub)?.replace(URL_PATTERN, "<url>")}" }

    private const val MAX_CAUSE_DEPTH = 16

    /** A URL up to the first whitespace or quote, so the quote Spring puts around it survives. */
    private val URL_PATTERN = Regex("[A-Za-z][A-Za-z0-9+.-]*://[^\\s\"'<>]+")

    /** Accepts delta-seconds and RFC 1123 dates; non-positive, past, or unparseable values are ignored. */
    fun parseRetryAfter(value: String?, now: Instant = Instant.now()): Instant? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        trimmed.toLongOrNull()?.let { seconds ->
            // A delay past the end of Instant's range is as unusable as an unparseable one.
            return if (seconds <= 0) null else runCatching { now.plusSeconds(seconds) }.getOrNull()
        }
        val parsed = runCatching {
            Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(trimmed))
        }.getOrNull()
        return parsed?.takeIf { it.isAfter(now) }
    }

    /**
     * Reads at most [maxBytes] of [body]. The first byte beyond the limit throws [onOverflow]
     * without buffering the rest. An interrupted thread throws [onCancelled]: that is how the
     * provider timeout (`Future.cancel(true)`) reaches a read in progress, because a blocked socket
     * read ignores the interrupt but returns within one read timeout, or at once while a slow body
     * keeps trickling.
     *
     * On any failure the body is closed here, unread. Spring's `close()` of the response drains
     * the rest first, so otherwise a failed read of an oversized, slow, or endless body would keep
     * the provider thread reading after the fetch had given up.
     */
    fun readBounded(
        body: InputStream,
        maxBytes: Int,
        onOverflow: () -> RuntimeException,
        onCancelled: () -> RuntimeException,
    ): ByteArray {
        val buffer = ByteArray(BUFFER_SIZE)
        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE))
        var total = 0
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw onCancelled()
                }
                val read = body.read(buffer)
                if (read < 0) {
                    return output.toByteArray()
                }
                total += read
                if (total > maxBytes) {
                    throw onOverflow()
                }
                output.write(buffer, 0, read)
            }
        } catch (exception: Exception) {
            closeUnread(body)
            throw exception
        }
    }

    /**
     * Closes the body of a response that will not be read, such as an error status, so that
     * Spring's `close()` has nothing left to drain; see [readBounded].
     */
    fun closeUnread(body: () -> InputStream) {
        runCatching { body().close() }
    }

    private fun closeUnread(body: InputStream) {
        runCatching { body.close() }
    }
}
