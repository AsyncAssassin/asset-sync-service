package com.example.assetsync.unit

import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyCursor
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows

/**
 * Locks the durable cursor codec: a compact, stable v1 token that carries only the next block, and
 * a decoder that treats anything else as provider-data invalid instead of restarting from genesis.
 */
class AlchemyCursorTests {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `encodes a compact stable token and decodes it back`() {
        val encoded = AlchemyCursor(123457).encode()

        assertEquals("""{"v":1,"p":"alchemy","nextBlock":123457}""", encoded)
        assertTrue(encoded.length < 128, "cursor must stay far below the 4096-character limit")
        assertEquals(AlchemyCursor(123457), AlchemyCursor.decode(encoded, objectMapper))
        assertEquals(AlchemyCursor(0), AlchemyCursor.decode("""{"v":1,"p":"alchemy","nextBlock":0}""", objectMapper))
        assertNull(AlchemyCursor.decode(null, objectMapper))
    }

    @Test
    fun `rejects unknown versions, other providers, missing or negative blocks, and malformed json`() {
        assertInvalid("""{"v":2,"p":"alchemy","nextBlock":1}""", "unsupported version 2")
        assertInvalid("""{"p":"alchemy","nextBlock":1}""", "no integer version")
        assertInvalid("""{"v":1,"p":"moralis","nextBlock":1}""", "belongs to provider 'moralis'")
        assertInvalid("""{"v":1,"nextBlock":1}""", "no provider marker")
        assertInvalid("""{"v":1,"p":"alchemy"}""", "no integer nextBlock")
        assertInvalid("""{"v":1,"p":"alchemy","nextBlock":"12"}""", "no integer nextBlock")
        assertInvalid("""{"v":1,"p":"alchemy","nextBlock":-1}""", "negative nextBlock")
        assertInvalid("""{"v":1,"p":"alchemy","nextBlock":99999999999999999999999}""", "no integer nextBlock")
        assertInvalid("""{"v":1,"p":"alchemy","nextBlock":""", "not valid JSON")
        assertInvalid("""[1,2]""", "not a JSON object")
        assertInvalid("plain-text-cursor", "not valid JSON")
    }

    @Test
    fun `a cursor another provider wrote says so and names the fix, whatever its shape`() {
        val fix = "so another provider wrote it; clear its provider_cursor (docs/alchemy-runbook.md, section 6)"
        // The demo simulator's opaque token, and HTTP bridge tokens of any shape: a bare number, a
        // JSON object of their own, one longer than any token this adapter writes.
        assertInvalid("sim:0123456789abcdef", "is not valid JSON, $fix")
        assertInvalid("19000000", "is not a JSON object, $fix")
        assertInvalid("""{"offset":42}""", "has no provider marker, $fix")
        assertInvalid("""{"v":1,"p":"http","nextBlock":1,"page":2}""", "belongs to provider 'http', $fix")
        assertInvalid("b".repeat(300), "is longer than 256 characters, $fix")
    }

    @Test
    fun `a damaged alchemy cursor is never called foreign`() {
        listOf(
            """{"v":2,"p":"alchemy","nextBlock":1}""",
            """{"v":1,"p":"alchemy","nextBlock":1,"pageKey":"abc"}""",
            """{"v":1,"p":"alchemy","nextBlock":-1}""",
        ).forEach { raw ->
            val exception = assertThrows<ProviderDataInvalidException> { AlchemyCursor.decode(raw, objectMapper) }
            assertFalse(exception.message!!.contains("another provider"), "for $raw: ${exception.message}")
        }
    }

    @Test
    fun `rejects mid-block or provider session fields and oversized tokens`() {
        assertInvalid("""{"v":1,"p":"alchemy","nextBlock":1,"lastIdx":3}""", "unsupported fields [lastIdx]")
        assertInvalid("""{"v":1,"p":"alchemy","nextBlock":1,"pageKey":"abc"}""", "unsupported fields [pageKey]")
        assertInvalid("""{"v":1,"p":"alchemy","nextBlock":1,"padding":"${"x".repeat(300)}"}""", "longer than 256 characters")
    }

    private fun assertInvalid(raw: String, expectedFragment: String) {
        val exception = assertThrows<ProviderDataInvalidException> { AlchemyCursor.decode(raw, objectMapper) }
        assertTrue(exception.message!!.contains(expectedFragment), "for $raw expected '$expectedFragment' in: ${exception.message}")
    }
}
