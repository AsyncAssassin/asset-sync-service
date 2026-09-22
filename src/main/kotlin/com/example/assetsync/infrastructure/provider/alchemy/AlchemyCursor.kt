package com.example.assetsync.infrastructure.provider.alchemy

import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Durable, self-encoded resume token: `{"v":1,"p":"alchemy","nextBlock":N}`. `nextBlock` is the
 * first block the next fetch scans and always sits on a block boundary. No log index, transaction
 * hash, or Alchemy `pageKey` is ever part of it: the provider's page token is transient, and a
 * mid-block position would let lower log indexes discovered later be skipped for good. A
 * malformed token is provider-data invalid rather than a silent restart from genesis, which could
 * replay pages below the stored high-water and fail the run terminally later.
 */
data class AlchemyCursor(val nextBlock: Long) {

    init {
        require(nextBlock >= 0) { "nextBlock must not be negative" }
    }

    fun encode(): String = """{"v":$VERSION,"p":"$PROVIDER","nextBlock":$nextBlock}"""

    companion object {
        const val VERSION = 1
        const val PROVIDER = "alchemy"
        const val MAX_LENGTH = 256
        private val ALLOWED_FIELDS = setOf("v", "p", "nextBlock")

        fun decode(raw: String?, objectMapper: ObjectMapper): AlchemyCursor? {
            if (raw == null) {
                return null
            }
            if (raw.length > MAX_LENGTH) {
                throw invalid("is longer than $MAX_LENGTH characters")
            }
            val node = try {
                objectMapper.readTree(raw)
            } catch (exception: JsonProcessingException) {
                throw invalid("is not valid JSON")
            }
            if (node == null || !node.isObject) {
                throw invalid("is not a JSON object")
            }
            val unsupported = node.fieldNames().asSequence().filterNot { it in ALLOWED_FIELDS }.toList()
            if (unsupported.isNotEmpty()) {
                throw invalid("carries unsupported fields $unsupported")
            }
            val version = node.get("v")?.takeIf { it.isIntegralNumber }?.asInt()
                ?: throw invalid("has no integer version")
            if (version != VERSION) {
                throw invalid("has unsupported version $version")
            }
            val provider = node.get("p")?.takeIf { it.isTextual }?.asText()
                ?: throw invalid("has no provider marker")
            if (provider != PROVIDER) {
                throw invalid("belongs to provider '$provider'")
            }
            val nextBlock = node.get("nextBlock")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
                ?: throw invalid("has no integer nextBlock")
            if (nextBlock < 0) {
                throw invalid("has a negative nextBlock")
            }
            return AlchemyCursor(nextBlock)
        }

        private fun invalid(reason: String): ProviderDataInvalidException =
            ProviderDataInvalidException("Alchemy provider cursor $reason.")
    }
}
