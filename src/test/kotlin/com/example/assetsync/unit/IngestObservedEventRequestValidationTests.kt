package com.example.assetsync.unit

import com.example.assetsync.api.dto.IngestObservedEventRequest
import com.example.assetsync.api.dto.MAX_AMOUNT_LENGTH
import jakarta.validation.Validation
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bean validation of the ingest request without Spring MVC, so the amount constraints are checked
 * with inputs past the JSON string limit that guards the HTTP path.
 */
class IngestObservedEventRequestValidationTests {

    private val validator = Validation.buildDefaultValidatorFactory().validator

    private val request = IngestObservedEventRequest(
        chainId = "local-evm",
        txHash = "0xdeadbeef",
        eventIndex = 0,
        address = "0xabc123",
        asset = "USDC",
        amount = "12.34",
        blockHeight = 100,
        confirmations = 1,
        direction = "INBOUND",
        status = "SEEN",
    )

    @Test
    fun `an oversized amount is reported by its length alone and never parsed`() {
        val started = System.nanoTime()
        val violations = validator.validate(request.copy(amount = "1".repeat(1_000_000)))
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertEquals(
            listOf("amount" to "amount must be at most $MAX_AMOUNT_LENGTH characters"),
            violations.map { it.propertyPath.toString() to it.message },
        )
        // Parsing a million digits takes about ten seconds on its own.
        assertTrue(elapsed < Duration.ofSeconds(1), "validation took $elapsed")
    }

    @Test
    fun `an amount within the length limit is still parsed and checked`() {
        val violations = validator.validate(request.copy(amount = "9".repeat(MAX_AMOUNT_LENGTH)))

        assertEquals(listOf("amountValid"), violations.map { it.propertyPath.toString() })
        assertTrue(validator.validate(request).isEmpty())
    }
}
