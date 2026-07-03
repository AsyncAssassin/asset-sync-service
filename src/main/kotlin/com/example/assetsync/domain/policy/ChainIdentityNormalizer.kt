package com.example.assetsync.domain.policy

import java.util.Locale

data class NormalizedChainIdentity(
    val chainId: String,
    val address: String,
    val asset: String,
)

object ChainIdentityNormalizer {
    fun normalize(chainId: String, address: String, asset: String): NormalizedChainIdentity {
        val normalizedChainId = chainId.trim()
        return when (normalizedChainId) {
            "local-evm" -> NormalizedChainIdentity(
                chainId = normalizedChainId,
                address = address.trim().lowercase(Locale.ROOT),
                asset = asset.trim().uppercase(Locale.ROOT),
            )
            else -> NormalizedChainIdentity(
                chainId = normalizedChainId,
                address = address.trim(),
                asset = asset.trim(),
            )
        }
    }

    /**
     * Normalizes the transaction hash, which is part of the natural key alongside address/asset.
     * EVM hashes are case-insensitive hex, so local-evm lowercases them to keep the identity single
     * (symmetric with [normalize]'s address handling); other chains only trim.
     */
    fun normalizeTxHash(chainId: String, txHash: String): String {
        val trimmed = txHash.trim()
        return when (chainId.trim()) {
            "local-evm" -> trimmed.lowercase(Locale.ROOT)
            else -> trimmed
        }
    }
}
