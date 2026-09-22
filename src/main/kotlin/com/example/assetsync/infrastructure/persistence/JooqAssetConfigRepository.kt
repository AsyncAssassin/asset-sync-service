package com.example.assetsync.infrastructure.persistence

import com.example.assetsync.application.account.AssetConfig
import com.example.assetsync.application.account.AssetConfigRepository
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.ASSET_CONFIGS
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository

@Repository
class JooqAssetConfigRepository(
    private val dsl: DSLContext,
) : AssetConfigRepository {

    override fun findEnabledByChainIdAndAsset(chainId: String, asset: String): AssetConfig? =
        dsl
            .select(
                ASSET_CONFIGS.CHAIN_ID,
                ASSET_CONFIGS.ASSET,
                ASSET_CONFIGS.TOKEN_STANDARD,
                ASSET_CONFIGS.CONTRACT_ADDRESS,
                ASSET_CONFIGS.DECIMALS,
                ASSET_CONFIGS.DISPLAY_NAME,
                ASSET_CONFIGS.ENABLED,
            )
            .from(ASSET_CONFIGS)
            .where(ASSET_CONFIGS.CHAIN_ID.eq(chainId))
            .and(ASSET_CONFIGS.ASSET.eq(asset))
            .and(ASSET_CONFIGS.ENABLED.eq(true))
            .fetchOne { it.toAssetConfig() }

    private fun Record.toAssetConfig(): AssetConfig =
        AssetConfig(
            chainId = requireNotNull(get(ASSET_CONFIGS.CHAIN_ID)),
            asset = requireNotNull(get(ASSET_CONFIGS.ASSET)),
            tokenStandard = requireNotNull(get(ASSET_CONFIGS.TOKEN_STANDARD)),
            contractAddress = requireNotNull(get(ASSET_CONFIGS.CONTRACT_ADDRESS)),
            decimals = requireNotNull(get(ASSET_CONFIGS.DECIMALS)),
            displayName = get(ASSET_CONFIGS.DISPLAY_NAME),
            enabled = requireNotNull(get(ASSET_CONFIGS.ENABLED)),
        )
}
