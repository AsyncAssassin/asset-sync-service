package com.example.assetsync.unit

import com.example.assetsync.domain.policy.ChainIdentityNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
