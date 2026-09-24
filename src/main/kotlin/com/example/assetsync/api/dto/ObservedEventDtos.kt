package com.example.assetsync.api.dto

import com.example.assetsync.application.transaction.IngestObservedEventCommand
import com.example.assetsync.application.transaction.InvalidObservedEventRequestException
import com.example.assetsync.application.transaction.ObservedEventIngestionResult
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.policy.AmountPolicy
import com.example.assetsync.domain.policy.ChainIdentityNormalizer
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

const val MAX_CHAIN_ID_LENGTH = 64
const val MAX_TX_HASH_LENGTH = ChainIdentityNormalizer.MAX_IDENTITY_LENGTH
const val MAX_ADDRESS_LENGTH = ChainIdentityNormalizer.MAX_IDENTITY_LENGTH
const val MAX_ASSET_LENGTH = 32
const val MAX_AMOUNT_LENGTH = 80


// Unknown properties are skipped as they are read, not buffered until the known ones are complete,
// so extra fields cost neither memory nor the JSON string cap; the same holds for the other
// request DTOs.
@JsonIgnoreProperties(ignoreUnknown = true)
data class IngestObservedEventRequest(
    @field:NotBlank(message = "chainId is required")
    @field:Size(max = MAX_CHAIN_ID_LENGTH, message = "chainId must be at most $MAX_CHAIN_ID_LENGTH characters")
    @field:NoControlCharacters(message = "chainId must not contain control characters")
    val chainId: String = "",
    @field:NotBlank(message = "txHash is required")
    @field:Size(max = MAX_TX_HASH_LENGTH, message = "txHash must be at most $MAX_TX_HASH_LENGTH characters")
    val txHash: String = "",
    @field:NotNull(message = "eventIndex is required")
    @field:Min(value = 0, message = "eventIndex must be greater than or equal to 0")
    val eventIndex: Int? = null,
    @field:NotBlank(message = "address is required")
    @field:Size(max = MAX_ADDRESS_LENGTH, message = "address must be at most $MAX_ADDRESS_LENGTH characters")
    @field:NoControlCharacters(message = "address must not contain control characters")
    val address: String = "",
    @field:NotBlank(message = "asset is required")
    @field:Size(max = MAX_ASSET_LENGTH, message = "asset must be at most $MAX_ASSET_LENGTH characters")
    @field:NoControlCharacters(message = "asset must not contain control characters")
    val asset: String = "",
    @field:NotBlank(message = "amount is required")
    @field:Size(max = MAX_AMOUNT_LENGTH, message = "amount must be at most $MAX_AMOUNT_LENGTH characters")
    val amount: String = "",
    @field:NotNull(message = "blockHeight is required")
    @field:Min(value = 0, message = "blockHeight must be greater than or equal to 0")
    val blockHeight: Long? = null,
    @field:NotNull(message = "confirmations is required")
    @field:Min(value = 0, message = "confirmations must be greater than or equal to 0")
    val confirmations: Int? = null,
    @field:NotBlank(message = "direction is required")
    val direction: String = "",
    @field:NotBlank(message = "status is required")
    val status: String = "",
) {
    // A blank or over-long amount is left to @NotBlank and @Size, so it is reported once.
    @get:AssertTrue(message = "amount must be a non-negative decimal string that fits numeric(38,18)")
    @get:JsonIgnore
    val isAmountValid: Boolean
        get() = amount.failsNotBlank() || amount.length > MAX_AMOUNT_LENGTH || parseAmountOrNull(amount) != null

    @get:AssertTrue(message = "direction must be INBOUND or OUTBOUND")
    @get:JsonIgnore
    val isDirectionValid: Boolean
        get() = direction.failsNotBlank() || Direction.entries.any { it.name == direction.trim() }

    @get:AssertTrue(message = "status must be SEEN, CONFIRMED, or REVERTED")
    @get:JsonIgnore
    val isStatusValid: Boolean
        get() = status.failsNotBlank() || TransactionStatus.entries.any { it.name == status.trim() }
}

data class ObservedEventResponse(
    val transactionId: UUID,
    val result: String,
    val status: String,
    val outboxEvents: List<String>,
)

fun IngestObservedEventRequest.toCommand(source: String): IngestObservedEventCommand =
    IngestObservedEventCommand(
        chainId = chainId,
        txHash = txHash,
        eventIndex = eventIndex ?: throw InvalidObservedEventRequestException("eventIndex is required."),
        address = address,
        asset = asset,
        amount = parseAmountOrNull(amount)
            ?: throw InvalidObservedEventRequestException(
                "amount must be a non-negative decimal string that fits numeric(38,18).",
            ),
        blockHeight = blockHeight ?: throw InvalidObservedEventRequestException("blockHeight is required."),
        confirmations = confirmations ?: throw InvalidObservedEventRequestException("confirmations is required."),
        direction = parseEnum<Direction>("direction", direction),
        status = parseEnum<TransactionStatus>("status", status),
        source = source,
    )

fun ObservedEventIngestionResult.toResponse(): ObservedEventResponse =
    ObservedEventResponse(
        transactionId = transactionId,
        result = result.name,
        status = status.name,
        outboxEvents = outboxEvents.map { it.name },
    )

private inline fun <reified T : Enum<T>> parseEnum(fieldName: String, value: String): T =
    enumValues<T>().firstOrNull { it.name == value.trim() }
        ?: throw InvalidObservedEventRequestException("$fieldName has an unsupported value.")

/**
 * Parses a decimal string, exponent notation included, into the stored scale-18 amount. A value
 * over [MAX_AMOUNT_LENGTH] is never parsed: the parse takes time quadratic in the length.
 */
private fun parseAmountOrNull(value: String): BigDecimal? =
    if (value.length > MAX_AMOUNT_LENGTH) {
        null
    } else {
        runCatching { value.trim().toBigDecimal() }
            .getOrNull()
            ?.let(AmountPolicy::normalizedOrNull)
    }

/**
 * Whether @NotBlank rejects this value. Hibernate Validator trims with Java's `String.trim()`,
 * which strips only characters up to U+0020, while Kotlin's `isBlank()` also counts Unicode spaces;
 * the getters that defer a blank value to @NotBlank must use the same test, or a value of Unicode
 * spaces would pass validation and fail later with a different error.
 */
internal fun String.failsNotBlank(): Boolean = all { it <= ' ' }
