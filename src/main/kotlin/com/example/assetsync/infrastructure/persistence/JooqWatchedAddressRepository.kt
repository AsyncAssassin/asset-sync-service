package com.example.assetsync.infrastructure.persistence

import com.example.assetsync.application.account.DuplicateWatchedAddressException
import com.example.assetsync.application.account.NewWatchedAddress
import com.example.assetsync.application.account.WatchedAddress
import com.example.assetsync.application.account.WatchedAddressRepository
import com.example.assetsync.application.account.WatchedAddressStatus
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.WATCHED_ADDRESSES
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository

@Repository
class JooqWatchedAddressRepository(
    private val dsl: DSLContext,
) : WatchedAddressRepository {

    override fun insert(watchedAddress: NewWatchedAddress): WatchedAddress {
        val record = dsl
            .insertInto(WATCHED_ADDRESSES)
            .set(WATCHED_ADDRESSES.ID, watchedAddress.id)
            .set(WATCHED_ADDRESSES.ACCOUNT_ID, watchedAddress.accountId)
            .set(WATCHED_ADDRESSES.CHAIN_ID, watchedAddress.chainId)
            .set(WATCHED_ADDRESSES.ADDRESS, watchedAddress.address)
            .set(WATCHED_ADDRESSES.ASSET, watchedAddress.asset)
            .set(WATCHED_ADDRESSES.LABEL, watchedAddress.label)
            .set(WATCHED_ADDRESSES.STATUS, watchedAddress.status.name)
            .set(WATCHED_ADDRESSES.CREATED_AT, watchedAddress.createdAt.toOffsetDateTime())
            .set(WATCHED_ADDRESSES.UPDATED_AT, watchedAddress.updatedAt.toOffsetDateTime())
            .onConflict(WATCHED_ADDRESSES.CHAIN_ID, WATCHED_ADDRESSES.ADDRESS, WATCHED_ADDRESSES.ASSET)
            .doNothing()
            .returningResult(
                WATCHED_ADDRESSES.ID,
                WATCHED_ADDRESSES.ACCOUNT_ID,
                WATCHED_ADDRESSES.CHAIN_ID,
                WATCHED_ADDRESSES.ADDRESS,
                WATCHED_ADDRESSES.ASSET,
                WATCHED_ADDRESSES.LABEL,
                WATCHED_ADDRESSES.STATUS,
                WATCHED_ADDRESSES.CREATED_AT,
                WATCHED_ADDRESSES.UPDATED_AT,
            )
            .fetchOne()

        return record?.toWatchedAddress()
            ?: throw DuplicateWatchedAddressException(
                chainId = watchedAddress.chainId,
                address = watchedAddress.address,
                asset = watchedAddress.asset,
            )
    }

    override fun findByAccountId(accountId: UUID): List<WatchedAddress> =
        dsl
            .select(
                WATCHED_ADDRESSES.ID,
                WATCHED_ADDRESSES.ACCOUNT_ID,
                WATCHED_ADDRESSES.CHAIN_ID,
                WATCHED_ADDRESSES.ADDRESS,
                WATCHED_ADDRESSES.ASSET,
                WATCHED_ADDRESSES.LABEL,
                WATCHED_ADDRESSES.STATUS,
                WATCHED_ADDRESSES.CREATED_AT,
                WATCHED_ADDRESSES.UPDATED_AT,
            )
            .from(WATCHED_ADDRESSES)
            .where(WATCHED_ADDRESSES.ACCOUNT_ID.eq(accountId))
            .orderBy(WATCHED_ADDRESSES.CREATED_AT.asc(), WATCHED_ADDRESSES.ID.asc())
            .fetch { it.toWatchedAddress() }

    override fun findActiveById(addressId: UUID): WatchedAddress? =
        dsl
            .select(
                WATCHED_ADDRESSES.ID,
                WATCHED_ADDRESSES.ACCOUNT_ID,
                WATCHED_ADDRESSES.CHAIN_ID,
                WATCHED_ADDRESSES.ADDRESS,
                WATCHED_ADDRESSES.ASSET,
                WATCHED_ADDRESSES.LABEL,
                WATCHED_ADDRESSES.STATUS,
                WATCHED_ADDRESSES.CREATED_AT,
                WATCHED_ADDRESSES.UPDATED_AT,
            )
            .from(WATCHED_ADDRESSES)
            .where(WATCHED_ADDRESSES.ID.eq(addressId))
            .and(WATCHED_ADDRESSES.STATUS.eq(WatchedAddressStatus.ACTIVE.name))
            .fetchOne { it.toWatchedAddress() }

    override fun findActiveByAccountId(accountId: UUID): List<WatchedAddress> =
        dsl
            .select(
                WATCHED_ADDRESSES.ID,
                WATCHED_ADDRESSES.ACCOUNT_ID,
                WATCHED_ADDRESSES.CHAIN_ID,
                WATCHED_ADDRESSES.ADDRESS,
                WATCHED_ADDRESSES.ASSET,
                WATCHED_ADDRESSES.LABEL,
                WATCHED_ADDRESSES.STATUS,
                WATCHED_ADDRESSES.CREATED_AT,
                WATCHED_ADDRESSES.UPDATED_AT,
            )
            .from(WATCHED_ADDRESSES)
            .where(WATCHED_ADDRESSES.ACCOUNT_ID.eq(accountId))
            .and(WATCHED_ADDRESSES.STATUS.eq(WatchedAddressStatus.ACTIVE.name))
            .orderBy(WATCHED_ADDRESSES.CREATED_AT.asc(), WATCHED_ADDRESSES.ID.asc())
            .fetch { it.toWatchedAddress() }

    override fun findActiveByNaturalKey(chainId: String, address: String, asset: String): WatchedAddress? =
        dsl
            .select(
                WATCHED_ADDRESSES.ID,
                WATCHED_ADDRESSES.ACCOUNT_ID,
                WATCHED_ADDRESSES.CHAIN_ID,
                WATCHED_ADDRESSES.ADDRESS,
                WATCHED_ADDRESSES.ASSET,
                WATCHED_ADDRESSES.LABEL,
                WATCHED_ADDRESSES.STATUS,
                WATCHED_ADDRESSES.CREATED_AT,
                WATCHED_ADDRESSES.UPDATED_AT,
            )
            .from(WATCHED_ADDRESSES)
            .where(WATCHED_ADDRESSES.CHAIN_ID.eq(chainId))
            .and(WATCHED_ADDRESSES.ADDRESS.eq(address))
            .and(WATCHED_ADDRESSES.ASSET.eq(asset))
            .and(WATCHED_ADDRESSES.STATUS.eq(WatchedAddressStatus.ACTIVE.name))
            .fetchOne { it.toWatchedAddress() }

    private fun Record.toWatchedAddress(): WatchedAddress =
        WatchedAddress(
            id = requireNotNull(get(WATCHED_ADDRESSES.ID)),
            accountId = requireNotNull(get(WATCHED_ADDRESSES.ACCOUNT_ID)),
            chainId = requireNotNull(get(WATCHED_ADDRESSES.CHAIN_ID)),
            address = requireNotNull(get(WATCHED_ADDRESSES.ADDRESS)),
            asset = requireNotNull(get(WATCHED_ADDRESSES.ASSET)),
            label = get(WATCHED_ADDRESSES.LABEL),
            status = WatchedAddressStatus.valueOf(requireNotNull(get(WATCHED_ADDRESSES.STATUS))),
            createdAt = requireNotNull(get(WATCHED_ADDRESSES.CREATED_AT)).toInstant(),
            updatedAt = requireNotNull(get(WATCHED_ADDRESSES.UPDATED_AT)).toInstant(),
        )
}
