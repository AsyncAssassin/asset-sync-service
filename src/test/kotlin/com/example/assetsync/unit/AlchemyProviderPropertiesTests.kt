package com.example.assetsync.unit

import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.config.AlchemyAuthMode
import com.example.assetsync.config.AlchemyNetworkProperties
import com.example.assetsync.config.AlchemyProviderProperties
import com.example.assetsync.config.AlchemyStartMode
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyLegacyWatchedAddresses
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyRequiredChain
import com.example.assetsync.infrastructure.provider.alchemy.AlchemyRolloutRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertThrows

/**
 * Locks the Alchemy startup rules that need no database: the static property validation (every
 * violation reported at once, the `>= 4` RPC floor, template placeholders per auth mode), the
 * secret never appearing in `toString`, and the pure rollout rules that combine configuration with
 * registry state.
 */
class AlchemyProviderPropertiesTests {

    private val sepolia = mapOf("eth-sepolia" to AlchemyNetworkProperties(network = "eth-sepolia"))

    @Test
    fun `defaults with a key are valid and use the built-in templates`() {
        val properties = AlchemyProviderProperties(apiKey = "secret-key-123", networks = sepolia)

        assertEquals(emptyList(), properties.staticViolations())
        assertEquals("https://{network}.g.alchemy.com/v2/", properties.endpointTemplate)
        assertEquals("https://{network}.g.alchemy.com/v2/{apiKey}", properties.pathEndpointTemplate)
        assertEquals(AlchemyAuthMode.HEADER, properties.authMode)
        assertEquals(AlchemyStartMode.REGISTRATION_SAFE, properties.startMode)
        assertEquals(8, properties.maxRpcCallsPerFetch)
        assertEquals("eth-sepolia", properties.networkFor("eth-sepolia")?.network)
        assertEquals(null, properties.networkFor("local-evm"))
    }

    @Test
    fun `blank bound templates fall back to the defaults and the key is trimmed`() {
        val properties = AlchemyProviderProperties(
            apiKey = "  secret-key-123  ",
            endpointTemplate = "   ",
            pathEndpointTemplate = "",
        )

        assertEquals("secret-key-123", properties.apiKey)
        assertEquals(AlchemyProviderProperties.DEFAULT_ENDPOINT_TEMPLATE, properties.endpointTemplate)
        assertEquals(AlchemyProviderProperties.DEFAULT_PATH_ENDPOINT_TEMPLATE, properties.pathEndpointTemplate)
    }

    @Test
    fun `missing key is a violation and validate reports it as a configuration exception`() {
        val properties = AlchemyProviderProperties(apiKey = "   ", networks = sepolia)

        val violations = properties.staticViolations()
        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().contains("api-key"), violations.single())
        assertTrue(violations.single().contains("ASSET_SYNC_PROVIDER_ALCHEMY_API_KEY"), violations.single())

        val exception = assertThrows<ProviderConfigurationException> { properties.validate() }
        assertTrue(exception.message!!.startsWith("Alchemy provider configuration is invalid: "), exception.message)
        assertTrue(exception.message!!.contains("api-key"), exception.message)
    }

    @Test
    fun `max rpc calls per fetch below four is rejected and four is accepted`() {
        val three = AlchemyProviderProperties(apiKey = "k", maxRpcCallsPerFetch = 3).staticViolations()
        assertEquals(1, three.size, three.toString())
        assertTrue(three.single().contains("max-rpc-calls-per-fetch must be at least 4"), three.single())

        assertEquals(emptyList(), AlchemyProviderProperties(apiKey = "k", maxRpcCallsPerFetch = 4).staticViolations())
    }

    @Test
    fun `header mode requires the network placeholder, rejects the api key placeholder, and needs an http url`() {
        val noNetwork = AlchemyProviderProperties(apiKey = "k", endpointTemplate = "https://alchemy.example/v2/").staticViolations()
        assertEquals(listOf("asset-sync.provider.alchemy.endpoint-template must contain {network}"), noNetwork)

        val withKey = AlchemyProviderProperties(apiKey = "k", endpointTemplate = "https://{network}.example/v2/{apiKey}").staticViolations()
        assertEquals(1, withKey.size, withKey.toString())
        assertTrue(withKey.single().contains("must not contain {apiKey} in header auth mode"), withKey.single())

        val notHttp = AlchemyProviderProperties(apiKey = "k", endpointTemplate = "ftp://{network}.example/v2/").staticViolations()
        assertEquals(listOf("asset-sync.provider.alchemy.endpoint-template must be an http(s) URL"), notHttp)

        // The path template is not consulted in header mode, so a broken one does not block a header deployment.
        val brokenPathTemplate = AlchemyProviderProperties(apiKey = "k", pathEndpointTemplate = "nonsense").staticViolations()
        assertEquals(emptyList(), brokenPathTemplate)
    }

    @Test
    fun `path mode requires both placeholders in the path template`() {
        val missingKey = AlchemyProviderProperties(
            apiKey = "k",
            authMode = AlchemyAuthMode.PATH,
            pathEndpointTemplate = "https://{network}.example/v2/",
        ).staticViolations()
        assertEquals(1, missingKey.size, missingKey.toString())
        assertTrue(missingKey.single().contains("path-endpoint-template must contain both {network} and {apiKey}"), missingKey.single())

        val valid = AlchemyProviderProperties(apiKey = "k", authMode = AlchemyAuthMode.PATH).staticViolations()
        assertEquals(emptyList(), valid)
    }

    @Test
    fun `network slugs, start blocks, and numeric caps are validated together`() {
        val properties = AlchemyProviderProperties(
            apiKey = "k",
            networks = mapOf(
                "eth-sepolia" to AlchemyNetworkProperties(network = "Eth Sepolia"),
                "eth-mainnet" to AlchemyNetworkProperties(network = "eth-mainnet", startBlock = -1),
            ),
            finalityDepthFallback = 0,
            maxWindowBlocks = 0,
            rateLimitCapacity = 0,
            rateLimitRefillPerSecond = Double.NaN,
        )

        val violations = properties.staticViolations()

        assertEquals(6, violations.size, violations.toString())
        assertTrue(violations.any { it.contains("networks.eth-sepolia.network must be a non-blank Alchemy network slug") }, violations.toString())
        assertTrue(violations.any { it.contains("networks.eth-mainnet.start-block must not be negative") }, violations.toString())
        assertTrue(violations.any { it.contains("finality-depth-fallback must be positive") }, violations.toString())
        assertTrue(violations.any { it.contains("max-window-blocks must be positive") }, violations.toString())
        assertTrue(violations.any { it.contains("rate-limit-capacity must be positive") }, violations.toString())
        assertTrue(violations.any { it.contains("rate-limit-refill-per-second must be positive") }, violations.toString())
    }

    @Test
    fun `toString never contains the api key`() {
        val withKey = AlchemyProviderProperties(apiKey = "secret-key-123", networks = sepolia).toString()
        assertFalse(withKey.contains("secret-key-123"), withKey)
        assertTrue(withKey.contains("apiKey=***"), withKey)
        assertTrue(withKey.contains("eth-sepolia"), withKey)

        val withoutKey = AlchemyProviderProperties().toString()
        assertTrue(withoutKey.contains("apiKey=<unset>"), withoutKey)
    }

    @Test
    fun `rollout rules fail enabled chains with active addresses but no network mapping and name the operator action`() {
        val properties = AlchemyProviderProperties(apiKey = "k", networks = sepolia)
        val required = listOf(
            AlchemyRequiredChain("eth-sepolia", setOf("ERC20")),
            AlchemyRequiredChain("local-evm", setOf("ERC20")),
        )

        val violations = AlchemyRolloutRules.violations(properties, required, emptyList())

        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().contains("with active watched addresses but no Alchemy network mapping: [local-evm]"), violations.single())
        assertTrue(violations.single().contains("disable them in chain_configs"), violations.single())
        assertEquals(emptyList(), AlchemyRolloutRules.unmappedIdleChains(properties, required))
    }

    @Test
    fun `an enabled chain without a network mapping and without active addresses is only reported as idle`() {
        val properties = AlchemyProviderProperties(apiKey = "k", networks = sepolia)
        val required = listOf(
            AlchemyRequiredChain("eth-sepolia", setOf("ERC20"), hasActiveAddresses = false),
            AlchemyRequiredChain("local-evm", setOf("ERC20"), hasActiveAddresses = false),
        )

        assertEquals(emptyList(), AlchemyRolloutRules.violations(properties, required, emptyList()))
        assertEquals(listOf("local-evm"), AlchemyRolloutRules.unmappedIdleChains(properties, required))
    }

    @Test
    fun `configured-block requires a start block only for required chains and only in that mode`() {
        val required = listOf(AlchemyRequiredChain("eth-sepolia", setOf("ERC20")))
        val networks = mapOf(
            "eth-sepolia" to AlchemyNetworkProperties(network = "eth-sepolia"),
            "eth-mainnet" to AlchemyNetworkProperties(network = "eth-mainnet"),
        )

        val registrationSafe = AlchemyProviderProperties(apiKey = "k", networks = networks)
        assertEquals(emptyList(), AlchemyRolloutRules.violations(registrationSafe, required, emptyList()))

        val configuredBlock = AlchemyProviderProperties(apiKey = "k", networks = networks, startMode = AlchemyStartMode.CONFIGURED_BLOCK)
        val violations = AlchemyRolloutRules.violations(configuredBlock, required, emptyList())
        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().contains("start-mode=configured-block requires"), violations.single())
        assertTrue(violations.single().contains("missing for: [eth-sepolia]"), violations.single())
        assertFalse(violations.single().contains("eth-mainnet"), violations.single())

        val configuredWithBlock = AlchemyProviderProperties(
            apiKey = "k",
            networks = mapOf("eth-sepolia" to AlchemyNetworkProperties(network = "eth-sepolia", startBlock = 8_000_000)),
            startMode = AlchemyStartMode.CONFIGURED_BLOCK,
        )
        assertEquals(emptyList(), AlchemyRolloutRules.violations(configuredWithBlock, required, emptyList()))
    }

    @Test
    fun `rollout rules fail legacy watched addresses and unsupported token standards`() {
        val properties = AlchemyProviderProperties(apiKey = "k", networks = sepolia)
        val required = listOf(AlchemyRequiredChain("eth-sepolia", setOf("ERC20", "ERC721")))
        val legacy = listOf(
            AlchemyLegacyWatchedAddresses(chainId = "eth-sepolia", asset = "DAI", count = 2),
            AlchemyLegacyWatchedAddresses(chainId = "eth-sepolia", asset = "WETH", count = 1),
        )

        val violations = AlchemyRolloutRules.violations(properties, required, legacy)

        assertEquals(2, violations.size, violations.toString())
        assertTrue(violations[0].contains("token standard other than ERC20: [eth-sepolia=[ERC20, ERC721]]"), violations[0])
        assertTrue(violations[1].contains("(eth-sepolia, DAI) x2, (eth-sepolia, WETH) x1"), violations[1])
        assertTrue(violations[1].contains("docs/database.md"), violations[1])
    }

    @Test
    fun `rollout rules pass when every required chain is mapped and no legacy address exists`() {
        val properties = AlchemyProviderProperties(apiKey = "k", networks = sepolia)

        assertEquals(
            emptyList(),
            AlchemyRolloutRules.violations(properties, listOf(AlchemyRequiredChain("eth-sepolia", setOf("ERC20"))), emptyList()),
        )
        assertEquals(emptyList(), AlchemyRolloutRules.violations(properties, emptyList(), emptyList()))
    }
}
