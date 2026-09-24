package com.example.assetsync.application.sync

import com.fasterxml.jackson.databind.JsonNode

/**
 * JSON as PostgreSQL prints it from `jsonb`, which is what the `octet_length(...::text)` checks on
 * `sync_cursors.checkpoint` and `sync_runs.run_checkpoint` measure: a space after every `:` and
 * `,`, numbers written out in full (`1e300` becomes 301 digits), and strings as UTF-8. A compact
 * Jackson rendering is shorter, so a value checked by it can still break those checks.
 */
internal object JsonbText {

    /** The bytes of [node] as `jsonb` text, or slightly more where a float's binary value prints longer. */
    fun byteLength(node: JsonNode): Int =
        when {
            node.isObject -> 2 + separators(node.size()) +
                node.fields().asSequence().sumOf { (key, value) -> stringLength(key) + 2 + byteLength(value) }
            node.isArray -> 2 + separators(node.size()) + node.sumOf { byteLength(it) }
            node.isTextual -> stringLength(node.textValue())
            node.isNumber -> node.decimalValue().toPlainString().length
            else -> node.toString().length
        }

    /** Whether a key or string of [node] holds U+0000, which `jsonb` refuses. */
    fun containsNul(node: JsonNode): Boolean =
        when {
            node.isObject -> node.fields().asSequence().any { (key, value) -> '\u0000' in key || containsNul(value) }
            node.isArray -> node.any { containsNul(it) }
            node.isTextual -> '\u0000' in node.textValue()
            else -> false
        }

    private fun separators(count: Int): Int = 2 * (count - 1).coerceAtLeast(0)

    private fun stringLength(text: String): Int =
        2 + text.sumOf { char ->
            when {
                char == '"' || char == '\\' || char == '\b' || char == '\u000C' || char == '\n' || char == '\r' || char == '\t' -> 2
                char < ' ' -> 6
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                // A surrogate pair is four bytes in UTF-8: two for each half.
                Character.isSurrogate(char) -> 2
                else -> 3
            }
        }
}
