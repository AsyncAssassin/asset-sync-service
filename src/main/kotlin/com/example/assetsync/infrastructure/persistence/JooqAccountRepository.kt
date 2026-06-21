package com.example.assetsync.infrastructure.persistence

import com.example.assetsync.application.account.Account
import com.example.assetsync.application.account.AccountRepository
import com.example.assetsync.application.account.AccountStatus
import com.example.assetsync.application.account.DuplicateAccountExternalRefException
import com.example.assetsync.application.account.NewAccount
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.ACCOUNTS
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository

@Repository
class JooqAccountRepository(
    private val dsl: DSLContext,
) : AccountRepository {

    override fun insert(account: NewAccount): Account {
        val record = dsl
            .insertInto(ACCOUNTS)
            .set(ACCOUNTS.ID, account.id)
            .set(ACCOUNTS.EXTERNAL_REF, account.externalRef)
            .set(ACCOUNTS.STATUS, account.status.name)
            .set(ACCOUNTS.CREATED_AT, account.createdAt.toOffsetDateTime())
            .set(ACCOUNTS.UPDATED_AT, account.updatedAt.toOffsetDateTime())
            .onConflict(ACCOUNTS.EXTERNAL_REF)
            .where(ACCOUNTS.EXTERNAL_REF.isNotNull)
            .doNothing()
            .returningResult(
                ACCOUNTS.ID,
                ACCOUNTS.EXTERNAL_REF,
                ACCOUNTS.STATUS,
                ACCOUNTS.CREATED_AT,
                ACCOUNTS.UPDATED_AT,
            )
            .fetchOne()

        return record?.toAccount()
            ?: throw DuplicateAccountExternalRefException(account.externalRef.orEmpty())
    }

    override fun findById(accountId: UUID): Account? =
        dsl
            .select(
                ACCOUNTS.ID,
                ACCOUNTS.EXTERNAL_REF,
                ACCOUNTS.STATUS,
                ACCOUNTS.CREATED_AT,
                ACCOUNTS.UPDATED_AT,
            )
            .from(ACCOUNTS)
            .where(ACCOUNTS.ID.eq(accountId))
            .fetchOne { it.toAccount() }

    override fun existsById(accountId: UUID): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(accountId)),
        )

    private fun Record.toAccount(): Account =
        Account(
            id = requireNotNull(get(ACCOUNTS.ID)),
            externalRef = get(ACCOUNTS.EXTERNAL_REF),
            status = AccountStatus.valueOf(requireNotNull(get(ACCOUNTS.STATUS))),
            createdAt = requireNotNull(get(ACCOUNTS.CREATED_AT)).toInstant(),
            updatedAt = requireNotNull(get(ACCOUNTS.UPDATED_AT)).toInstant(),
        )
}

internal fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
