package com.example.assetsync.unit

import com.example.assetsync.application.account.AssetConfig
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.domain.model.Direction
import com.example.assetsync.domain.model.TransactionStatus
import com.example.assetsync.domain.policy.ChainIdentityNormalizer
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyAmountMapper
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyRawContract
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyTransfer
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyTransferMapper
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyTransfersResult
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyUniqueId
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows

/**
 * Locks the row mapping against a captured-shape fixture and the parsing rules behind it: the
 * ERC-20 `uniqueId` log pattern, base-unit hex amounts with registry decimals, the floating
 * `value` never being money, and every malformed shape ending as provider-data invalid.
 */
class AlchemyTransferMapperTests {

    private val objectMapper = jacksonObjectMapper()
    private val watched = "0xAbC0000000000000000000000000000000000001"
    private val identity = ChainIdentityNormalizer.normalize(chainId = "eth-sepolia", address = watched, asset = "usdc")
    private val usdc = AssetConfig(
        chainId = "eth-sepolia",
        asset = "USDC",
        tokenStandard = "ERC20",
        contractAddress = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238",
        decimals = 6,
        displayName = "USD Coin",
        enabled = true,
    )
    private val latest = 100_000_100L
    private val mapper = AlchemyTransferMapper(identity = identity, assetConfig = usdc, latestBlockHeight = latest)

    @Test
    fun `maps the fixture page into inbound event, outbound event, self-transfer skip, and wrong-token skip`() {
        val fixture = objectMapper.readTree(Files.readString(Path.of("src/test/resources/provider/alchemy/erc20-transfers-page.json")))
        val page = objectMapper.treeToValue(fixture.get("result"), AlchemyTransfersResult::class.java)
        val rows = requireNotNull(page.transfers)
        assertEquals(4, rows.size)
        assertEquals("b5f2e3c0-3d3e-4a1a-9c0e-8f1d2e3c4b5a", page.pageKey)

        val inbound = assertIs<AlchemyTransferMapper.Outcome.Event>(mapper.map(rows[0], Direction.INBOUND))
        assertEquals("0x9d3f4e2b7a1c6d5e8f0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60", inbound.event.txHash)
        assertEquals(12, inbound.event.eventIndex)
        assertEquals(100_000_000L, inbound.event.blockHeight)
        assertEquals(BigDecimal("1.234567000000000000"), inbound.event.amount)
        assertEquals(101, inbound.event.confirmations)
        assertEquals(Direction.INBOUND, inbound.event.direction)
        assertEquals(TransactionStatus.SEEN, inbound.event.status)
        assertEquals("eth-sepolia", inbound.event.chainId)
        assertEquals(watched.lowercase(), inbound.event.address)
        assertEquals("USDC", inbound.event.asset)

        val outbound = assertIs<AlchemyTransferMapper.Outcome.Event>(mapper.map(rows[1], Direction.OUTBOUND))
        assertEquals(BigDecimal("0.000025000000000000"), outbound.event.amount, "raw hex, not the scientific-notation float")
        assertEquals(3, outbound.event.eventIndex)
        assertEquals(Direction.OUTBOUND, outbound.event.direction)

        val self = assertIs<AlchemyTransferMapper.Outcome.SelfTransfer>(mapper.map(rows[2], Direction.INBOUND))
        assertEquals(100_000_002L, self.blockHeight)
        assertIs<AlchemyTransferMapper.Outcome.SelfTransfer>(mapper.map(rows[2], Direction.OUTBOUND))

        val wrongToken = assertIs<AlchemyTransferMapper.Outcome.WrongToken>(mapper.map(rows[3], Direction.INBOUND))
        assertEquals("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", wrongToken.contractAddress)
    }

    @Test
    fun `unique id must be the erc20 log pattern matching the transaction hash`() {
        val hash = "0x" + "ab".repeat(32)
        assertEquals(AlchemyUniqueId.Parsed(hash, 0), AlchemyUniqueId.parse("$hash:log:0", hash))
        assertEquals(AlchemyUniqueId.Parsed(hash, 987654321), AlchemyUniqueId.parse("$hash:log:987654321", hash.uppercase()))

        listOf(
            "$hash" to "does not match the ERC-20 log pattern",
            "$hash:external" to "does not match the ERC-20 log pattern",
            "$hash:internal:0_3_2" to "does not match the ERC-20 log pattern",
            "$hash:log:x" to "does not match the ERC-20 log pattern",
            "$hash:log:1234567890" to "does not match the ERC-20 log pattern",
            "0x${"cd".repeat(32)}:log:1" to "does not match its transaction hash",
            "" to "has no uniqueId",
        ).forEach { (uniqueId, fragment) ->
            val exception = assertThrows<ProviderDataInvalidException> { AlchemyUniqueId.parse(uniqueId, hash) }
            assertTrue(exception.message!!.contains(fragment), "for '$uniqueId': ${exception.message}")
        }
        assertTrue(assertThrows<ProviderDataInvalidException> { AlchemyUniqueId.parse("$hash:log:1", null) }.message!!.contains("no transaction hash"))
    }

    @Test
    fun `amounts come from raw hex and registry decimals within numeric(38,18)`() {
        assertEquals(BigDecimal("1.234567000000000000"), AlchemyAmountMapper.toAmount("0x12d687", "0x6", 6))
        assertEquals(BigDecimal("1.000000000000000000"), AlchemyAmountMapper.toAmount("0xde0b6b3a7640000", "0x12", 18))
        assertEquals(BigDecimal("0E-18").setScale(18), AlchemyAmountMapper.toAmount("0x0", null, 6))
        assertEquals(BigDecimal("0.000000000000000001"), AlchemyAmountMapper.toAmount("0x1", null, 18))
        val twentyDigits = "9".repeat(20).toBigInteger()
        assertEquals(BigDecimal(twentyDigits).setScale(18), AlchemyAmountMapper.toAmount("0x" + twentyDigits.toString(16), null, 0))

        val twentyOneDigits = "1" + "0".repeat(20)
        assertTrue(
            assertThrows<ProviderDataInvalidException> { AlchemyAmountMapper.toAmount("0x" + twentyOneDigits.toBigInteger().toString(16), null, 0) }
                .message!!.contains("does not fit numeric(38, 18)"),
        )
        assertTrue(assertThrows<ProviderDataInvalidException> { AlchemyAmountMapper.toAmount("12d687", null, 6) }.message!!.contains("not a hex quantity"))
        assertTrue(assertThrows<ProviderDataInvalidException> { AlchemyAmountMapper.toAmount(null, null, 6) }.message!!.contains("no rawContract.value"))
        assertTrue(assertThrows<ProviderDataInvalidException> { AlchemyAmountMapper.toAmount("0x12d687", "0x12", 6) }.message!!.contains("differs from the registry decimals 6"))
        assertTrue(assertThrows<ProviderDataInvalidException> { AlchemyAmountMapper.toAmount("0x12d687", "six", 6) }.message!!.contains("rawContract.decimal is not a hex quantity"))
        assertTrue(assertThrows<ProviderDataInvalidException> { AlchemyAmountMapper.toAmount("0x1", null, 19) }.message!!.contains("outside 0..18"))
    }

    @Test
    fun `malformed rows are provider data invalid, never silently skipped`() {
        val valid = AlchemyTransfer(
            blockNum = "0x64",
            uniqueId = "0x${"11".repeat(32)}:log:1",
            hash = "0x${"11".repeat(32)}",
            from = "0x2222222222222222222222222222222222222222",
            to = watched,
            category = "erc20",
            asset = "USDC",
            rawContract = AlchemyRawContract(value = "0x1", address = usdc.contractAddress, decimal = "0x6"),
        )
        assertIs<AlchemyTransferMapper.Outcome.Event>(mapper.map(valid, Direction.INBOUND))

        fun expectInvalid(transfer: AlchemyTransfer, direction: Direction, fragment: String) {
            val exception = assertThrows<ProviderDataInvalidException> { mapper.map(transfer, direction) }
            assertTrue(exception.message!!.contains(fragment), "expected '$fragment' in: ${exception.message}")
        }
        expectInvalid(valid.copy(category = "internal"), Direction.INBOUND, "is not erc20")
        expectInvalid(valid.copy(blockNum = "100"), Direction.INBOUND, "blockNum is not a hex quantity")
        expectInvalid(valid.copy(blockNum = null), Direction.INBOUND, "has no blockNum")
        expectInvalid(valid.copy(rawContract = null), Direction.INBOUND, "has no rawContract")
        expectInvalid(valid.copy(rawContract = AlchemyRawContract(value = "0x1", address = null)), Direction.INBOUND, "has no rawContract.address")
        expectInvalid(valid.copy(from = null), Direction.INBOUND, "has no from address")
        expectInvalid(valid.copy(to = " "), Direction.INBOUND, "has no to address")
        expectInvalid(valid, Direction.OUTBOUND, "does not involve the watched address on the outbound side")
        expectInvalid(valid.copy(to = "0x3333333333333333333333333333333333333333"), Direction.INBOUND, "does not involve the watched address on the inbound side")
        expectInvalid(valid.copy(blockNum = "0x5f5e200"), Direction.INBOUND, "is above the latest block")
        expectInvalid(valid.copy(rawContract = AlchemyRawContract(value = "0x1", address = usdc.contractAddress, decimal = "0x2")), Direction.INBOUND, "differs from the registry decimals")
    }
}
