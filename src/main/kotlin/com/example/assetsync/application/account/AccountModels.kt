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

/** One row of the asset registry: the public asset code of a chain resolved to its token identity. */
data class AssetConfig(
    val chainId: String,
    val asset: String,
    val tokenStandard: String,
    val contractAddress: String,
    val decimals: Int,
    val displayName: String?,
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

data class WatchedAddressPage(
    val items: List<WatchedAddress>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean,
)

interface AccountRepository {
    fun insert(account: NewAccount): Account

    fun findById(accountId: UUID): Account?

    fun existsById(accountId: UUID): Boolean
}

interface ChainConfigRepository {
    fun findEnabledByChainId(chainId: String): ChainConfig?
}

interface AssetConfigRepository {
    fun findEnabledByChainIdAndAsset(chainId: String, asset: String): AssetConfig?
}

interface WatchedAddressRepository {
    fun insert(watchedAddress: NewWatchedAddress): WatchedAddress

    fun findByAccountId(accountId: UUID, limit: Int, offset: Int): List<WatchedAddress>

    fun findById(addressId: UUID): WatchedAddress?

    fun findActiveById(addressId: UUID): WatchedAddress?

    /** An active address whose chain is enabled, the only kind a sync serves; null otherwise. */
    fun findSyncableById(addressId: UUID): WatchedAddress?

    /**
     * Active addresses of the account on enabled chains in `(created_at, id)` order, strictly after
     * the keyset position when one is given, so a traversal survives addresses added or disabled
     * meanwhile. An address on a disabled chain is skipped like a disabled address.
     */
    fun findSyncableByAccountIdAfter(accountId: UUID, afterCreatedAt: Instant?, afterId: UUID?, limit: Int): List<WatchedAddress>

    fun updateStatus(addressId: UUID, status: WatchedAddressStatus, updatedAt: Instant): WatchedAddress?

    fun countSyncableByAccountId(accountId: UUID): Int

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
) : RuntimeException("The chain is not configured, is disabled, or is not served by the active provider.")

class UnsupportedAssetException(
    val chainId: String,
    val asset: String,
) : RuntimeException("Asset configuration was not found or is disabled for the chain.")

class InvalidWatchedAddressException(
    val chainId: String,
    override val message: String,
) : RuntimeException(message)

/** A watched address id that does not exist in any status; see WatchedAddressByIdNotFoundException for active ones. */
class UnknownWatchedAddressException(
    val addressId: UUID,
) : RuntimeException("Watched address was not found.")

class InvalidWatchedAddressPageException(
    val page: Int,
    val size: Int,
    val maxPage: Int,
    val maxPageSize: Int,
) : RuntimeException("Watched address page request is outside the supported bounds.")
