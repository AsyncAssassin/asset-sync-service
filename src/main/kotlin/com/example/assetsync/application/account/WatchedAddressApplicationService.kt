package com.example.assetsync.application.account

import com.example.assetsync.application.sync.ChainProviderPort
import com.example.assetsync.domain.policy.ChainIdentityNormalizer
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WatchedAddressApplicationService(
    private val accountRepository: AccountRepository,
    private val chainConfigRepository: ChainConfigRepository,
    private val assetConfigRepository: AssetConfigRepository,
    private val watchedAddressRepository: WatchedAddressRepository,
    private val chainProviderPort: ChainProviderPort,
    private val clock: Clock,
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100
        const val MAX_PAGE = 10_000
    }

    @Transactional
    fun registerWatchedAddress(command: RegisterWatchedAddressCommand): WatchedAddress {
        if (!accountRepository.existsById(command.accountId)) {
            throw AccountNotFoundException(command.accountId)
        }

        val identity = ChainIdentityNormalizer.normalize(
            chainId = command.chainId,
            address = command.address,
            asset = command.asset,
        )
        requireServable(chainId = identity.chainId, asset = identity.asset)
        ChainIdentityNormalizer.addressViolation(identity.chainId, identity.address)?.let { violation ->
            throw InvalidWatchedAddressException(chainId = identity.chainId, message = violation)
        }

        val now = Instant.now(clock)
        return watchedAddressRepository.insert(
            NewWatchedAddress(
                id = UUID.randomUUID(),
                accountId = command.accountId,
                chainId = identity.chainId,
                address = identity.address,
                asset = identity.asset,
                label = command.label.trimToNull(),
                status = WatchedAddressStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Enables or disables a watched address. A disabled address is skipped by account sync and
     * refused by address sync and event ingestion; enabling it again resumes from its stored cursor.
     * Enabling passes the registration checks of chain, provider, and asset again, so it cannot bring
     * back an address the registry or the active provider no longer serves. Setting the current
     * status again changes nothing.
     */
    @Transactional
    fun updateStatus(addressId: UUID, status: WatchedAddressStatus): WatchedAddress {
        val current = watchedAddressRepository.findById(addressId)
            ?: throw UnknownWatchedAddressException(addressId)
        if (current.status == status) {
            return current
        }
        if (status == WatchedAddressStatus.ACTIVE) {
            requireServable(chainId = current.chainId, asset = current.asset)
        }
        return watchedAddressRepository.updateStatus(addressId = addressId, status = status, updatedAt = Instant.now(clock))
            ?: throw UnknownWatchedAddressException(addressId)
    }

    private fun requireServable(chainId: String, asset: String) {
        chainConfigRepository.findEnabledByChainId(chainId) ?: throw UnsupportedChainException(chainId)
        // An address on a chain the provider cannot serve would fail every sync, and under Alchemy
        // stop the next start; it is refused like a chain that is not configured.
        if (!chainProviderPort.supportsChain(chainId)) {
            throw UnsupportedChainException(chainId)
        }
        // The registry is the global supported-asset contract: every profile and provider type goes
        // through it, and local/test/demo flows rely on the seeded `local-evm` USDC row.
        assetConfigRepository.findEnabledByChainIdAndAsset(chainId = chainId, asset = asset)
            ?: throw UnsupportedAssetException(chainId = chainId, asset = asset)
    }

    @Transactional(readOnly = true)
    fun listWatchedAddresses(accountId: UUID, page: Int, size: Int): WatchedAddressPage {
        if (!accountRepository.existsById(accountId)) {
            throw AccountNotFoundException(accountId)
        }

        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
            throw InvalidWatchedAddressPageException(
                page = page,
                size = size,
                maxPage = MAX_PAGE,
                maxPageSize = MAX_PAGE_SIZE,
            )
        }

        val offset = page.toLong() * size.toLong()
        if (offset > Int.MAX_VALUE) {
            throw InvalidWatchedAddressPageException(
                page = page,
                size = size,
                maxPage = MAX_PAGE,
                maxPageSize = MAX_PAGE_SIZE,
            )
        }

        val rows = watchedAddressRepository.findByAccountId(
            accountId = accountId,
            limit = size + 1,
            offset = offset.toInt(),
        )

        return WatchedAddressPage(
            items = rows.take(size),
            page = page,
            size = size,
            hasNext = rows.size > size,
        )
    }
}

internal fun String?.trimToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
