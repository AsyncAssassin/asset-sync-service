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

    /**
     * EVM chains that carry real on-chain identities: an address is `0x` and 40 hex digits, a
     * transaction hash `0x` and 64. `local-evm` stays outside so the synthetic identities of local
     * runs, the demo, and the tests keep working.
     */
    val HEX_IDENTITY_CHAIN_IDS: Set<String> = setOf("eth-sepolia", "eth-mainnet")

    /** The longest address or transaction hash the database stores (changeset 006). */
    const val MAX_IDENTITY_LENGTH = 128

    private val HEX_ADDRESS = Regex("^0x[0-9a-f]{40}$")
    private val HEX_TX_HASH = Regex("^0x[0-9a-f]{64}$")

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

    /** Why a normalized [address] cannot identify a watched address on [chainId], or null if it can. */
    fun addressViolation(chainId: String, address: String): String? =
        identityViolation(
            chainId = chainId.trim(),
            field = "address",
            value = address,
            hexPattern = HEX_ADDRESS,
            hexDigits = 40,
        )

    /** Why a normalized [txHash] cannot identify a transaction on [chainId], or null if it can. */
    fun txHashViolation(chainId: String, txHash: String): String? =
        identityViolation(
            chainId = chainId.trim(),
            field = "txHash",
            value = txHash,
            hexPattern = HEX_TX_HASH,
            hexDigits = 64,
        )

    /**
     * Control characters are refused on every chain, so an identity can never forge a log line.
     * `local-evm` also refuses whitespace, '/', and ':', which no EVM identity contains, not even
     * a synthetic one; a '/' would also break the URL path of the HTTP bridge request.
     */
    private fun identityViolation(
        chainId: String,
        field: String,
        value: String,
        hexPattern: Regex,
        hexDigits: Int,
    ): String? =
        when {
            value.isBlank() -> "$field must not be blank."
            value.length > MAX_IDENTITY_LENGTH -> "$field must be at most $MAX_IDENTITY_LENGTH characters."
            value.any { it.isISOControl() } -> "$field must not contain control characters."
            chainId in HEX_IDENTITY_CHAIN_IDS && !hexPattern.matches(value) ->
                "$field must be 0x followed by $hexDigits hex digits on $chainId."
            chainId in EVM_CHAIN_IDS && value.any { it.isWhitespace() || it == '/' || it == ':' } ->
                "$field must not contain whitespace, '/', or ':' on $chainId."
            else -> null
        }
}
