package com.example.assetsync.api.dto

import com.example.assetsync.application.transaction.IngestObservedEventCommand
import com.example.assetsync.application.transaction.InvalidObservedEventRequestException
import com.example.assetsync.application.transaction.ObservedEventIngestionResult
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal
import java.util.UUID

data class IngestObservedEventRequest(
    @field:NotBlank(message = "chainId is required")
    val chainId: String = "",
    @field:NotBlank(message = "txHash is required")
    val txHash: String = "",
    @field:Min(value = 0, message = "eventIndex must be greater than or equal to 0")
    val eventIndex: Int = -1,
    @field:NotBlank(message = "address is required")
    val address: String = "",
    @field:NotBlank(message = "asset is required")
    val asset: String = "",
    @field:NotBlank(message = "amount is required")
    val amount: String = "",
    @field:Min(value = 0, message = "blockHeight must be greater than or equal to 0")
    val blockHeight: Long = -1,
    @field:Min(value = 0, message = "confirmations must be greater than or equal to 0")
    val confirmations: Int = -1,
    @field:NotBlank(message = "direction is required")
    val direction: String = "",
    @field:NotBlank(message = "status is required")
    val status: String = "",
) {
    @get:AssertTrue(message = "amount must be a non-negative decimal string that fits numeric(38,18)")
    val isAmountValid: Boolean
        get() = amount.isBlank() || parseAmountOrNull(amount)?.fitsNumeric38_18() == true

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
        eventIndex = eventIndex,
        address = address,
        asset = asset,
        amount = parseAmountOrNull(amount)?.takeIf { it.fitsNumeric38_18() }
            ?: throw InvalidObservedEventRequestException(
                "amount must be a non-negative decimal string that fits numeric(38,18).",
            ),
        blockHeight = blockHeight,
        confirmations = confirmations,
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

private fun parseAmountOrNull(value: String): BigDecimal? =
    runCatching { value.trim().toBigDecimal() }
        .getOrNull()
        ?.takeIf { it.signum() >= 0 }

private fun BigDecimal.fitsNumeric38_18(): Boolean {
    val normalized = stripTrailingZeros()
    val fractionDigits = maxOf(normalized.scale(), 0)
    val integerDigits = maxOf(normalized.precision() - normalized.scale(), 0)
    return fractionDigits <= 18 && integerDigits <= 20
}
