package com.example.assetsync.application.account

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

    @Transactional
    fun registerWatchedAddress(command: RegisterWatchedAddressCommand): WatchedAddress {
        if (!accountRepository.existsById(command.accountId)) {
            throw AccountNotFoundException(command.accountId)
        }

        val chainId = command.chainId.trim()
        chainConfigRepository.findEnabledByChainId(chainId) ?: throw UnsupportedChainException(chainId)

        val now = Instant.now(clock)
        return watchedAddressRepository.insert(
            NewWatchedAddress(
                id = UUID.randomUUID(),
                accountId = command.accountId,
                chainId = chainId,
                address = command.address.trim(),
                asset = command.asset.trim(),
                label = command.label.trimToNull(),
                status = WatchedAddressStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun listWatchedAddresses(accountId: UUID): List<WatchedAddress> {
        if (!accountRepository.existsById(accountId)) {
            throw AccountNotFoundException(accountId)
        }

        return watchedAddressRepository.findByAccountId(accountId)
    }
}

internal fun String?.trimToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
