package com.example.assetsync.unit

import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.config.AlchemyNetworkProperties
import com.example.assetsync.config.AlchemyProviderProperties
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProvider
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProviderHealthIndicator
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyPreflightReport
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyRequiredChain
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.actuate.health.Status

/**
 * Locks what the Alchemy provider exposes through health and what its page fetch does in this
 * version: UP after the startup probe with provider, auth mode, networks, and state; DOWN with the
 * scrubbed error after a fetch; terminal configuration failures for both mapped and unmapped
 * chains; and never the API key anywhere in the details.
 */
class AlchemyChainProviderHealthIndicatorTests {

    private val apiKey = "secret-key-123"
    private val properties = AlchemyProviderProperties(
        apiKey = apiKey,
        networks = mapOf("eth-sepolia" to AlchemyNetworkProperties(network = "eth-sepolia")),
    )

    @Test
    fun `reports the probe state without secrets and goes down after a failed fetch`() {
        val provider = AlchemyChainProvider(
            properties = properties,
            preflight = AlchemyPreflightReport(
                requiredChains = listOf(AlchemyRequiredChain("eth-sepolia", setOf("ERC20"))),
                probedBlockHeights = mapOf("eth-sepolia" to 100L),
            ),
        )
        val indicator = AlchemyChainProviderHealthIndicator(provider)

        val up = indicator.health()
        assertEquals(Status.UP, up.status)
        assertEquals("alchemy", up.details["provider"])
        assertEquals("header", up.details["authMode"])
        assertEquals(listOf("eth-sepolia"), up.details["networks"])
        assertEquals("probe-succeeded", up.details["state"])
        assertNull(up.details["error"])
        assertFalse(up.details.toString().contains(apiKey), up.details.toString())

        val failure = assertThrows<ProviderConfigurationException> { provider.fetchObservedEventsPage(pageRequest("eth-sepolia")) }
        assertEquals(AlchemyChainProvider.FETCH_NOT_AVAILABLE_MESSAGE, failure.message)

        val down = indicator.health()
        assertEquals(Status.DOWN, down.status)
        assertEquals("fetch-failed", down.details["state"])
        assertEquals(AlchemyChainProvider.FETCH_NOT_AVAILABLE_MESSAGE, down.details["error"])
        assertEquals(listOf("eth-sepolia"), down.details["networks"])
        assertFalse(down.details.toString().contains(apiKey), down.details.toString())
    }

    @Test
    fun `a chain without a network mapping fails terminally with a message naming the chain`() {
        val provider = AlchemyChainProvider(
            properties = properties,
            preflight = AlchemyPreflightReport(requiredChains = emptyList(), probedBlockHeights = mapOf("eth-sepolia" to 1L)),
        )

        val failure = assertThrows<ProviderConfigurationException> { provider.fetchObservedEventsPage(pageRequest("local-evm")) }

        assertTrue(failure.message!!.contains("No Alchemy network is mapped for chain local-evm"), failure.message)
        assertTrue(failure.message!!.contains("asset-sync.provider.alchemy.networks.local-evm.network"), failure.message)
        assertEquals(failure.message, provider.lastError())
    }

    @Test
    fun `no required networks is still up and says so`() {
        val provider = AlchemyChainProvider(
            properties = properties,
            preflight = AlchemyPreflightReport(requiredChains = emptyList(), probedBlockHeights = emptyMap()),
        )

        val health = AlchemyChainProviderHealthIndicator(provider).health()

        assertEquals(Status.UP, health.status)
        assertEquals("no-required-networks", health.details["state"])
        assertEquals(emptyList<String>(), health.details["networks"])
    }

    private fun pageRequest(chainId: String): ChainProviderEventsPageRequest =
        ChainProviderEventsPageRequest(
            watchedAddressId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            chainId = chainId,
            address = "0x1111111111111111111111111111111111111111",
            asset = "USDC",
            cursor = null,
            limit = 100,
        )
}
