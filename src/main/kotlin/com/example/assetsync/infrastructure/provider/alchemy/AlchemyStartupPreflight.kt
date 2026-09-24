package com.example.assetsync.infrastructure.provider.alchemy

import com.example.assetsync.application.sync.ChainProviderUnavailableException
import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.config.AlchemyProviderProperties
import com.example.assetsync.config.AlchemyStartMode
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.ASSET_CONFIGS
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.CHAIN_CONFIGS
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.WATCHED_ADDRESSES
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

/**
 * An enabled chain that has at least one enabled asset config, so the provider must serve it once
 * it has active watched addresses.
 */
data class AlchemyRequiredChain(
    val chainId: String,
    val tokenStandards: Set<String>,
    val hasActiveAddresses: Boolean = true,
)

/** Active watched addresses grouped by `(chain_id, asset)` that no enabled asset config covers. */
data class AlchemyLegacyWatchedAddresses(
    val chainId: String,
    val asset: String,
    val count: Int,
)

/** What the startup preflight proved; the provider bean cannot exist without it. */
class AlchemyPreflightReport(
    val requiredChains: List<AlchemyRequiredChain>,
    val probedBlockHeights: Map<String, Long>,
    /** Networks whose probe met an availability failure, with the scrubbed error; the provider starts DOWN. */
    val unavailableNetworks: Map<String, String> = emptyMap(),
) {
    val probedNetworks: List<String>
        get() = probedBlockHeights.keys.sorted()
}

/**
 * Rollout rules that combine configuration with registry state. Pure so they can be unit-tested;
 * messages name the offending chains or `(chain_id, asset)` pairs and the operator action.
 */
object AlchemyRolloutRules {

    const val SUPPORTED_TOKEN_STANDARD = "ERC20"

    fun violations(
        properties: AlchemyProviderProperties,
        requiredChains: List<AlchemyRequiredChain>,
        legacyAddresses: List<AlchemyLegacyWatchedAddresses>,
    ): List<String> {
        val violations = mutableListOf<String>()
        val unmapped = requiredChains
            .filter { it.hasActiveAddresses && properties.networkFor(it.chainId) == null }
            .map { it.chainId }
        if (unmapped.isNotEmpty()) {
            violations += "enabled chains with active watched addresses but no Alchemy network mapping: $unmapped " +
                "(disable them in chain_configs or add ${AlchemyProviderProperties.PREFIX}.networks.<chain-id>.network)"
        }
        val unsupported = requiredChains
            .filter { chain -> chain.tokenStandards.any { it != SUPPORTED_TOKEN_STANDARD } }
            .map { "${it.chainId}=${it.tokenStandards}" }
        if (unsupported.isNotEmpty()) {
            violations += "enabled asset configs with a token standard other than $SUPPORTED_TOKEN_STANDARD: $unsupported"
        }
        if (properties.startMode == AlchemyStartMode.CONFIGURED_BLOCK) {
            val missingStartBlock = requiredChains
                .filter { chain -> properties.networkFor(chain.chainId)?.let { it.startBlock == null } == true }
                .map { it.chainId }
            if (missingStartBlock.isNotEmpty()) {
                violations += "start-mode=configured-block requires ${AlchemyProviderProperties.PREFIX}.networks.<chain-id>.start-block " +
                    "for every enabled chain; missing for: $missingStartBlock"
            }
        }
        if (legacyAddresses.isNotEmpty()) {
            val pairs = legacyAddresses.joinToString { "(${it.chainId}, ${it.asset}) x${it.count}" }
            violations += "active watched addresses without an enabled asset config: $pairs " +
                "(enable or add the asset configs, or deactivate the addresses; see the rollout preflight query in docs/database.md)"
        }
        return violations
    }

    /**
     * Enabled chains without a network mapping and without active watched addresses, such as the
     * seeded `local-evm` on a fresh database. Nothing needs them yet, so startup only warns, and
     * registration and re-enabling refuse addresses there. An address enabled there by SQL fails
     * its syncs terminally, and the next startup refuses the chain through [violations] until it is
     * mapped or the address or the chain is disabled.
     */
    fun unmappedIdleChains(properties: AlchemyProviderProperties, requiredChains: List<AlchemyRequiredChain>): List<String> =
        requiredChains
            .filter { !it.hasActiveAddresses && properties.networkFor(it.chainId) == null }
            .map { it.chainId }
}

/**
 * Runs once while the Alchemy beans are created, after Liquibase: static configuration rules,
 * the registry rules above, and one `eth_blockNumber` auth probe per required network. A rule
 * violation or a probe that the key or the configuration fails (401, 403, JSON-RPC `-32600`, or
 * an answer that is not a block number, which for this fixed request means a wrong endpoint) is a
 * scrubbed [ProviderConfigurationException], so a bad key or an unserved chain stops the process
 * before the worker can create retrying sync runs. A probe that meets an availability failure
 * (5xx, 429, a timeout, a transport error) does not: the provider starts in the `probe-failed`
 * state and health is DOWN, while the REST API and outbox publishing keep working. Sync runs retry
 * with backoff up to `asset-sync.sync.worker.max-attempts` and then fail; nothing probes again, so
 * the state clears with the first successful fetch.
 */
class AlchemyStartupPreflight(
    private val properties: AlchemyProviderProperties,
    private val dsl: DSLContext,
    private val client: AlchemyJsonRpcClient,
) {

    private val logger = LoggerFactory.getLogger(AlchemyStartupPreflight::class.java)
    private val scrubber = AlchemySecretScrubber(properties.apiKey)

    fun run(): AlchemyPreflightReport {
        properties.validate()
        val requiredChains = loadRequiredChains()
        val legacyAddresses = loadLegacyWatchedAddresses()
        val violations = AlchemyRolloutRules.violations(properties, requiredChains, legacyAddresses)
        if (violations.isNotEmpty()) {
            throw ProviderConfigurationException(
                scrubber.scrub("Alchemy rollout preflight failed: ${violations.joinToString("; ")}"),
            )
        }
        val idleChains = AlchemyRolloutRules.unmappedIdleChains(properties, requiredChains)
        if (idleChains.isNotEmpty()) {
            logger.warn(
                "alchemy_preflight_unmapped_chains_skipped chains={} reason=no_active_watched_addresses " +
                    "action=\"map the chains to use them under Alchemy, or disable them\"",
                idleChains,
            )
        }
        val networks = requiredChains
            .mapNotNull { properties.networkFor(it.chainId)?.network }
            .toSortedSet()
        if (networks.isEmpty()) {
            logger.warn("alchemy_preflight_no_required_networks authMode={}", properties.authMode)
        }
        val heights = sortedMapOf<String, Long>()
        val unavailable = sortedMapOf<String, String>()
        networks.forEach { network ->
            try {
                heights[network] = client.blockNumber(network)
            } catch (exception: ChainProviderUnavailableException) {
                val error = probeFailure(network, exception)
                unavailable[network] = error
                logger.warn("alchemy_preflight_probe_unavailable network={} error={}", network, error)
            } catch (exception: RuntimeException) {
                throw ProviderConfigurationException(probeFailure(network, exception))
            }
        }
        logger.info(
            "alchemy_preflight_succeeded authMode={} startMode={} requiredChains={} probedBlockHeights={} unavailableNetworks={}",
            properties.authMode,
            properties.startMode,
            requiredChains.map { it.chainId },
            heights,
            unavailable.keys,
        )
        return AlchemyPreflightReport(requiredChains = requiredChains, probedBlockHeights = heights, unavailableNetworks = unavailable)
    }

    private fun probeFailure(network: String, exception: RuntimeException): String =
        scrubber.scrub("Alchemy startup probe failed for network $network: ${exception.message}")

    private fun loadRequiredChains(): List<AlchemyRequiredChain> {
        val chainsWithActiveAddresses = dsl
            .selectDistinct(WATCHED_ADDRESSES.CHAIN_ID)
            .from(WATCHED_ADDRESSES)
            .where(WATCHED_ADDRESSES.STATUS.eq("ACTIVE"))
            .fetch { requireNotNull(it.value1()) }
            .toSet()
        return dsl
            .select(ASSET_CONFIGS.CHAIN_ID, ASSET_CONFIGS.TOKEN_STANDARD)
            .from(ASSET_CONFIGS)
            .join(CHAIN_CONFIGS).on(CHAIN_CONFIGS.CHAIN_ID.eq(ASSET_CONFIGS.CHAIN_ID))
            .where(ASSET_CONFIGS.ENABLED.eq(true))
            .and(CHAIN_CONFIGS.ENABLED.eq(true))
            .fetch()
            .groupBy({ requireNotNull(it[ASSET_CONFIGS.CHAIN_ID]) }, { requireNotNull(it[ASSET_CONFIGS.TOKEN_STANDARD]) })
            .map { (chainId, standards) ->
                AlchemyRequiredChain(
                    chainId = chainId,
                    tokenStandards = standards.toSortedSet(),
                    hasActiveAddresses = chainId in chainsWithActiveAddresses,
                )
            }
            .sortedBy { it.chainId }
    }

    /** The same query as the operator preflight in docs/database.md, grouped by `(chain_id, asset)`. */
    private fun loadLegacyWatchedAddresses(): List<AlchemyLegacyWatchedAddresses> =
        dsl
            .select(WATCHED_ADDRESSES.CHAIN_ID, WATCHED_ADDRESSES.ASSET, DSL.count())
            .from(WATCHED_ADDRESSES)
            .join(CHAIN_CONFIGS).on(CHAIN_CONFIGS.CHAIN_ID.eq(WATCHED_ADDRESSES.CHAIN_ID))
            .leftJoin(ASSET_CONFIGS).on(ASSET_CONFIGS.CHAIN_ID.eq(WATCHED_ADDRESSES.CHAIN_ID))
            .and(ASSET_CONFIGS.ASSET.eq(DSL.upper(WATCHED_ADDRESSES.ASSET)))
            .and(ASSET_CONFIGS.ENABLED.eq(true))
            .where(WATCHED_ADDRESSES.STATUS.eq("ACTIVE"))
            .and(CHAIN_CONFIGS.ENABLED.eq(true))
            .and(ASSET_CONFIGS.CHAIN_ID.isNull)
            .groupBy(WATCHED_ADDRESSES.CHAIN_ID, WATCHED_ADDRESSES.ASSET)
            .orderBy(WATCHED_ADDRESSES.CHAIN_ID, WATCHED_ADDRESSES.ASSET)
            .fetch { record ->
                AlchemyLegacyWatchedAddresses(
                    chainId = requireNotNull(record.value1()),
                    asset = requireNotNull(record.value2()),
                    count = record.value3(),
                )
            }
}
