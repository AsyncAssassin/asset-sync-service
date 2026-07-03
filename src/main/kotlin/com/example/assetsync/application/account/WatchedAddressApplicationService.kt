package com.example.assetsync.application.account

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
    private val watchedAddressRepository: WatchedAddressRepository,
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
        chainConfigRepository.findEnabledByChainId(identity.chainId) ?: throw UnsupportedChainException(identity.chainId)

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
