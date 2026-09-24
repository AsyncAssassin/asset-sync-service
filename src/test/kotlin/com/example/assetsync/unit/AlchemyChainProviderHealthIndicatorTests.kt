package com.example.assetsync.unit

import com.example.assetsync.AlchemyJsonRpcStubServer
import com.example.assetsync.ScriptedAlchemyChain
import com.example.assetsync.application.account.AssetConfig
import com.example.assetsync.application.account.AssetConfigRepository
import com.example.assetsync.application.sync.AddressConfigurationException
import com.example.assetsync.application.sync.ChainProviderEventsPageRequest
import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.application.sync.ProviderDataInvalidException
import com.example.assetsync.config.AlchemyNetworkProperties
import com.example.assetsync.config.AlchemyProviderProperties
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProvider
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyChainProviderHealthIndicator
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyJsonRpcClient
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyPreflightReport
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyRequiredChain
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.actuate.health.Status
import org.springframework.web.client.RestClient

/**
 * Locks what the Alchemy provider exposes through health: UP after the startup probe with the
 * provider, auth mode, networks, and state; DOWN with `probe-failed` when Alchemy was unavailable
 * at startup; UP again with `fetch-succeeded` after a page; DOWN with the scrubbed error after a
 * failed fetch; and never the API key anywhere in the details.
 */
class AlchemyChainProviderHealthIndicatorTests {

    private val apiKey = "secret-key-123"
    private val watched = "0xabc0000000000000000000000000000000000001"
    private val contract = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"
    private val stub = AlchemyJsonRpcStubServer()
    private val chain = ScriptedAlchemyChain(watchedAddress = watched, contractAddress = contract).also { stub.responder = it.responder() }
    private val properties = AlchemyProviderProperties(
        apiKey = apiKey,
        endpointTemplate = stub.headerEndpointTemplate(),
        networks = mapOf("eth-sepolia" to AlchemyNetworkProperties(network = "eth-sepolia")),
    )

    @AfterTest
    fun tearDown() {
        stub.close()
    }

    @Test
    fun `reports the probe state without secrets, then follows fetch outcomes`() {
        val provider = provider(probedNetworks = mapOf("eth-sepolia" to 100L))
        val indicator = AlchemyChainProviderHealthIndicator(provider)

        val up = indicator.health()
        assertEquals(Status.UP, up.status)
        assertEquals("alchemy", up.details["provider"])
        assertEquals("header", up.details["authMode"])
        assertEquals(listOf("eth-sepolia"), up.details["networks"])
        assertEquals("probe-succeeded", up.details["state"])
        assertNull(up.details["error"])
        assertFalse(up.details.toString().contains(apiKey), up.details.toString())

        val page = provider.fetchObservedEventsPage(pageRequest("eth-sepolia"))
        assertEquals(emptyList(), page.events)
        val afterFetch = indicator.health()
        assertEquals(Status.UP, afterFetch.status)
        assertEquals("fetch-succeeded", afterFetch.details["state"])
        assertNull(afterFetch.details["error"])

        stub.responder = null
        stub.responseStatus = 500
        assertThrows<ChainProviderUnavailableException> { provider.fetchObservedEventsPage(pageRequest("eth-sepolia")) }
        val down = indicator.health()
        assertEquals(Status.DOWN, down.status)
        assertEquals("fetch-failed", down.details["state"])
        assertEquals("Alchemy returned HTTP 500 for network eth-sepolia.", down.details["error"])
        assertEquals(listOf("eth-sepolia"), down.details["networks"])
        assertFalse(down.details.toString().contains(apiKey), down.details.toString())

        stub.responder = chain.responder()
        provider.fetchObservedEventsPage(pageRequest("eth-sepolia"))
        assertEquals(Status.UP, indicator.health().status, "a later successful fetch recovers")
    }

    @Test
    fun `invalid data for one address keeps health up with a scrubbed detail`() {
        val provider = provider(probedNetworks = mapOf("eth-sepolia" to 100L))
        val indicator = AlchemyChainProviderHealthIndicator(provider)
        provider.fetchObservedEventsPage(pageRequest("eth-sepolia"))

        stub.responder = null
        stub.responseStatus = 400
        assertThrows<ProviderDataInvalidException> { provider.fetchObservedEventsPage(pageRequest("eth-sepolia")) }

        val health = indicator.health()
        assertEquals(Status.UP, health.status, "a rejected request for one address is not an Alchemy outage")
        assertEquals("fetch-succeeded", health.details["state"])
        assertEquals("Alchemy returned HTTP 400 for eth_blockNumber on network eth-sepolia.", health.details["lastDataError"])
        assertNull(health.details["error"])
        assertFalse(health.details.toString().contains(apiKey), health.details.toString())
    }

    @Test
    fun `a chain without a network mapping fails that address terminally and keeps health up`() {
        val provider = provider(probedNetworks = mapOf("eth-sepolia" to 1L))

        val failure = assertThrows<ProviderConfigurationException> { provider.fetchObservedEventsPage(pageRequest("local-evm")) }

        assertTrue(failure.message!!.contains("No Alchemy network is mapped for chain local-evm"), failure.message)
        assertTrue(failure.message!!.contains("asset-sync.provider.alchemy.networks.local-evm.network"), failure.message)
        // One address's gap, not an outage: the reason is a detail and the aggregate health stays 200.
        val health = AlchemyChainProviderHealthIndicator(provider).health()
        assertEquals(Status.UP, health.status)
        assertEquals(failure.message, health.details["lastDataError"])
        assertNull(provider.lastError())
    }

    @Test
    fun `a key rejected at fetch time turns health down, unlike one address's gap`() {
        val provider = provider(probedNetworks = mapOf("eth-sepolia" to 100L))
        stub.responder = null
        stub.responseStatus = 401

        val failure = assertThrows<ProviderConfigurationException> { provider.fetchObservedEventsPage(pageRequest("eth-sepolia")) }

        assertFalse(failure is AddressConfigurationException, "a rejected key concerns every address")
        val health = AlchemyChainProviderHealthIndicator(provider).health()
        assertEquals(Status.DOWN, health.status)
        assertEquals("fetch-failed", health.details["state"])
        assertEquals(failure.message, health.details["error"])
        assertNull(health.details["lastDataError"])
        assertFalse(health.details.toString().contains(apiKey), health.details.toString())
    }

    @Test
    fun `no required networks is still up and says so`() {
        val health = AlchemyChainProviderHealthIndicator(provider(probedNetworks = emptyMap())).health()

        assertEquals(Status.UP, health.status)
        assertEquals("no-required-networks", health.details["state"])
        assertEquals(emptyList<String>(), health.details["networks"])
    }

    @Test
    fun `an alchemy unavailable at startup is down until the first successful fetch`() {
        val probeError = "Alchemy startup probe failed for network eth-sepolia: Alchemy returned HTTP 503 for network eth-sepolia."
        val provider = provider(probedNetworks = emptyMap(), unavailableNetworks = mapOf("eth-sepolia" to probeError))
        val indicator = AlchemyChainProviderHealthIndicator(provider)

        val down = indicator.health()
        assertEquals(Status.DOWN, down.status)
        assertEquals("probe-failed", down.details["state"])
        assertEquals(probeError, down.details["error"])
        assertEquals(emptyList<String>(), down.details["networks"])

        provider.fetchObservedEventsPage(pageRequest("eth-sepolia"))
        val up = indicator.health()
        assertEquals(Status.UP, up.status, "the first successful fetch recovers")
        assertEquals("fetch-succeeded", up.details["state"])
        assertNull(up.details["error"])
    }

    private fun provider(
        probedNetworks: Map<String, Long>,
        unavailableNetworks: Map<String, String> = emptyMap(),
    ): AlchemyChainProvider =
        AlchemyChainProvider(
            properties = properties,
            preflight = AlchemyPreflightReport(
                requiredChains = (probedNetworks.keys + unavailableNetworks.keys).map { AlchemyRequiredChain(it, setOf("ERC20")) },
                probedBlockHeights = probedNetworks,
                unavailableNetworks = unavailableNetworks,
            ),
            client = AlchemyJsonRpcClient(restClient = RestClient.builder().build(), properties = properties),
            assetConfigRepository = object : AssetConfigRepository {
                override fun findEnabledByChainIdAndAsset(chainId: String, asset: String): AssetConfig? =
                    AssetConfig(chainId, asset, "ERC20", contract, 6, "USD Coin", true).takeIf { chainId == "eth-sepolia" && asset == "USDC" }
            },
        )

    private fun pageRequest(chainId: String): ChainProviderEventsPageRequest =
        ChainProviderEventsPageRequest(
            watchedAddressId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            chainId = chainId,
            address = watched,
            asset = "USDC",
            cursor = null,
            limit = 100,
        )
}
