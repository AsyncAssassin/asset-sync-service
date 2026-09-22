package com.example.assetsync.infrastructure.provider.alchemy

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Parameters of `alchemy_getAssetTransfers` as the adapter sends them: one ERC-20 category, one
 * registry contract, ascending order, metadata on, zero values kept, and exactly one of
 * `toAddress` (inbound stream) or `fromAddress` (outbound stream). Block bounds and `maxCount`
 * are hex quantities; `pageKey` is only ever an in-memory continuation inside one fetch.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AlchemyTransfersParams(
    val fromBlock: String,
    val toBlock: String,
    val category: List<String> = listOf(ERC20_CATEGORY),
    val contractAddresses: List<String>,
    val withMetadata: Boolean = true,
    val excludeZeroValue: Boolean = false,
    val order: String = "asc",
    val maxCount: String,
    val toAddress: String? = null,
    val fromAddress: String? = null,
    val pageKey: String? = null,
) {
    companion object {
        const val ERC20_CATEGORY = "erc20"
    }
}

/** `result` of `alchemy_getAssetTransfers`; `pageKey` is present only when more rows exist. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AlchemyTransfersResult(
    val transfers: List<AlchemyTransfer>? = null,
    val pageKey: String? = null,
)

/**
 * One transfer row. The floating `value` field is deliberately not mapped: money comes from
 * `rawContract.value` and the registry decimals only, and the provider `asset` symbol is display
 * data, never identity.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AlchemyTransfer(
    val blockNum: String? = null,
    val uniqueId: String? = null,
    val hash: String? = null,
    val from: String? = null,
    val to: String? = null,
    val category: String? = null,
    val asset: String? = null,
    val rawContract: AlchemyRawContract? = null,
    val metadata: AlchemyTransferMetadata? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AlchemyRawContract(
    val value: String? = null,
    val address: String? = null,
    val decimal: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AlchemyTransferMetadata(
    val blockTimestamp: String? = null,
)
