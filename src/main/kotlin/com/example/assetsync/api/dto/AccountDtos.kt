package com.example.assetsync.api.dto

import com.example.assetsync.application.account.Account
import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.account.WatchedAddressStatus
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

const val MAX_EXTERNAL_REF_LENGTH = 255
const val MAX_LABEL_LENGTH = 255

// At least one non-whitespace character. `(?s)` lets `.` cross line breaks: without it a long value
// that ends in one makes the match backtrack in quadratic time, and the validator runs the pattern
// even when @Size has already failed.
private const val NON_BLANK_PATTERN = "(?s).*\\S.*"

@JsonIgnoreProperties(ignoreUnknown = true)
data class CreateAccountRequest(
    @field:Pattern(regexp = NON_BLANK_PATTERN, message = "externalRef must be non-blank when provided")
    @field:Size(max = MAX_EXTERNAL_REF_LENGTH, message = "externalRef must be at most $MAX_EXTERNAL_REF_LENGTH characters")
    val externalRef: String? = null,
)

data class AccountResponse(
    val id: UUID,
    val externalRef: String?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RegisterWatchedAddressRequest(
    @field:NotBlank(message = "chainId is required")
    @field:Size(max = MAX_CHAIN_ID_LENGTH, message = "chainId must be at most $MAX_CHAIN_ID_LENGTH characters")
    val chainId: String = "",
    @field:NotBlank(message = "address is required")
    @field:Size(max = MAX_ADDRESS_LENGTH, message = "address must be at most $MAX_ADDRESS_LENGTH characters")
    val address: String = "",
    @field:NotBlank(message = "asset is required")
    @field:Size(max = MAX_ASSET_LENGTH, message = "asset must be at most $MAX_ASSET_LENGTH characters")
    val asset: String = "",
    @field:Pattern(regexp = NON_BLANK_PATTERN, message = "label must be non-blank when provided")
    @field:Size(max = MAX_LABEL_LENGTH, message = "label must be at most $MAX_LABEL_LENGTH characters")
    val label: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UpdateWatchedAddressRequest(
    @field:NotBlank(message = "status is required")
    val status: String = "",
) {
    @get:AssertTrue(message = "status must be ACTIVE or DISABLED")
    val isStatusValid: Boolean
        get() = status.failsNotBlank() || WatchedAddressStatus.entries.any { it.name == status.trim() }

    fun toStatus(): WatchedAddressStatus = WatchedAddressStatus.valueOf(status.trim())
}

data class WatchedAddressResponse(
    val id: UUID,
    val accountId: UUID,
    val chainId: String,
    val address: String,
    val asset: String,
    val label: String?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class WatchedAddressListResponse(
    val items: List<WatchedAddressResponse>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
)

fun Account.toResponse(): AccountResponse =
    AccountResponse(
        id = id,
        externalRef = externalRef,
        status = status.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun WatchedAddress.toResponse(): WatchedAddressResponse =
    WatchedAddressResponse(
        id = id,
        accountId = accountId,
        chainId = chainId,
        address = address,
        asset = asset,
        label = label,
        status = status.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
