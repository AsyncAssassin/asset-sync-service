package com.example.assetsync.application.account

import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountApplicationService(
    private val accountRepository: AccountRepository,
    private val clock: Clock,
) {

    @Transactional
    fun createAccount(command: CreateAccountCommand): Account {
        val now = Instant.now(clock)
        return accountRepository.insert(
            NewAccount(
                id = UUID.randomUUID(),
                externalRef = command.externalRef.trimToNull(),
                status = AccountStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun getAccount(accountId: UUID): Account =
        accountRepository.findById(accountId) ?: throw AccountNotFoundException(accountId)
}
