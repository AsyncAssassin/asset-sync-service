package com.example.assetsync.infrastructure.provider.alchemy

/**
 * Removes the configured API key from any text that may reach a log line, an exception message, a
 * health detail, or `sync_runs.last_error`. Path auth mode puts the key into request URLs, and
 * `RestClient` failures embed that URL, so scrubbing is applied to every outgoing message rather
 * than only to the places known to leak today.
 */
class AlchemySecretScrubber(private val apiKey: String) {

    fun scrub(text: String?): String {
        val value = text ?: return ""
        if (apiKey.isBlank()) {
            return value
        }
        return value.replace(apiKey, REDACTED)
    }

    companion object {
        const val REDACTED = "***"
    }
}
