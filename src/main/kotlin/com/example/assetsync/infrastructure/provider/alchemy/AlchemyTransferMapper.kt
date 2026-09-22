package com.example.assetsync.infrastructure.provider.alchemy

import com.example.assetsync.application.account.AssetConfig
import com.example.assetsync.application.sync.ChainProviderObservedEvent
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.policy.NormalizedChainIdentity
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

/**
 * ERC-20 `uniqueId` is `{txHash}:log:{logIndex}`. The log index is the honest per-transaction
 * event discriminator the domain needs; any other shape (`:external`, `:internal:...`, a missing
 * suffix) cannot yield an integer event index and is provider-data invalid.
 */
object AlchemyUniqueId {

    data class Parsed(val txHash: String, val logIndex: Int)

    private val PATTERN = Regex("^(0x[0-9a-fA-F]{64}):log:(\\d{1,9})$")

    fun parse(uniqueId: String?, hash: String?): Parsed {
        val value = uniqueId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ProviderDataInvalidException("Alchemy transfer has no uniqueId.")
        val match = PATTERN.matchEntire(value)
            ?: throw ProviderDataInvalidException("Alchemy transfer uniqueId does not match the ERC-20 log pattern.")
        val txHash = match.groupValues[1].lowercase(Locale.ROOT)
        val logIndex = match.groupValues[2].toInt()
        val declaredHash = hash?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ProviderDataInvalidException("Alchemy transfer has no transaction hash.")
        if (declaredHash.lowercase(Locale.ROOT) != txHash) {
            throw ProviderDataInvalidException("Alchemy transfer uniqueId does not match its transaction hash.")
        }
        return Parsed(txHash = txHash, logIndex = logIndex)
    }
}

/**
 * Converts `rawContract.value` (an unsigned hex base-unit integer) with the registry decimals
 * into the decimal-adjusted token amount stored in `observed_transactions.amount`. The provider's
 * floating `value` is never used, and a provider `rawContract.decimal` that disagrees with the
 * registry is a data or registry mismatch, not something to average out.
 */
object AlchemyAmountMapper {

    const val MAX_DECIMALS = 18
    const val MAX_PRECISION = 38

    private val HEX_QUANTITY = Regex("^0x[0-9a-fA-F]{1,64}$")
    private val HEX_SMALL = Regex("^0x[0-9a-fA-F]{1,8}$")

    fun toAmount(rawValueHex: String?, providerDecimalHex: String?, registryDecimals: Int): BigDecimal {
        val hex = rawValueHex?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ProviderDataInvalidException("Alchemy transfer has no rawContract.value.")
        if (!HEX_QUANTITY.matches(hex)) {
            throw ProviderDataInvalidException("Alchemy transfer rawContract.value is not a hex quantity.")
        }
        if (registryDecimals !in 0..MAX_DECIMALS) {
            throw ProviderDataInvalidException("Asset config decimals $registryDecimals are outside 0..$MAX_DECIMALS.")
        }
        providerDecimalHex?.trim()?.takeIf { it.isNotEmpty() }?.let { providerHex ->
            if (!HEX_SMALL.matches(providerHex)) {
                throw ProviderDataInvalidException("Alchemy transfer rawContract.decimal is not a hex quantity.")
            }
            val providerDecimals = providerHex.substring(2).toInt(radix = 16)
            if (providerDecimals != registryDecimals) {
                throw ProviderDataInvalidException(
                    "Alchemy transfer rawContract.decimal $providerDecimals differs from the registry decimals $registryDecimals.",
                )
            }
        }
        val raw = BigInteger(hex.substring(2), 16)
        val amount = BigDecimal(raw).movePointLeft(registryDecimals).setScale(MAX_DECIMALS)
        if (amount.precision() > MAX_PRECISION) {
            throw ProviderDataInvalidException("Alchemy transfer amount does not fit numeric($MAX_PRECISION, $MAX_DECIMALS).")
        }
        return amount
    }
}

/**
 * Maps one transfer row of one stream onto the domain event, or classifies it as a deterministic
 * skip. Structural rules (category, block number, uniqueId, both addresses, contract identity)
 * apply to every row before any skip decision, so a malformed row never hides behind a filter.
 */
class AlchemyTransferMapper(
    private val identity: NormalizedChainIdentity,
    private val assetConfig: AssetConfig,
    private val latestBlockHeight: Long,
) {

    sealed interface Outcome {
        val uniqueId: String
        val blockHeight: Long

        data class Event(override val uniqueId: String, override val blockHeight: Long, val event: ChainProviderObservedEvent) : Outcome

        data class SelfTransfer(override val uniqueId: String, override val blockHeight: Long) : Outcome

        data class WrongToken(override val uniqueId: String, override val blockHeight: Long, val contractAddress: String) : Outcome
    }

    fun map(transfer: AlchemyTransfer, direction: Direction): Outcome {
        val category = transfer.category?.trim()?.lowercase(Locale.ROOT)
        if (category != AlchemyTransfersParams.ERC20_CATEGORY) {
            throw ProviderDataInvalidException("Alchemy transfer category '${transfer.category}' is not erc20.")
        }
        val blockHeight = parseBlockNumber(transfer.blockNum)
        val parsedId = AlchemyUniqueId.parse(uniqueId = transfer.uniqueId, hash = transfer.hash)
        val from = requireAddress(transfer.from, "from")
        val to = requireAddress(transfer.to, "to")
        val rawContract = transfer.rawContract
            ?: throw ProviderDataInvalidException("Alchemy transfer has no rawContract.")
        val contractAddress = rawContract.address?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            ?: throw ProviderDataInvalidException("Alchemy transfer has no rawContract.address.")
        val expectedSide = if (direction == Direction.INBOUND) to else from
        if (expectedSide != identity.address) {
            throw ProviderDataInvalidException("Alchemy transfer does not involve the watched address on the ${direction.name.lowercase()} side.")
        }
        if (contractAddress != assetConfig.contractAddress) {
            return Outcome.WrongToken(uniqueId = transfer.uniqueId!!.trim(), blockHeight = blockHeight, contractAddress = contractAddress)
        }
        if (from == to) {
            return Outcome.SelfTransfer(uniqueId = transfer.uniqueId!!.trim(), blockHeight = blockHeight)
        }
        val amount = AlchemyAmountMapper.toAmount(
            rawValueHex = rawContract.value,
            providerDecimalHex = rawContract.decimal,
            registryDecimals = assetConfig.decimals,
        )
        val confirmations = latestBlockHeight - blockHeight + 1
        if (confirmations < 0) {
            throw ProviderDataInvalidException("Alchemy transfer block $blockHeight is above the latest block $latestBlockHeight.")
        }
        if (confirmations > Int.MAX_VALUE) {
            throw ProviderDataInvalidException("Alchemy transfer confirmation count overflows.")
        }
        return Outcome.Event(
            uniqueId = transfer.uniqueId!!.trim(),
            blockHeight = blockHeight,
            event = ChainProviderObservedEvent(
                chainId = identity.chainId,
                txHash = parsedId.txHash,
                eventIndex = parsedId.logIndex,
                address = identity.address,
                asset = identity.asset,
                amount = amount,
                blockHeight = blockHeight,
                confirmations = confirmations.toInt(),
                direction = direction,
                status = TransactionStatus.SEEN,
            ),
        )
    }

    private fun parseBlockNumber(blockNum: String?): Long {
        val hex = blockNum?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ProviderDataInvalidException("Alchemy transfer has no blockNum.")
        if (!BLOCK_NUMBER.matches(hex)) {
            throw ProviderDataInvalidException("Alchemy transfer blockNum is not a hex quantity.")
        }
        return hex.substring(2).toLong(radix = 16)
    }

    private fun requireAddress(value: String?, field: String): String =
        value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            ?: throw ProviderDataInvalidException("Alchemy transfer has no $field address.")

    private companion object {
        val BLOCK_NUMBER = Regex("^0x[0-9a-fA-F]{1,15}$")
    }
}
