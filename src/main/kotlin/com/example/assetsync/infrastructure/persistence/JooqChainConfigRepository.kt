package com.example.assetsync.infrastructure.persistence

import com.example.assetsync.application.account.ChainConfig
import com.example.assetsync.application.account.ChainConfigRepository
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.CHAIN_CONFIGS
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository

@Repository
class JooqChainConfigRepository(
    private val dsl: DSLContext,
) : ChainConfigRepository {

    override fun findEnabledByChainId(chainId: String): ChainConfig? =
        dsl
            .select(
                CHAIN_CONFIGS.CHAIN_ID,
                CHAIN_CONFIGS.DISPLAY_NAME,
                CHAIN_CONFIGS.REQUIRED_CONFIRMATIONS,
                CHAIN_CONFIGS.ENABLED,
            )
            .from(CHAIN_CONFIGS)
            .where(CHAIN_CONFIGS.CHAIN_ID.eq(chainId))
            .and(CHAIN_CONFIGS.ENABLED.eq(true))
            .fetchOne { it.toChainConfig() }

    private fun Record.toChainConfig(): ChainConfig =
        ChainConfig(
            chainId = requireNotNull(get(CHAIN_CONFIGS.CHAIN_ID)),
            displayName = requireNotNull(get(CHAIN_CONFIGS.DISPLAY_NAME)),
            requiredConfirmations = requireNotNull(get(CHAIN_CONFIGS.REQUIRED_CONFIRMATIONS)),
            enabled = requireNotNull(get(CHAIN_CONFIGS.ENABLED)),
        )
}
