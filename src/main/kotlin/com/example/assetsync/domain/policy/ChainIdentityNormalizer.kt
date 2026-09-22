package com.example.assetsync.domain.policy

import java.util.Locale

data class NormalizedChainIdentity(
    val chainId: String,
    val address: String,
    val asset: String,
)

object ChainIdentityNormalizer {
    /**
     * EVM chains whose addresses and transaction hashes are case-insensitive hex. Their identity
     * is stored lower-case and their public asset code upper-case, so the same address and asset
     * cannot be registered twice under different casing. Other chains only trim until a policy
     * for them exists. A chain-family column on `chain_configs` would replace this set once more
     * chains are supported.
     */
    val EVM_CHAIN_IDS: Set<String> = setOf("local-evm", "eth-sepolia", "eth-mainnet")

    fun normalize(chainId: String, address: String, asset: String): NormalizedChainIdentity {
        val normalizedChainId = chainId.trim()
        return if (normalizedChainId in EVM_CHAIN_IDS) {
            NormalizedChainIdentity(
                chainId = normalizedChainId,
                address = address.trim().lowercase(Locale.ROOT),
                asset = asset.trim().uppercase(Locale.ROOT),
            )
        } else {
            NormalizedChainIdentity(
                chainId = normalizedChainId,
                address = address.trim(),
                asset = asset.trim(),
            )
        }
    }

    /**
     * Normalizes the transaction hash, which is part of the natural key alongside address/asset.
     * EVM hashes are case-insensitive hex, so EVM chains lowercase them to keep the identity single
     * (symmetric with [normalize]'s address handling); other chains only trim.
     */
    fun normalizeTxHash(chainId: String, txHash: String): String {
        val trimmed = txHash.trim()
        return if (chainId.trim() in EVM_CHAIN_IDS) trimmed.lowercase(Locale.ROOT) else trimmed
    }
}
