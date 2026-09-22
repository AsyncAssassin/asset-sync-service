package com.example.assetsync.infrastructure.provider.alchemy

import com.example.assetsync.application.sync.ProviderConfigurationException
import com.example.assetsync.config.AlchemyProviderProperties
import com.example.assetsync.config.AlchemyStartMode
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.ASSET_CONFIGS
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.CHAIN_CONFIGS
import com.example.assetsync.infrastructure.persistence.jooq.generated.tables.references.WATCHED_ADDRESSES
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

/** An enabled chain that has at least one enabled asset config, so the provider must serve it. */
data class AlchemyRequiredChain(
    val chainId: String,
    val tokenStandards: Set<String>,
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
        val unmapped = requiredChains.filter { properties.networkFor(it.chainId) == null }.map { it.chainId }
        if (unmapped.isNotEmpty()) {
            violations += "enabled chains with enabled asset configs but no Alchemy network mapping: $unmapped " +
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
}

/**
 * Runs once while the Alchemy beans are created, after Liquibase: static configuration rules,
 * the registry rules above, and one `eth_blockNumber` auth probe per required network. Any failure
 * is a scrubbed [ProviderConfigurationException], so a bad key or an unserved chain stops the
 * process before the worker can create retrying sync runs.
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
        val networks = requiredChains
            .mapNotNull { properties.networkFor(it.chainId)?.network }
            .toSortedSet()
        if (networks.isEmpty()) {
            logger.warn("alchemy_preflight_no_required_networks authMode={}", properties.authMode)
        }
        val heights = networks.associateWith { network -> probe(network) }
        logger.info(
            "alchemy_preflight_succeeded authMode={} startMode={} requiredChains={} probedBlockHeights={}",
            properties.authMode,
            properties.startMode,
            requiredChains.map { it.chainId },
            heights,
        )
        return AlchemyPreflightReport(requiredChains = requiredChains, probedBlockHeights = heights)
    }

    private fun probe(network: String): Long =
        try {
            client.blockNumber(network)
        } catch (exception: RuntimeException) {
            throw ProviderConfigurationException(
                scrubber.scrub("Alchemy startup probe failed for network $network: ${exception.message}"),
            )
        }

    private fun loadRequiredChains(): List<AlchemyRequiredChain> =
        dsl
            .select(ASSET_CONFIGS.CHAIN_ID, ASSET_CONFIGS.TOKEN_STANDARD)
            .from(ASSET_CONFIGS)
            .join(CHAIN_CONFIGS).on(CHAIN_CONFIGS.CHAIN_ID.eq(ASSET_CONFIGS.CHAIN_ID))
            .where(ASSET_CONFIGS.ENABLED.eq(true))
            .and(CHAIN_CONFIGS.ENABLED.eq(true))
            .fetch()
            .groupBy({ requireNotNull(it[ASSET_CONFIGS.CHAIN_ID]) }, { requireNotNull(it[ASSET_CONFIGS.TOKEN_STANDARD]) })
            .map { (chainId, standards) -> AlchemyRequiredChain(chainId = chainId, tokenStandards = standards.toSortedSet()) }
            .sortedBy { it.chainId }

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
