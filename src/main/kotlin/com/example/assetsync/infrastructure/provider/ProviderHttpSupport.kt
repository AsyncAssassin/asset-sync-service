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

    /** Reads at most [maxBytes]; the first byte beyond it throws [onOverflow] without buffering the rest. */
    fun readBounded(body: InputStream, maxBytes: Int, onOverflow: () -> RuntimeException): ByteArray {
        val buffer = ByteArray(BUFFER_SIZE)
        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE))
        var total = 0
        while (true) {
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
    }
}
