package com.example.assetsync.application.account

import java.time.Instant
import java.util.UUID

enum class AccountStatus {
    ACTIVE,
    DISABLED,
}

enum class WatchedAddressStatus {
    ACTIVE,
    DISABLED,
}

data class CreateAccountCommand(
    val externalRef: String?,
)

data class RegisterWatchedAddressCommand(
    val accountId: UUID,
    val chainId: String,
    val address: String,
    val asset: String,
    val label: String?,
)

data class NewAccount(
    val id: UUID,
    val externalRef: String?,
    val status: AccountStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Account(
    val id: UUID,
    val externalRef: String?,
    val status: AccountStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ChainConfig(
    val chainId: String,
    val displayName: String,
    val requiredConfirmations: Int,
    val enabled: Boolean,
)

data class NewWatchedAddress(
    val id: UUID,
    val accountId: UUID,
    val chainId: String,
    val address: String,
    val asset: String,
    val label: String?,
    val status: WatchedAddressStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class WatchedAddress(
    val id: UUID,
    val accountId: UUID,
    val chainId: String,
    val address: String,
    val asset: String,
    val label: String?,
    val status: WatchedAddressStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface AccountRepository {
    fun insert(account: NewAccount): Account

    fun findById(accountId: UUID): Account?

    fun existsById(accountId: UUID): Boolean
}

interface ChainConfigRepository {
    fun findEnabledByChainId(chainId: String): ChainConfig?
}

interface WatchedAddressRepository {
    fun insert(watchedAddress: NewWatchedAddress): WatchedAddress

    fun findByAccountId(accountId: UUID): List<WatchedAddress>

    fun findActiveById(addressId: UUID): WatchedAddress?

    fun findActiveByAccountId(accountId: UUID): List<WatchedAddress>

    fun findActiveByNaturalKey(chainId: String, address: String, asset: String): WatchedAddress?
}

class AccountNotFoundException(
    val accountId: UUID,
) : RuntimeException("Account was not found.")

class DuplicateAccountExternalRefException(
    val externalRef: String,
) : RuntimeException("Account externalRef already exists.")

class DuplicateWatchedAddressException(
    val chainId: String,
    val address: String,
    val asset: String,
) : RuntimeException("Watched address already exists.")

class UnsupportedChainException(
    val chainId: String,
) : RuntimeException("Chain configuration was not found or is disabled.")
