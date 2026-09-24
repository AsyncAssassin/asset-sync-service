package com.example.assetsync.unit

import com.example.assetsync.application.sync.JsonbText
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The size of JSON as PostgreSQL prints it from jsonb, which the checkpoint column checks measure. */
class JsonbTextTests {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `lengths match what PostgreSQL prints from jsonb`() {
        // PostgreSQL prints {"a": 1, "b": [1, 2]}, and 1e300 as its 301 digits.
        assertEquals(21, JsonbText.byteLength(mapper.readTree("""{"a":1,"b":[1,2]}""")))
        assertEquals(308, JsonbText.byteLength(mapper.readTree("""{"x":1e300}""")))
        assertEquals(2, JsonbText.byteLength(mapper.readTree("{}")))
        assertEquals(12, JsonbText.byteLength(mapper.readTree("""[true, null]""")))
        // Strings are UTF-8 with JSON escapes: a two-byte letter, a control character, a surrogate pair, a quote.
        assertEquals(4, JsonbText.byteLength(TextNode("é")))
        assertEquals(8, JsonbText.byteLength(TextNode("\u0001")))
        assertEquals(6, JsonbText.byteLength(TextNode("😀")))
        assertEquals(4, JsonbText.byteLength(TextNode("\"")))
    }

    @Test
    fun `a NUL in a key or a string at any depth is found`() {
        assertTrue(JsonbText.containsNul(mapper.readTree("""{"a":{"b":["x\u0000"]}}""")))
        assertTrue(JsonbText.containsNul(mapper.readTree("""{"k\u0000":1}""")))
        assertFalse(JsonbText.containsNul(mapper.readTree("""{"a":["\u0001", 0]}""")))
    }
}
