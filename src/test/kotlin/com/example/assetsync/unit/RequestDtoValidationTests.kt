package com.example.assetsync.unit

import com.example.assetsync.api.dto.CreateAccountRequest
import com.example.assetsync.api.dto.IngestObservedEventRequest
import com.example.assetsync.api.dto.MAX_AMOUNT_LENGTH
import com.example.assetsync.api.dto.MAX_EXTERNAL_REF_LENGTH
import com.example.assetsync.api.dto.MAX_LABEL_LENGTH
import com.example.assetsync.api.dto.RegisterWatchedAddressRequest
import com.example.assetsync.api.dto.UpdateWatchedAddressRequest
import com.example.assetsync.api.dto.toCommand
import com.example.assetsync.application.transaction.InvalidObservedEventRequestException
import jakarta.validation.ConstraintViolation
import jakarta.validation.Validation
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively

/**
 * Bean validation of the request DTOs without Spring MVC, so inputs past the JSON string limit
 * that guards the HTTP path can still be checked. The timeouts are preemptive: a constraint that
 * falls back into a quadratic parse or match fails its test after a second instead of blocking it.
 */
class RequestDtoValidationTests {

    private val validator = Validation.buildDefaultValidatorFactory().validator

    private val event = IngestObservedEventRequest(
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
        val violations = assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            validator.validate(event.copy(amount = "1".repeat(1_000_000)))
        }

        assertEquals(
            listOf("amount" to "amount must be at most $MAX_AMOUNT_LENGTH characters"),
            violations.map { it.propertyPath.toString() to it.message },
        )
    }

    @Test
    fun `an amount within the length limit is still parsed and checked`() {
        val violations = validator.validate(event.copy(amount = "9".repeat(MAX_AMOUNT_LENGTH)))

        assertEquals(listOf("amountValid"), violations.paths())
        assertTrue(validator.validate(event).isEmpty())
    }

    @Test
    fun `mapping a request that skipped validation never parses an oversized amount`() {
        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            assertThrows<InvalidObservedEventRequestException> {
                event.copy(amount = "1".repeat(1_000_000)).toCommand(source = "test")
            }
        }
    }

    @Test
    fun `an oversized externalRef or label that ends in a line break is reported by its length alone`() {
        val value = "a".repeat(99_999) + "\n"

        val violations = assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            validator.validate(CreateAccountRequest(externalRef = value)) +
                validator.validate(
                    RegisterWatchedAddressRequest(chainId = "local-evm", address = "0xabc123", asset = "USDC", label = value),
                )
        }

        assertEquals(
            setOf(
                "externalRef" to "externalRef must be at most $MAX_EXTERNAL_REF_LENGTH characters",
                "label" to "label must be at most $MAX_LABEL_LENGTH characters",
            ),
            violations.map { it.propertyPath.toString() to it.message }.toSet(),
        )
    }

    @Test
    fun `externalRef needs a non-whitespace character and may span lines`() {
        assertEquals(listOf("externalRef"), validator.validate(CreateAccountRequest(externalRef = " \n\t ")).paths())
        assertTrue(validator.validate(CreateAccountRequest(externalRef = "first line\nsecond line")).isEmpty())
    }

    @Test
    fun `a value of unicode spaces fails its own rule instead of passing as blank`() {
        // @NotBlank trims with String.trim(), which keeps these characters, so the getters report them.
        listOf(
            event.copy(amount = " ") to "amountValid",
            event.copy(direction = "　") to "directionValid",
            event.copy(status = " ") to "statusValid",
        ).forEach { (request, property) ->
            assertEquals(listOf(property), validator.validate(request).paths(), property)
        }
        assertEquals(listOf("statusValid"), validator.validate(UpdateWatchedAddressRequest(status = " ")).paths())
        // An ASCII blank is still reported by @NotBlank alone.
        assertEquals(listOf("amount"), validator.validate(event.copy(amount = " ")).paths())
    }

    private fun <T> Set<ConstraintViolation<T>>.paths(): List<String> = map { it.propertyPath.toString() }
}
