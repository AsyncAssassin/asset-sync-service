package com.example.assetsync.infrastructure.provider

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory

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
        object : SimpleClientHttpRequestFactory() {
            override fun prepareConnection(connection: HttpURLConnection, httpMethod: String) {
                super.prepareConnection(connection, httpMethod)
                connection.instanceFollowRedirects = false
            }
        }.apply {
            setConnectTimeout(connectTimeout)
            setReadTimeout(readTimeout)
        }

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
