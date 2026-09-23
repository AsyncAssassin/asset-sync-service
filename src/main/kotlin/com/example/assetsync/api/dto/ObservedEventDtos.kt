package com.example.assetsync.api.dto

import com.example.assetsync.application.transaction.IngestObservedEventCommand
import com.example.assetsync.application.transaction.InvalidObservedEventRequestException
import com.example.assetsync.application.transaction.ObservedEventIngestionResult
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.policy.AmountPolicy
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

const val MAX_CHAIN_ID_LENGTH = 64
const val MAX_TX_HASH_LENGTH = 128
const val MAX_ADDRESS_LENGTH = 128
const val MAX_ASSET_LENGTH = 32
const val MAX_AMOUNT_LENGTH = 80

data class IngestObservedEventRequest(
    @field:NotBlank(message = "chainId is required")
    @field:Size(max = MAX_CHAIN_ID_LENGTH, message = "chainId must be at most $MAX_CHAIN_ID_LENGTH characters")
    val chainId: String = "",
    @field:NotBlank(message = "txHash is required")
    @field:Size(max = MAX_TX_HASH_LENGTH, message = "txHash must be at most $MAX_TX_HASH_LENGTH characters")
    val txHash: String = "",
    @field:NotNull(message = "eventIndex is required")
    @field:Min(value = 0, message = "eventIndex must be greater than or equal to 0")
    val eventIndex: Int? = null,
    @field:NotBlank(message = "address is required")
    @field:Size(max = MAX_ADDRESS_LENGTH, message = "address must be at most $MAX_ADDRESS_LENGTH characters")
    val address: String = "",
    @field:NotBlank(message = "asset is required")
    @field:Size(max = MAX_ASSET_LENGTH, message = "asset must be at most $MAX_ASSET_LENGTH characters")
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
    @get:AssertTrue(message = "amount must be a non-negative decimal string that fits numeric(38,18)")
    val isAmountValid: Boolean
        get() = amount.isBlank() || parseAmountOrNull(amount) != null

    @get:AssertTrue(message = "direction must be INBOUND or OUTBOUND")
    val isDirectionValid: Boolean
        get() = direction.isBlank() || Direction.entries.any { it.name == direction.trim() }

    @get:AssertTrue(message = "status must be SEEN, CONFIRMED, or REVERTED")
    val isStatusValid: Boolean
        get() = status.isBlank() || TransactionStatus.entries.any { it.name == status.trim() }
}

data class ObservedEventResponse(
    val transactionId: UUID,
    val result: String,
    val status: String,
    val outboxEvents: List<String>,
)

fun IngestObservedEventRequest.toCommand(): IngestObservedEventCommand =
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

/** Parses a decimal string, exponent notation included, into the stored scale-18 amount. */
private fun parseAmountOrNull(value: String): BigDecimal? =
    runCatching { value.trim().toBigDecimal() }
        .getOrNull()
        ?.let(AmountPolicy::normalizedOrNull)
