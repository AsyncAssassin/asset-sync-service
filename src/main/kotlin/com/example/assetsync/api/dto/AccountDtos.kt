package com.example.assetsync.api.dto

import com.example.assetsync.application.account.Account
import com.example.assetsync.application.account.WatchedAddress
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

data class CreateAccountRequest(
    @field:Pattern(regexp = ".*\\S.*", message = "externalRef must be non-blank when provided")
    val externalRef: String? = null,
)

data class AccountResponse(
    val id: UUID,
    val externalRef: String?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class RegisterWatchedAddressRequest(
    @field:NotBlank(message = "chainId is required")
    val chainId: String = "",
    @field:NotBlank(message = "address is required")
    val address: String = "",
    @field:NotBlank(message = "asset is required")
    val asset: String = "",
    @field:Pattern(regexp = ".*\\S.*", message = "label must be non-blank when provided")
    val label: String? = null,
)

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
