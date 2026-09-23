package com.example.assetsync.unit

import com.example.assetsync.domain.policy.ChainIdentityNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChainIdentityNormalizerTests {

    @Test
    fun `evm chains lower-case addresses and hashes and upper-case the asset code`() {
        for (chainId in listOf("local-evm", "eth-sepolia", "eth-mainnet")) {
            val identity = ChainIdentityNormalizer.normalize(
                chainId = " $chainId ",
                address = " 0xABCDEF0123456789ABCDEF0123456789ABCDEF01 ",
                asset = " usdc ",
            )
            assertEquals(chainId, identity.chainId)
            assertEquals("0xabcdef0123456789abcdef0123456789abcdef01", identity.address)
            assertEquals("USDC", identity.asset)
            assertEquals("0xdeadbeef", ChainIdentityNormalizer.normalizeTxHash(chainId, " 0xDEADBEEF "))
        }
    }

    @Test
    fun `other chains only trim and preserve casing`() {
        val identity = ChainIdentityNormalizer.normalize(chainId = "solana-devnet", address = " Base58Addr ", asset = " usdc ")
        assertEquals("solana-devnet", identity.chainId)
        assertEquals("Base58Addr", identity.address)
        assertEquals("usdc", identity.asset)
        assertEquals("SigNature", ChainIdentityNormalizer.normalizeTxHash("solana-devnet", " SigNature "))
    }

    @Test
    fun `real evm chains accept only 0x-prefixed hex of the exact length`() {
        for (chainId in listOf("eth-sepolia", "eth-mainnet")) {
            assertNull(ChainIdentityNormalizer.addressViolation(chainId, address(chainId, "0xABCDEF0123456789ABCDEF0123456789ABCDEF01")))
            assertNull(ChainIdentityNormalizer.txHashViolation(chainId, txHash(chainId, "0x" + "Ab".repeat(32))))

            listOf(
                "0x123",
                "0x" + "g".repeat(40),
                "0x" + "a".repeat(41),
                "abcdef0123456789abcdef0123456789abcdef0123",
                "0xabcdef0123456789abcd\nf0123456789abcdef01",
            ).forEach { candidate ->
                assertEquals(
                    if ('\n' in candidate) "address must not contain control characters." else "address must be 0x followed by 40 hex digits on $chainId.",
                    ChainIdentityNormalizer.addressViolation(chainId, address(chainId, candidate)),
                    "address $candidate on $chainId",
                )
            }
            assertEquals(
                "txHash must be 0x followed by 64 hex digits on $chainId.",
                ChainIdentityNormalizer.txHashViolation(chainId, txHash(chainId, "0xe2e-tx")),
            )
        }
    }

    @Test
    fun `local evm keeps synthetic identities but refuses whitespace slashes colons and control characters`() {
        assertNull(ChainIdentityNormalizer.addressViolation("local-evm", address("local-evm", "0xdemoaddr")))
        assertNull(ChainIdentityNormalizer.txHashViolation("local-evm", txHash("local-evm", "0xsim-1a2b3c")))

        listOf("0xab cd", "0xab/cd", "0xab:cd").forEach { candidate ->
            assertEquals(
                "address must not contain whitespace, '/', or ':' on local-evm.",
                ChainIdentityNormalizer.addressViolation("local-evm", address("local-evm", candidate)),
                "address $candidate",
            )
            assertEquals(
                "txHash must not contain whitespace, '/', or ':' on local-evm.",
                ChainIdentityNormalizer.txHashViolation("local-evm", txHash("local-evm", candidate)),
                "txHash $candidate",
            )
        }
        assertEquals(
            "address must not contain control characters.",
            ChainIdentityNormalizer.addressViolation("local-evm", address("local-evm", "0xab\ncd")),
        )
        assertEquals("txHash must not be blank.", ChainIdentityNormalizer.txHashViolation("local-evm", txHash("local-evm", "  ")))
    }

    @Test
    fun `other chains refuse only control characters`() {
        assertNull(ChainIdentityNormalizer.addressViolation("solana-devnet", "0:0xcollision"))
        assertNull(ChainIdentityNormalizer.txHashViolation("solana-devnet", "Sig Nature/1"))
        assertEquals(
            "txHash must not contain control characters.",
            ChainIdentityNormalizer.txHashViolation("solana-devnet", "forged\r\nline"),
        )
    }

    private fun address(chainId: String, raw: String): String =
        ChainIdentityNormalizer.normalize(chainId = chainId, address = raw, asset = "USDC").address

    private fun txHash(chainId: String, raw: String): String =
        ChainIdentityNormalizer.normalizeTxHash(chainId, raw)
}
